/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.ScalarDecodeRobustnessModel;
import org.postgresql.test.data.EdgeCase;
import org.postgresql.test.data.Int4EdgeCases;
import org.postgresql.test.data.Int8EdgeCases;
import org.postgresql.test.data.IntArrayEdgeCases;
import org.postgresql.test.data.TextArrayEdgeCases;
import org.postgresql.test.data.TextEdgeCases;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The single registry of Jazzer {@code @FuzzTest} targets in this module: for each target it records the
 * built-in type OIDs the target exercises per wire read direction, and -- for the targets whose parameter
 * carries a value (a {@code byte[]}, a {@link String}, an {@code int}, or a {@code long}) -- how to derive
 * a representative seed corpus from the shared edge-case catalogues.
 *
 * <p>This registry covers only the hand-written targets: the container decoders and the round-trip
 * targets. The uniform scalar decode targets are generated from the codec registry into
 * {@code GeneratedScalarDecodeRobustnessFuzzTest}, the primitive-capability parity targets into
 * {@code GeneratedPrimitiveCapabilityFuzzTest}, and the coercion-reader matrix per scalar into
 * {@code Generated<Type>CoercionReaderFuzzTest}; {@link JazzerSeedCorpusGenerator} seeds the scalar family
 * straight from {@link ScalarDecodeRobustnessModel}, so none of the generated families is listed or
 * accounted for here.
 *
 * <p>It is the source of truth for two consumers, so the two never drift:
 * <ul>
 *   <li>{@link JazzerSeedCorpusGenerator} turns each target's {@link Target#seeds() seeds} into the
 *       per-method seed files under {@code src/test/resources}, using Jazzer's own {@code ArgumentsMutator}
 *       so the on-disk byte layout is correct for the target's signature.</li>
 *   <li>{@link JazzerCodecCoverageArchTest} cross-checks that every hand-written {@code @FuzzTest} method is
 *       registered, and that the OIDs a target claims to read are real built-in codec OIDs.</li>
 * </ul>
 *
 * <p>Not every target is seedable. The {@link com.code_intelligence.jazzer.api.FuzzedDataProvider}
 * targets (the geometric, {@code json}/{@code jsonb}, {@code bit}/{@code varbit}, and {@code interval}
 * ones) consume raw libFuzzer bytes through a provider, not a typed argument, so a value cannot be
 * reverse-encoded into a seed file; they carry no seeds here. The array-binary and {@code bytea}
 * targets are seedable in principle but their edge-case catalogues carry no typed {@link EdgeCase#value()}
 * (they are read-only literals), so a canonical binary wire cannot be encoded from them; they too carry no
 * seeds. Every such omission is intentional and noted on the target below.
 */
final class JazzerFuzzTargets {

  private JazzerFuzzTargets() {
  }

  /** Derives a target's seed argument values; invoked only by the generator, never by the coverage test. */
  @FunctionalInterface
  interface Seeder {
    List<NamedArg> seeds() throws SQLException;
  }

  /** A named seed argument value: the file name stem, and the value handed to {@code ArgumentsMutator}. */
  static final class NamedArg {
    private final String name;
    private final Object value;

    NamedArg(String name, Object value) {
      this.name = name;
      this.value = value;
    }

    String name() {
      return name;
    }

    Object value() {
      return value;
    }
  }

  /** One {@code @FuzzTest} target: its identity, the OIDs it reads per format, and its seed source. */
  static final class Target {
    private final Class<?> testClass;
    private final String method;
    private final Set<Integer> readsBinary;
    private final Set<Integer> readsText;
    private final Seeder seeder;

    Target(Class<?> testClass, String method, Set<Integer> readsBinary, Set<Integer> readsText,
        Seeder seeder) {
      this.testClass = testClass;
      this.method = method;
      this.readsBinary = readsBinary;
      this.readsText = readsText;
      this.seeder = seeder;
    }

    Class<?> testClass() {
      return testClass;
    }

    String method() {
      return method;
    }

    Set<Integer> readsBinary() {
      return readsBinary;
    }

    Set<Integer> readsText() {
      return readsText;
    }

    List<NamedArg> seeds() throws SQLException {
      return seeder.seeds();
    }
  }

  private static final List<NamedArg> NO_SEEDS = Collections.emptyList();

  // A few point-composite text literals for compositeLiteral: there is no CompositeEdgeCases catalogue, so
  // these are inline. The point composite is (x int4, y int4, label text); the labels cover the empty
  // label, a plain word, and a quoted value carrying the field separator.
  private static final String[][] POINT_LITERALS = {
      {"origin", "(0,0,)"},
      {"unit", "(1,1,unit)"},
      {"range_ends", "(-2147483648,2147483647,edge)"},
      {"quoted_comma", "(1,2,\"a,b\")"},
  };

  private static final List<Target> TARGETS = buildTargets();

  /** The registered targets, one per hand-written {@code @FuzzTest} method across the module's five classes. */
  static List<Target> all() {
    return TARGETS;
  }

  /** The union of the built-in OIDs some hand-written target reads in the binary format. */
  static Set<Integer> readsBinaryOids() {
    Set<Integer> out = new TreeSet<>();
    for (Target target : TARGETS) {
      out.addAll(target.readsBinary());
    }
    return out;
  }

  /** The union of the built-in OIDs some hand-written target reads in the text format. */
  static Set<Integer> readsTextOids() {
    Set<Integer> out = new TreeSet<>();
    for (Target target : TARGETS) {
      out.addAll(target.readsText());
    }
    return out;
  }

  private static List<Target> buildTargets() {
    List<Target> targets = new ArrayList<>();

    // --- JazzerScalarCodecFuzzTest: round-trip targets read both formats of their type. --------------
    Class<?> scalar = JazzerScalarCodecFuzzTest.class;
    // int4/int8/text carry a typed value() and a fixed-shape argument, so they are seeded from their
    // boundary catalogues; the rest of this class draws from a FuzzedDataProvider and cannot be seeded.
    targets.add(roundTrip(scalar, "int4RoundTrip", Oid.INT4,
        () -> typedValues(Int4EdgeCases.ALL)));
    targets.add(roundTrip(scalar, "int8RoundTrip", Oid.INT8,
        () -> typedValues(Int8EdgeCases.ALL)));
    targets.add(roundTrip(scalar, "textRoundTrip", Oid.TEXT,
        () -> literalStrings(TextEdgeCases.ALL)));
    targets.add(roundTrip(scalar, "int2RoundTrip", Oid.INT2, JazzerFuzzTargets::noSeeds));
    // bytea's edge cases are read-only literals (no typed value()), so no canonical byte[] seed.
    targets.add(roundTrip(scalar, "byteaRoundTrip", Oid.BYTEA, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "oid8RoundTrip", Oid.OID8, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "xid8RoundTrip", Oid.XID8, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "float4RoundTrip", Oid.FLOAT4, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "float8RoundTrip", Oid.FLOAT8, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "boolRoundTrip", Oid.BOOL, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "numericRoundTrip", Oid.NUMERIC, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "jsonRoundTrip", Oid.JSON, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "jsonbRoundTrip", Oid.JSONB, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "bitRoundTrip", Oid.BIT, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "varbitRoundTrip", Oid.VARBIT, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "intervalRoundTrip", Oid.INTERVAL, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "pointRoundTrip", Oid.POINT, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "boxRoundTrip", Oid.BOX, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "lineRoundTrip", Oid.LINE, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "lsegRoundTrip", Oid.LSEG, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "pathRoundTrip", Oid.PATH, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "polygonRoundTrip", Oid.POLYGON, JazzerFuzzTargets::noSeeds));
    targets.add(roundTrip(scalar, "circleRoundTrip", Oid.CIRCLE, JazzerFuzzTargets::noSeeds));
    // The point composite round-trips its int4 and text leaf codecs in both formats.
    targets.add(new Target(scalar, "regularStructRoundTrip", both(Oid.INT4, Oid.TEXT),
        both(Oid.INT4, Oid.TEXT), JazzerFuzzTargets::noSeeds));

    // The uniform scalar decode targets (numeric/int4/text binary and text, and every other built-in
    // scalar) are generated into GeneratedScalarDecodeRobustnessFuzzTest, the primitive-capability parity
    // targets into GeneratedPrimitiveCapabilityFuzzTest, and the coercion-reader matrix per scalar into
    // Generated<Type>CoercionReaderFuzzTest; the generated families are seeded (or not) from their own
    // sources, so none is registered here. This registry now covers only the hand-written container and
    // round-trip targets.

    // --- JazzerBinaryContainerDecodeFuzzTest: adversarial container binary wire. ---------------------
    // The container decoders drive their leaf codecs' binary decode. The array/composite edge-case
    // literals carry no typed value(), so no canonical binary wire is encoded for these here.
    Class<?> container = JazzerBinaryContainerDecodeFuzzTest.class;
    targets.add(new Target(container, "int4ArrayBinary", only(Oid.INT4), none(),
        JazzerFuzzTargets::noSeeds));
    targets.add(new Target(container, "textArrayBinary", only(Oid.TEXT), none(),
        JazzerFuzzTargets::noSeeds));
    targets.add(new Target(container, "compositeBinary", both(Oid.INT4, Oid.TEXT), none(),
        JazzerFuzzTargets::noSeeds));
    targets.add(new Target(container, "rangeBinary", only(Oid.INT4), none(), JazzerFuzzTargets::noSeeds));
    targets.add(new Target(container, "multirangeBinary", only(Oid.INT4), none(),
        JazzerFuzzTargets::noSeeds));

    // --- JazzerTextLiteralDecodeFuzzTest: adversarial container/scalar text literal. -----------------
    Class<?> literal = JazzerTextLiteralDecodeFuzzTest.class;
    targets.add(new Target(literal, "int4ArrayLiteral", none(), only(Oid.INT4),
        () -> literalStrings(IntArrayEdgeCases.ALL)));
    targets.add(new Target(literal, "textArrayLiteral", none(), only(Oid.TEXT),
        () -> literalStrings(TextArrayEdgeCases.ALL)));
    targets.add(new Target(literal, "compositeLiteral", none(), both(Oid.INT4, Oid.TEXT),
        JazzerFuzzTargets::pointLiterals));
    targets.add(new Target(literal, "rangeLiteral", none(), only(Oid.INT4), JazzerFuzzTargets::noSeeds));
    targets.add(new Target(literal, "multirangeLiteral", none(), only(Oid.INT4),
        JazzerFuzzTargets::noSeeds));

    // The coercion-reader matrix is generated per scalar into Generated<Type>CoercionReaderFuzzTest (see
    // CoercionReaderFuzzTestGenerator); like the other generated families it is not registered here, and
    // its FuzzedDataProvider targets carry no reverse-encoded seed.

    return Collections.unmodifiableList(targets);
  }

  private static Target roundTrip(Class<?> testClass, String method, int oid, Seeder seeder) {
    return new Target(testClass, method, only(oid), only(oid), seeder);
  }

  private static Set<Integer> only(int oid) {
    return Collections.singleton(oid);
  }

  private static Set<Integer> both(int first, int second) {
    return new LinkedHashSet<>(Arrays.asList(first, second));
  }

  private static Set<Integer> none() {
    return Collections.emptySet();
  }

  // --- Seed derivations --------------------------------------------------------------------------

  private static List<NamedArg> noSeeds() {
    return NO_SEEDS;
  }

  /** Each edge case's literal text as a {@link String}, for a {@code String} target. */
  private static List<NamedArg> literalStrings(List<EdgeCase> cases) {
    List<NamedArg> out = new ArrayList<>();
    for (EdgeCase edgeCase : cases) {
      out.add(new NamedArg(edgeCase.name(), edgeCase.literal()));
    }
    return out;
  }

  /** Each edge case's typed value (an {@link Integer} or {@link Long}) for a primitive-argument target. */
  private static List<NamedArg> typedValues(List<EdgeCase> cases) {
    List<NamedArg> out = new ArrayList<>();
    for (EdgeCase edgeCase : cases) {
      Object value = edgeCase.value();
      if (value != null) {
        out.add(new NamedArg(edgeCase.name(), value));
      }
    }
    return out;
  }

  private static List<NamedArg> pointLiterals() {
    List<NamedArg> out = new ArrayList<>();
    for (String[] entry : POINT_LITERALS) {
      out.add(new NamedArg(entry[0], entry[1]));
    }
    return out;
  }
}
