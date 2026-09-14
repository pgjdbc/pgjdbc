import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Leases host ports for the test PostgreSQL server of a git worktree.
 *
 * <p>All worktrees of a repository share one registry in git refs: the ref
 * {@code refs/pgjdbc/ports/<slot>} points to a blob that holds {@code <unix time> <worktree path>}.
 * Slot N owns ports {@code 20000 + 3 * N} to {@code 20002 + 3 * N}, for the primary server and two
 * replicas, so the 3333 slots cover ports 20000 to 29998.
 *
 * <pre>
 *   java docker/bin/PortLease.java acquire     print "slot port port port", taking a slot if needed
 *   java docker/bin/PortLease.java show        print the same line if this worktree holds a slot
 *   java docker/bin/PortLease.java release     give up the slot of this worktree
 *   java docker/bin/PortLease.java addresses   print the addresses a server on leased ports listens on:
 *                                              127.0.0.1, and ::1 when the host has an IPv6 loopback
 * </pre>
 *
 * <p>A slot is free when it has no ref, or when its worktree is missing from {@code git worktree list}
 * or marked prunable there. When no slot is free, acquire takes the slot whose lease was renewed
 * longest ago. acquire renews the lease of the worktree it runs in, and keeps that slot even while its
 * ports are in use. A free slot, or a slot of another worktree, is not taken while one of its ports
 * accepts connections on one of those addresses, or when one of its ports is on the list of well-known
 * ports.
 *
 * <p>The file runs as a single source file with Java 17 or later, with no build step.
 */
public final class PortLease {
  private static final String REF_PREFIX = "refs/pgjdbc/ports/";
  private static final int FIRST_PORT = 20000;
  private static final int SLOT_COUNT = 3333;

  /**
   * Ports in 20000-29998 that common services use: Syncthing, Fluentd, Minecraft, RabbitMQ,
   * CockroachDB, Redis Sentinel, RethinkDB, Kafka in Docker, and Steam in 27015-27030, which also
   * covers MongoDB on 27017-27019.
   */
  private static final BitSet SKIPPED_PORTS = new BitSet();

  static {
    for (int port : new int[] {22000, 24224, 25565, 25672, 26257, 26379, 28015, 29015, 29092}) {
      SKIPPED_PORTS.set(port);
    }
    SKIPPED_PORTS.set(27015, 27031);
  }

  /** A ref of the registry that points to a blob. */
  private record Ref(String objectId, int slot) {
  }

  /**
   * A lease read from the registry.
   *
   * @param renewedAt seconds since the epoch when acquire last wrote the lease
   * @param worktreePath the worktree that holds the lease; null when the blob is not a lease
   */
  private record Lease(int slot, String objectId, long renewedAt, String worktreePath) {
  }

  /**
   * A slot acquire may take.
   *
   * @param expectedObjectId the object id the ref must still point to; null for a slot without a ref
   */
  private record Candidate(int slot, String expectedObjectId, boolean heldByCurrentWorktree) {
  }

  private record ProcessResult(int exitCode, byte[] stdout) {
  }

  private PortLease() {
  }

  public static void main(String[] args) throws Exception {
    ProcessResult toplevel = run(null, ProcessBuilder.Redirect.DISCARD, "git", "rev-parse", "--show-toplevel");
    if (toplevel.exitCode() != 0) {
      die("run it inside a git worktree");
    }
    String currentWorktree = new String(toplevel.stdout(), StandardCharsets.UTF_8).strip();
    String command = args.length == 1 ? args[0] : "";
    switch (command) {
      case "acquire" -> acquire(currentWorktree);
      case "show" -> show(currentWorktree);
      case "release" -> release(currentWorktree);
      case "addresses" -> loopbackAddresses().forEach(System.out::println);
      default -> die("usage: PortLease acquire|show|release|addresses");
    }
  }

