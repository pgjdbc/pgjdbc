/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.ScalarDecodeRobustnessModel;
import org.postgresql.fuzzkit.ScalarDecodeRobustnessNaming;
import org.postgresql.jdbc.PgType;
import org.postgresql.test.data.Bit1EdgeCases;
import org.postgresql.test.data.BoolEdgeCases;
import org.postgresql.test.data.BoxEdgeCases;
import org.postgresql.test.data.ByteaEdgeCases;
import org.postgresql.test.data.CircleEdgeCases;
import org.postgresql.test.data.DateEdgeCases;
import org.postgresql.test.data.EdgeCase;
import org.postgresql.test.data.Float4EdgeCases;
import org.postgresql.test.data.Float8EdgeCases;
import org.postgresql.test.data.Int2EdgeCases;
import org.postgresql.test.data.Int4EdgeCases;
import org.postgresql.test.data.Int8EdgeCases;
import org.postgresql.test.data.IntervalEdgeCases;
import org.postgresql.test.data.JsonEdgeCases;
import org.postgresql.test.data.LineEdgeCases;
import org.postgresql.test.data.LsegEdgeCases;
import org.postgresql.test.data.MoneyEdgeCases;
import org.postgresql.test.data.NumericEdgeCases;
import org.postgresql.test.data.OidEdgeCases;
import org.postgresql.test.data.PathEdgeCases;
import org.postgresql.test.data.PointEdgeCases;
import org.postgresql.test.data.PolygonEdgeCases;
import org.postgresql.test.data.TextEdgeCases;
import org.postgresql.test.data.TimeEdgeCases;
import org.postgresql.test.data.TimeTzEdgeCases;
import org.postgresql.test.data.TimestampEdgeCases;
import org.postgresql.test.data.TimestampTzEdgeCases;
import org.postgresql.test.data.UuidEdgeCases;
import org.postgresql.test.data.VarbitEdgeCases;
import org.postgresql.test.jazzer.JazzerFuzzTargets.NamedArg;
import org.postgresql.test.jazzer.JazzerFuzzTargets.Target;

