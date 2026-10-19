/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;

/**
 * A {@link SocketFactory} that wraps sockets to count "roundtrips" as write→read direction
 * transitions on the underlying socket. One or more writes followed by one or more reads is
 * one roundtrip: the client stopped writing and waited for the server. Ten consecutive Sync
 * messages with no intervening read count as zero roundtrips.
 *
 * <p>Counters are <em>per-connection</em>, not static. A test allocates a {@link Counters}
 * instance via {@link #register()}, passes its {@link Counters#key()} via
 * {@code PGProperty.SOCKET_FACTORY_ARG}, and reads cumulative values back through that
 * instance. Two tests running in parallel each get their own {@code Counters}.
 *
 * <p>The registry holds each {@code Counters} weakly: a test must keep it reachable, for instance
 * in a field, until it has opened its last connection. Once the {@code Counters} is
 * garbage-collected, the factory no longer finds its key, and a later {@link #register()} call
 * removes the entry. A test needs no cleanup call.
 *
 * <p>All counters are cumulative. Tests read deltas: snapshot values before an operation,
 * run it, snapshot after, and assert on the difference.
 *
 * <p>Under SSL, roundtrip counts still reflect real wire direction changes (SSLSocket uses
 * the underlying plain socket's streams), but handshake traffic contributes to the counters —
 * snapshot after the connection is established.
 */
public class CountingSocketFactory extends SocketFactory {

  /**
   * Per-connection counters. Owned by the test; the factory looks it up by {@link #key} and
   * writes into it.
   */
  public static final class Counters {
    public final AtomicLong roundtrips = new AtomicLong();
    public final AtomicLong flushes = new AtomicLong();
    public final AtomicLong bytesOut = new AtomicLong();
    public final AtomicLong bytesIn = new AtomicLong();

    private final String key;

    private Counters(String key) {
      this.key = key;
    }

    public String key() {
      return key;
    }
  }

  /**
   * Registry entry that does not keep its {@link Counters} reachable, and remembers the key so
   * the entry can be removed once the {@code Counters} is collected.
   */
  private static final class CountersRef extends WeakReference<Counters> {
    final String key;

    CountersRef(Counters counters, ReferenceQueue<Counters> queue) {
      super(counters, queue);
      this.key = counters.key;
    }
  }

  private static final ConcurrentMap<String, CountersRef> REGISTRY = new ConcurrentHashMap<>();
  private static final ReferenceQueue<Counters> COLLECTED = new ReferenceQueue<>();

  /**
   * Allocates a fresh {@link Counters} and registers it so a factory instantiated with its
   * key can find it. The registry holds the {@code Counters} weakly: the caller keeps it
   * reachable while connections are being opened, and needs no cleanup afterwards.
   */
  public static Counters register() {
    removeCollected();
    String key = UUID.randomUUID().toString();
    Counters c = new Counters(key);
    REGISTRY.put(key, new CountersRef(c, COLLECTED));
    return c;
  }

  /**
   * Removes the registry entry for {@code counters}, so the factory no longer finds its key.
   * Calling it is optional: once the {@code Counters} is garbage-collected, a later
   * {@link #register()} call removes the entry.
   */
  public static void unregister(Counters counters) {
    REGISTRY.remove(counters.key);
  }

  private static void removeCollected() {
    Reference<? extends Counters> ref;
    while ((ref = COLLECTED.poll()) != null) {
      CountersRef countersRef = (CountersRef) ref;
      REGISTRY.remove(countersRef.key, countersRef);
    }
  }

  private final Counters counters;

  public CountingSocketFactory(String key) {
    CountersRef ref = key == null ? null : REGISTRY.get(key);
    Counters c = ref == null ? null : ref.get();
    if (c == null) {
      throw new IllegalArgumentException(
          "No CountingSocketFactory.Counters registered for key=" + key
              + ". Call CountingSocketFactory.register(), keep the returned Counters reachable "
              + "(for instance, in a field of the test), and pass its key() via "
              + "PGProperty.SOCKET_FACTORY_ARG.");
    }
    this.counters = c;
  }

  @Override
  public Socket createSocket() {
    return new CountingSocket(counters);
  }

  @Override
  public Socket createSocket(String host, int port) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(InetAddress host, int port) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
      int localPort) {
    throw new UnsupportedOperationException();
  }

  private enum Dir { NONE, WRITE, READ }

  private static final class CountingSocket extends Socket {
    private final Counters counters;
    private final AtomicReference<Dir> lastDir = new AtomicReference<>(Dir.NONE);
    private @Nullable InputStream countingIn;
    private @Nullable OutputStream countingOut;

    CountingSocket(Counters counters) {
      this.counters = counters;
    }

    @Override
    public synchronized InputStream getInputStream() throws IOException {
      InputStream countingIn = this.countingIn;
      if (countingIn == null) {
        this.countingIn = countingIn = new CountingInputStream(super.getInputStream(), this);
      }
      return countingIn;
    }

    @Override
    public synchronized OutputStream getOutputStream() throws IOException {
      OutputStream countingOut = this.countingOut;
      if (countingOut == null) {
        this.countingOut = countingOut = new CountingOutputStream(super.getOutputStream(), this);
      }
      return countingOut;
    }

    void onWrite(int n) {
      if (n <= 0) {
        return;
      }
      counters.bytesOut.addAndGet(n);
      lastDir.set(Dir.WRITE);
    }

    void onRead(int n) {
      if (n <= 0) {
        return;
      }
      counters.bytesIn.addAndGet(n);
      if (lastDir.getAndSet(Dir.READ) == Dir.WRITE) {
        counters.roundtrips.incrementAndGet();
      }
    }
  }

  private static final class CountingInputStream extends FilterInputStream {
    private final CountingSocket socket;

    CountingInputStream(InputStream in, CountingSocket socket) {
      super(in);
      this.socket = socket;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      socket.onRead(b == -1 ? 0 : 1);
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = in.read(b, off, len);
      socket.onRead(n);
      return n;
    }
  }

  private static final class CountingOutputStream extends OutputStream {
    private final OutputStream out;
    private final CountingSocket socket;

    CountingOutputStream(OutputStream out, CountingSocket socket) {
      this.out = out;
      this.socket = socket;
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      socket.onWrite(1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
      socket.onWrite(len);
    }

    @Override
    public void flush() throws IOException {
      out.flush();
      socket.counters.flushes.incrementAndGet();
    }

    @Override
    public void close() throws IOException {
      out.close();
    }
  }
}