  private static void acquire(String currentWorktree) throws Exception {
    byte[] leaseContent = (Instant.now().getEpochSecond() + " " + currentWorktree + "\n")
        .getBytes(StandardCharsets.UTF_8);
    String leaseObjectId = new String(git(leaseContent, "hash-object", "-w", "--stdin"), StandardCharsets.UTF_8)
        .strip();
    String zeroObjectId = "0".repeat(leaseObjectId.length());
    List<String> addresses = loopbackAddresses();
    for (Candidate candidate : candidateSlots(currentWorktree)) {
      // The server of this worktree may be running, so its own slot is taken even when busy.
      if (!candidate.heldByCurrentWorktree() && hasPortInUse(candidate.slot(), addresses)) {
        continue;
      }
      String expected = candidate.expectedObjectId() == null ? zeroObjectId : candidate.expectedObjectId();
      // update-ref fails when the ref no longer points to the expected object id, so two worktrees
      // acquiring at once never take the same slot. That failure is expected, so git's message is dropped.
      ProcessResult update = run(null, ProcessBuilder.Redirect.DISCARD,
          "git", "update-ref", REF_PREFIX + candidate.slot(), leaseObjectId, expected);
      if (update.exitCode() == 0) {
        System.out.println(formatLease(candidate.slot()));
        return;
      }
    }
    die("every slot from " + FIRST_PORT + " to " + (FIRST_PORT + 3 * SLOT_COUNT - 1)
        + " is skipped or has a port in use");
  }

  private static void show(String currentWorktree) throws Exception {
    leases().stream()
        .filter(lease -> currentWorktree.equals(lease.worktreePath()))
        .findFirst()
        .ifPresent(lease -> System.out.println(formatLease(lease.slot())));
  }

  private static void release(String currentWorktree) throws Exception {
    for (Lease lease : leases()) {
      if (currentWorktree.equals(lease.worktreePath())) {
        git(null, "update-ref", "-d", REF_PREFIX + lease.slot(), lease.objectId());
      }
    }
  }

  /** Returns the slots acquire may take, best first: own leases, free slots, then evictable leases. */
  private static List<Candidate> candidateSlots(String currentWorktree) throws Exception {
    List<Lease> leases = leases();
    Set<String> liveWorktreePaths = liveWorktrees();
    Map<Integer, Lease> leaseBySlot = leases.stream().collect(Collectors.toMap(Lease::slot, Function.identity()));

    Stream<Candidate> own = leases.stream()
        .filter(lease -> currentWorktree.equals(lease.worktreePath()))
        .map(lease -> new Candidate(lease.slot(), lease.objectId(), true));
    // A slot is free without a lease, or when the worktree of its lease is gone.
    Stream<Candidate> free = IntStream.range(0, SLOT_COUNT)
        .filter(slot -> !hasSkippedPort(slot))
        .filter(slot -> !leaseBySlot.containsKey(slot)
            || !liveWorktreePaths.contains(leaseBySlot.get(slot).worktreePath()))
        .mapToObj(slot -> new Candidate(slot,
            leaseBySlot.containsKey(slot) ? leaseBySlot.get(slot).objectId() : null, false));
    Stream<Candidate> evictable = leases.stream()
        .filter(lease -> !currentWorktree.equals(lease.worktreePath())
            && liveWorktreePaths.contains(lease.worktreePath()))
        .sorted(Comparator.comparingLong(Lease::renewedAt))
        .map(lease -> new Candidate(lease.slot(), lease.objectId(), false));
    return Stream.of(own, free, evictable).flatMap(Function.identity()).collect(Collectors.toList());
  }

  /** Reads every ref of the registry that points to a blob. */
  private static List<Lease> leases() throws Exception {
    List<Ref> refs = gitText("for-each-ref", "--format=%(objectname) %(objecttype) %(refname)", REF_PREFIX)
        .lines()
        .map(line -> line.split(" ", 3))
        .filter(fields -> fields.length == 3 && fields[1].equals("blob")
            && fields[2].substring(REF_PREFIX.length()).matches("[0-9]{1,9}"))
        .map(fields -> new Ref(fields[0], Integer.parseInt(fields[2].substring(REF_PREFIX.length()))))
        .collect(Collectors.toList());
    if (refs.isEmpty()) {
      return List.of();
    }
    String objectIds = refs.stream().map(ref -> ref.objectId() + "\n").collect(Collectors.joining());
    InputStream batch = new ByteArrayInputStream(
        git(objectIds.getBytes(StandardCharsets.UTF_8), "cat-file", "--batch"));
    List<Lease> leases = new ArrayList<>();
    for (Ref ref : refs) {
      String[] header = readLine(batch).split(" ");
      if (header.length != 3) {
        continue; // "<object id> missing"
      }
      byte[] content = batch.readNBytes(Integer.parseInt(header[2]));
      batch.skip(1);
      leases.add(parseLease(ref, new String(content, StandardCharsets.UTF_8)));
    }
    return leases;
  }