import com.code_intelligence.jazzer.mutation.ArgumentsMutator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes the Jazzer seed corpus for the module's {@code @FuzzTest} targets, for both the hand-written
 * registry ({@link JazzerFuzzTargets}) and the generated scalar decode targets ({@link ScalarDecodeRobustnessModel}
 * -> {@code GeneratedScalarDecodeRobustnessFuzzTest}). Each seed is serialised to the per-method seed
 * directory Jazzer's JUnit engine loads as classpath resources:
 *
 * <pre>{@code
 *   <resourcesRoot>/org/postgresql/test/jazzer/<TestClass>Inputs/<method>/<edge-case-name>
 * }</pre>
 *
 * <p>The on-disk byte layout of a seed file depends on the {@code @FuzzTest} parameter type -- a
 * {@code byte[]} argument carries a length prefix, a {@link String} does not, and the primitives use a
 * fixed-width form. Rather than hand-encode any of that, every seed goes through the same
 * {@link ArgumentsMutator#writeAny} the Jazzer engine itself uses to serialise a corpus entry, so the
 * bytes are correct by construction for whatever the target's signature is and stay correct across Jazzer
 * upgrades. In non-fuzzing (regression) mode the JUnit engine replays these files as bounded inputs.
 *
 * <p>The generated scalar targets are seeded from the same {@link ScalarDecodeRobustnessModel} the source
 * generator drives, mapped to a per-OID edge-case catalogue: a binary target seeds from the canonical
 * binary wire of each edge case that has a bindable value; a text target seeds from each edge case's
 * literal. A disabled target ({@code numeric} binary, finding F3) never runs, so it is not seeded, and an
 * OID with no catalogue is left with the empty input only. The method name comes from
 * {@link ScalarDecodeRobustnessNaming}, so the seed directory matches the generated method exactly.
 *
 * <p>The generator is deterministic and idempotent: regenerating overwrites the same file names with the
 * same content. It owns each per-method directory it writes, so before writing it removes any stale seed
 * file it no longer produces (an edge case that was renamed or dropped). It never deletes a Jazzer crash
 * reproducer ({@code crash-*}): those are checked-in regression inputs it does not own. It never touches a
 * directory for a target it does not seed.
 *
 * <p>Invoked from Gradle as a {@code JavaExec} before {@code processTestResources}; the single argument is
 * the seed-corpus root to write into ({@code build/jazzer/corpus} in this module's Gradle wiring, added to
 * the test resources -- never {@code src/test/resources}, so a regenerated seed can never be committed by
 * mistake; only the hand-curated {@code crash-*} reproducers live under {@code src/test/resources}). It
 * needs no database connection: every value is encoded through the offline codec context.
 */
public final class JazzerSeedCorpusGenerator {

  private static final String PACKAGE_PATH = "org/postgresql/test/jazzer";

  private JazzerSeedCorpusGenerator() {
  }

  /**
   * Generates the seed corpus under the resources root given as the single argument.
   *
   * @param args {@code args[0]} is the seed-corpus root to write into (the module wires {@code build/jazzer/corpus})
   * @throws Exception if a seed cannot be derived or written
   */
  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException(
          "usage: JazzerSeedCorpusGenerator <resources-root>; got " + args.length + " arguments");
    }
    Path resourcesRoot = Paths.get(args[0]);
    int targetsSeeded = 0;
    int filesWritten = 0;

    // The hand-written targets: containers, round-trip, parity, and the coercion reader.
    for (Target target : JazzerFuzzTargets.all()) {
      List<NamedArg> seeds = target.seeds();
      if (seeds.isEmpty()) {
        continue;
      }
      Method method = fuzzMethod(target);
      Path methodDir = resourcesRoot.resolve(PACKAGE_PATH)
          .resolve(target.testClass().getSimpleName() + "Inputs")
          .resolve(target.method());
      filesWritten += writeMethodCorpus(method, methodDir, seeds);
      targetsSeeded++;
    }

    // The generated scalar decode targets, seeded from the shared model and the per-OID catalogues.
    Class<?> generatedClass = GeneratedScalarDecodeRobustnessFuzzTest.class;
    Path generatedInputs = resourcesRoot.resolve(PACKAGE_PATH)
        .resolve(generatedClass.getSimpleName() + "Inputs");
    Map<Integer, List<EdgeCase>> catalogues = edgeCasesByOid();
    for (ScalarDecodeRobustnessModel.Target target : ScalarDecodeRobustnessModel.targets()) {
      if (target.disabled()) {
        continue;
      }
      // An enumerated target is a @ParameterizedTest that supplies its own exhaustive inputs, not a
      // @FuzzTest, so it has no seed corpus to write.
      if (target.enumeratedByteDomain()) {
        continue;
      }
      List<EdgeCase> cases = catalogues.get(target.oid());
      if (cases == null) {
        continue;
      }
      List<NamedArg> seeds = target.format() == Format.TEXT
          ? textSeeds(cases)
          : binarySeeds(target.oid(), cases);
      if (seeds.isEmpty()) {
        continue;
      }
      Method method = generatedMethod(generatedClass, target);
      filesWritten += writeMethodCorpus(method, generatedInputs.resolve(target.methodName()), seeds);
      targetsSeeded++;
    }

    System.out.println("JazzerSeedCorpusGenerator: wrote " + filesWritten + " seed files across "
        + targetsSeeded + " targets under " + resourcesRoot.resolve(PACKAGE_PATH));
  }

  /** The canonical binary wire of each edge case that has a bindable value, as the target's {@code byte[]}. */
  private static List<NamedArg> binarySeeds(int oid, List<EdgeCase> cases) throws SQLException {
    PgType type = CodecFuzzSupport.scalar(oid, "t" + oid, 'X');
    CodecContext ctx = CodecFuzzSupport.builtins();
    List<NamedArg> out = new ArrayList<>();
    for (EdgeCase edgeCase : cases) {
      Object value = edgeCase.value();
      if (value == null) {
        // No representable Java value (NaN, Infinity, ...): read-side only, so it cannot be encoded here.
        continue;
      }
      byte[] wire;
      try {
        wire = Codecs.encode(value, type, ctx, Format.BINARY).toByteArray();
      } catch (SQLException cannotBind) {
        // A best-effort seed the codec will not bind in binary; skip it rather than fail generation.
        continue;
      }
      out.add(new NamedArg(edgeCase.name(), wire));
    }
    return out;
  }

  /** Each edge case's literal text as a {@link String}, for a generated {@code _text} target. */
  private static List<NamedArg> textSeeds(List<EdgeCase> cases) {
    List<NamedArg> out = new ArrayList<>();
    for (EdgeCase edgeCase : cases) {
      out.add(new NamedArg(edgeCase.name(), edgeCase.literal()));
    }
    return out;
  }

  /** Resolves the generated {@code @FuzzTest} method for a target by its name and parameter type. */
  private static Method generatedMethod(Class<?> generatedClass, ScalarDecodeRobustnessModel.Target target)
      throws NoSuchMethodException {
    Class<?> parameterType = target.format() == Format.TEXT ? String.class : byte[].class;
    return generatedClass.getDeclaredMethod(target.methodName(), parameterType);
  }

  // Maps a built-in scalar OID to its edge-case catalogue. Not every generated target is covered: an OID
  // absent here is seeded with the empty input only. Keyed by OID so the model and the seeder agree.
  private static Map<Integer, List<EdgeCase>> edgeCasesByOid() {
    Map<Integer, List<EdgeCase>> map = new LinkedHashMap<>();
    map.put(Oid.INT2, Int2EdgeCases.ALL);
    map.put(Oid.INT4, Int4EdgeCases.ALL);
    map.put(Oid.INT8, Int8EdgeCases.ALL);
    map.put(Oid.OID, OidEdgeCases.ALL);
    map.put(Oid.NUMERIC, NumericEdgeCases.ALL);
    map.put(Oid.FLOAT4, Float4EdgeCases.ALL);
    map.put(Oid.FLOAT8, Float8EdgeCases.ALL);
    map.put(Oid.BOOL, BoolEdgeCases.ALL);
    map.put(Oid.TEXT, TextEdgeCases.ALL);
    map.put(Oid.BYTEA, ByteaEdgeCases.ALL);
    map.put(Oid.DATE, DateEdgeCases.ALL);
    map.put(Oid.TIME, TimeEdgeCases.ALL);
    map.put(Oid.TIMETZ, TimeTzEdgeCases.ALL);
    map.put(Oid.TIMESTAMP, TimestampEdgeCases.ALL);
    map.put(Oid.TIMESTAMPTZ, TimestampTzEdgeCases.ALL);
    map.put(Oid.INTERVAL, IntervalEdgeCases.ALL);
    map.put(Oid.JSON, JsonEdgeCases.ALL);
    map.put(Oid.UUID, UuidEdgeCases.ALL);
    map.put(Oid.MONEY, MoneyEdgeCases.ALL);
    map.put(Oid.BIT, Bit1EdgeCases.ALL);
    map.put(Oid.VARBIT, VarbitEdgeCases.ALL);
    map.put(Oid.POINT, PointEdgeCases.ALL);
    map.put(Oid.BOX, BoxEdgeCases.ALL);
    map.put(Oid.LINE, LineEdgeCases.ALL);
    map.put(Oid.LSEG, LsegEdgeCases.ALL);
    map.put(Oid.PATH, PathEdgeCases.ALL);
    map.put(Oid.POLYGON, PolygonEdgeCases.ALL);
    map.put(Oid.CIRCLE, CircleEdgeCases.ALL);
    return map;
  }

  /**
   * Writes one target's seeds into {@code methodDir}, removing any stale seed file it no longer produces.
   *
   * @return the number of seed files written
   */
  private static int writeMethodCorpus(Method method, Path methodDir, List<NamedArg> seeds)
      throws IOException {
    Files.createDirectories(methodDir);

    // A mutator built from the exact target method, so writeAny serialises each seed in that signature's
    // on-disk layout. One instance is reused across the target's seeds.
    ArgumentsMutator mutator = ArgumentsMutator.forMethodOrThrow(method, false);

    Set<String> written = new LinkedHashSet<>();
    for (NamedArg seed : seeds) {
      String fileName = sanitise(seed.name());
      if (!written.add(fileName)) {
        throw new IllegalStateException("Duplicate seed file name '" + fileName + "' for "
            + method.getName() + "; edge-case names must be unique per target");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      mutator.writeAny(out, new Object[]{seed.value()});
      Files.write(methodDir.resolve(fileName), out.toByteArray());
    }

    removeOrphans(methodDir, written);
    return written.size();
  }

  /**
   * Deletes stale seed files in {@code methodDir} the generator no longer produces. It only removes files
   * it owns (edge-case-derived names); a Jazzer crash reproducer ({@code crash-*}) is a checked-in
   * regression input the generator never wrote and never deletes.
   */
  private static void removeOrphans(Path methodDir, Set<String> keep) throws IOException {
    List<Path> orphans = new ArrayList<>();
    try (Stream<Path> entries = Files.list(methodDir)) {
      entries.forEach(entry -> {
        String name = entry.getFileName().toString();
        if (Files.isRegularFile(entry) && !keep.contains(name) && !name.startsWith("crash-")) {
          orphans.add(entry);
        }
      });
    }
    for (Path orphan : orphans) {
      Files.delete(orphan);
    }
  }

  /** Resolves the {@code @FuzzTest} method of a target by its unique name. */
  private static Method fuzzMethod(Target target) {
    Method found = null;
    for (Method candidate : target.testClass().getDeclaredMethods()) {
      if (candidate.getName().equals(target.method())) {
        if (found != null) {
          throw new IllegalStateException("Ambiguous @FuzzTest method '" + target.method()
              + "' in " + target.testClass().getName() + "; names must be unique");
        }
        found = candidate;
      }
    }
    if (found == null) {
      throw new IllegalStateException("No method '" + target.method() + "' in "
          + target.testClass().getName());
    }
    return found;
  }

  /** Maps an edge-case name to a stable, filesystem-safe file name. */
  private static String sanitise(String name) {
    return name.replaceAll("[^A-Za-z0-9_.-]", "_");
  }
}