  private static Lease parseLease(Ref ref, String content) {
    String[] fields = content.lines().findFirst().orElse("").split(" ", 2);
    try {
      return new Lease(ref.slot(), ref.objectId(), Long.parseLong(fields[0]), fields[1]);
    } catch (RuntimeException e) {
      return new Lease(ref.slot(), ref.objectId(), 0, null);
    }
  }

  /** Returns the paths of the worktrees git does not consider prunable. */
  private static Set<String> liveWorktrees() throws Exception {
    return Arrays.stream(gitText("worktree", "list", "--porcelain").split("\n\n"))
        .filter(block -> block.lines().noneMatch(line -> line.startsWith("prunable")))
        .flatMap(block -> block.lines().filter(line -> line.startsWith("worktree ")))
        .map(line -> line.substring("worktree ".length()))
        .collect(Collectors.toSet());
  }

  /**
   * Returns 127.0.0.1, and ::1 when this host can bind it. Docker refuses to publish a port on ::1 on a
   * host without an IPv6 loopback address, and the container does not start.
   */
  private static List<String> loopbackAddresses() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("::1"))) {
      return List.of("127.0.0.1", "::1");
    } catch (IOException e) {
      return List.of("127.0.0.1");
    }
  }

  private static int basePort(int slot) {
    return FIRST_PORT + 3 * slot;
  }

  private static boolean hasSkippedPort(int slot) {
    int next = SKIPPED_PORTS.nextSetBit(basePort(slot));
    return next >= 0 && next <= basePort(slot) + 2;
  }

  /**
   * Returns whether something accepts connections on one of the ports of the slot, on one of the addresses.
   *
   * <p>The check connects rather than binds: a {@link ServerSocket} binds with SO_REUSEADDR, and on macOS
   * that bind succeeds while another process listens on 127.0.0.1 at the same port.
   */
  private static boolean hasPortInUse(int slot, List<String> addresses) {
    for (int port = basePort(slot); port <= basePort(slot) + 2; port++) {
      for (String address : addresses) {
        try (Socket socket = new Socket()) {
          socket.connect(new InetSocketAddress(address, port), 200);
          return true;
        } catch (IOException e) {
          // Nothing accepts connections on this address and port.
        }
      }
    }
    return false;
  }

  private static String formatLease(int slot) {
    int base = basePort(slot);
    return slot + " " + base + " " + (base + 1) + " " + (base + 2);
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    for (int b = in.read(); b != -1 && b != '\n'; b = in.read()) {
      line.write(b);
    }
    return line.toString(StandardCharsets.UTF_8);
  }

  private static String gitText(String... args) throws Exception {
    return new String(git(null, args), StandardCharsets.UTF_8);
  }

  /** Runs git with its error output shown, and returns its output; exits with an error when git fails. */
  private static byte[] git(byte[] input, String... args) throws Exception {
    String[] command = Stream.concat(Stream.of("git"), Arrays.stream(args)).toArray(String[]::new);
    ProcessResult result = run(input, ProcessBuilder.Redirect.INHERIT, command);
    if (result.exitCode() != 0) {
      die(String.join(" ", command) + " exited with " + result.exitCode());
    }
    return result.stdout();
  }

  private static ProcessResult run(byte[] input, ProcessBuilder.Redirect stderr, String... command) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command).redirectError(stderr);
    // Input goes through a file, so git never waits on a pipe this process has not read yet.
    Path stdin = input == null ? null : Files.write(Files.createTempFile("pg-port-lease", ".in"), input);
    if (stdin != null) {
      builder.redirectInput(stdin.toFile());
    }
    try {
      Process process = builder.start();
      if (stdin == null) {
        process.getOutputStream().close();
      }
      byte[] stdout = process.getInputStream().readAllBytes();
      return new ProcessResult(process.waitFor(), stdout);
    } finally {
      if (stdin != null) {
        Files.delete(stdin);
      }
    }
  }

  private static void die(String message) {
    System.err.println("pg-port-lease: " + message);
    System.exit(1);
  }
}
