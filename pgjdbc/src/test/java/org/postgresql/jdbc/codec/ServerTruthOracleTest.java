/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.core.ServerVersion;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGline;
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.test.TestUtil;
import org.postgresql.test.data.BoxEdgeCases;
import org.postgresql.test.data.EdgeCase;
import org.postgresql.test.data.IntervalEdgeCases;
import org.postgresql.test.data.NumericEdgeCases;
import org.postgresql.test.data.TimeTzEdgeCases;
import org.postgresql.test.data.TimestampTzEdgeCases;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGRange;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Server-truth net for the binary codecs: the server decides whether the driver's binary encoder is
 * correct, independently of the driver's decoder. See {@link ServerTruthOracle} for the mechanism and
 * how it differs from the round-trip {@link CodecParityRoundtripTest}.
 *
 * <p>The priority types are the ones whose canonical text carries information a round-trip can drop:
 * {@code numeric} (scale / trailing zeros), {@code interval}, {@code timestamptz}/{@code timetz}
 * (session offset), and geometry ({@code box} corner normalisation).</p>
 */
class ServerTruthOracleTest {

  private static Connection con;
  private static Connection binaryCon;
  private static int addrOid;
  private static int addrArrayOid;
  private static boolean haveRanges;
  private static boolean haveNumericInfinity;
  private static int int4rangeOid;
  private static int int8rangeOid;
  private static int numrangeOid;

  @BeforeAll
  static void setUpClass() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createCompositeType(con, "server_truth_addr", "street text, zip int4");
    long[] addr = ParityHarness.oidAndArray(con, "server_truth_addr");
    addrOid = (int) addr[0];
    addrArrayOid = (int) addr[1];
    haveRanges = TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2);
    haveNumericInfinity = TestUtil.haveMinimumServerVersion(con, ServerVersion.v14);
    if (haveRanges) {
      // Range OIDs are installation-assigned, not Oid constants, so resolve them by name.
      int4rangeOid = (int) ParityHarness.oidAndArray(con, "int4range")[0];
      int8rangeOid = (int) ParityHarness.oidAndArray(con, "int8range")[0];
      numrangeOid = (int) ParityHarness.oidAndArray(con, "numrange")[0];
    }
    // A connection that requests binary receive for every value: prepareThreshold=-1 forces a
    // standalone Describe before the first Bind, so the server returns binary. Used by the
    // decode-truth cases, which pin the driver's binary decode-to-string against the server ::text.
    binaryCon = ParityHarness.openBinary(null);
    pinGucs(con);
    pinGucs(binaryCon);
  }

  /**
   * Pins the GUCs that steer {@code ::text} so the canonical server text is deterministic across
   * environments. numeric needs none of these, but the temporal and float cases do.
   */
  private static void pinGucs(Connection c) throws Exception {
    try (Statement st = c.createStatement()) {
      st.execute("SET extra_float_digits = 3");
      st.execute("SET IntervalStyle = 'postgres'");
      st.execute("SET DateStyle = 'ISO, MDY'");
      st.execute("SET TIME ZONE 'UTC'");
    }
  }

  @AfterAll
  static void tearDownClass() throws Exception {
    if (con != null) {
      TestUtil.dropType(con, "server_truth_addr");
    }
    TestUtil.closeDB(con);
    TestUtil.closeDB(binaryCon);
  }

  /** Builds a codec bind value from a PostgreSQL literal; may reject a literal the type cannot hold. */
  @FunctionalInterface
  private interface BindFromLiteral {
    Object apply(String literal) throws Exception;
  }

  /** A catalogue with the named cases removed (they are handled or pinned separately). */
  private static List<EdgeCase> without(List<EdgeCase> cases, String... exclude) {
    Set<String> drop = new HashSet<>(Arrays.asList(exclude));
    List<EdgeCase> kept = new ArrayList<>();
    for (EdgeCase e : cases) {
      if (!drop.contains(e.name())) {
        kept.add(e);
      }
    }
    return kept;
  }

  /** Encode-truth over the testkit catalogue cases that carry a bind {@link EdgeCase#value()}. */
  private static List<DynamicTest> encodeEdges(String prefix, int oid, String typeName,
      List<EdgeCase> cases) {
    List<DynamicTest> t = new ArrayList<>();
    for (EdgeCase e : cases) {
      Object value = e.value();
      if (value != null) {
        t.add(DynamicTest.dynamicTest(prefix + "/" + e.name(), () ->
            ServerTruthOracle.assertEncodeTruth(con, oid, typeName, value, e.literal())));
      }
    }
    return t;
  }

  /**
   * Encode-truth for a literal-only catalogue: the bind value is built from the literal via the type's
   * own {@code PG*(String)} constructor (as the catalogue's javadoc notes). A literal the driver's type
   * cannot hold (an overflow edge case) is skipped -- decode-truth still exercises it.
   */
  private static List<DynamicTest> encodeEdgesVia(String prefix, int oid, String typeName,
      List<EdgeCase> cases, BindFromLiteral ctor) {
    List<DynamicTest> t = new ArrayList<>();
    for (EdgeCase e : cases) {
      Object value;
      try {
        value = ctor.apply(e.literal());
      } catch (Exception unheld) {
        continue;
      }
      Object bind = value;
      t.add(DynamicTest.dynamicTest(prefix + "/" + e.name(), () ->
          ServerTruthOracle.assertEncodeTruth(con, oid, typeName, bind, e.literal())));
    }
    return t;
  }

  @TestFactory
  List<DynamicTest> numeric() {
    // The scale, precision and integer-boundary corners come from the shared testkit NumericEdgeCases
    // (the same catalogue the differential oracle and fuzzers use); the BigDecimal-valued ones bind
    // directly, and the NaN/Infinity sentinels -- which the catalogue keeps literal-only -- bind
    // through the Double path here. numeric infinity is PostgreSQL 14+.
    List<DynamicTest> t =
        new ArrayList<>(encodeEdges("numeric", Oid.NUMERIC, "numeric", NumericEdgeCases.ALL));
    for (EdgeCase e : NumericEdgeCases.SPECIAL) {
      double sentinel = "NaN".equals(e.literal()) ? Double.NaN
          : "Infinity".equals(e.literal()) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
      if (Double.isInfinite(sentinel) && !haveNumericInfinity) {
        continue;
      }
      t.add(DynamicTest.dynamicTest("numeric/" + e.name(), () ->
          ServerTruthOracle.assertEncodeTruth(con, Oid.NUMERIC, "numeric", sentinel, e.literal())));
    }
    return t;
  }

  @TestFactory
  List<DynamicTest> box() {
    // Corners in both orders, degenerate, signed and large: from testkit BoxEdgeCases. The bind value
    // is built from the literal via new PGbox(String). The server normalises corners to (high, low),
    // so a correct encoding of the coordinates matches regardless of the order given.
    return encodeEdgesVia("box", Oid.BOX, "box", BoxEdgeCases.ALL, PGbox::new);
  }

  @TestFactory
  List<DynamicTest> interval() {
    // interval is not in the driver's default binary send set, so this is the only path that reaches
    // its binary encoder. Values come from testkit IntervalEdgeCases via new PGInterval(String);
    // literals the driver's PGInterval cannot hold (overflow) are skipped. half_microsecond is
    // excluded: new PGInterval rounds 0.5us up to 1us while the server rounds the literal to 0, so the
    // constructed value and the literal denote different intervals -- a PGInterval parse quirk, not an
    // encoder bug.
    return encodeEdgesVia("interval", Oid.INTERVAL, "interval",
        without(IntervalEdgeCases.ALL, "half_microsecond"), PGInterval::new);
  }

  private static DynamicTest encode(String name, int oid, String typeName, Object value,
      String literal) {
    return DynamicTest.dynamicTest(name, () ->
        ServerTruthOracle.assertEncodeTruth(con, oid, typeName, value, literal));
  }

  /**
   * Regression net: run the encode-truth oracle across the remaining binary scalar codecs. Each
   * value is paired with an independent literal denoting the same value, so the server's ::text of
   * the codec's binary encoding must match the server's parse of the literal.
   */
  @TestFactory
  List<DynamicTest> scalars() {
    List<DynamicTest> t = new ArrayList<>();
    t.add(encode("int2/max", Oid.INT2, "int2", (short) 32767, "32767"));
    t.add(encode("int2/min", Oid.INT2, "int2", (short) -32768, "-32768"));
    t.add(encode("int4/answer", Oid.INT4, "int4", 42, "42"));
    t.add(encode("int4/min", Oid.INT4, "int4", Integer.MIN_VALUE, "-2147483648"));
    t.add(encode("int8/max", Oid.INT8, "int8", Long.MAX_VALUE, "9223372036854775807"));
    t.add(encode("float4/simple", Oid.FLOAT4, "float4", 1.5f, "1.5"));
    t.add(encode("float4/tenth", Oid.FLOAT4, "float4", 0.1f, "0.1"));
    t.add(encode("float8/pi", Oid.FLOAT8, "float8", Math.PI, "3.141592653589793"));
    t.add(encode("float8/tenth", Oid.FLOAT8, "float8", 0.1, "0.1"));
    t.add(encode("bool/true", Oid.BOOL, "bool", Boolean.TRUE, "true"));
    t.add(encode("bool/false", Oid.BOOL, "bool", Boolean.FALSE, "false"));
    t.add(encode("bytea/bytes", Oid.BYTEA, "bytea",
        new byte[]{0, 1, (byte) 0xFE, (byte) 0xFF}, "\\x0001feff"));
    // A zero-length binary value cannot travel this path: setPGobject binds a PGBinaryObject of
    // lengthInBytes()==0 as SQL NULL, so an empty bytea/text is indistinguishable from NULL here.
    t.add(encode("uuid", Oid.UUID, "uuid",
        java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
        "550e8400-e29b-41d4-a716-446655440000"));
    return t;
  }

  /**
   * Regression net for the geometric codecs. Their encode-truth holds even though their
   * {@code getString} rendering diverges from the server (see {@link #decodeKnownDivergences()}):
   * the server's ::text of the binary encoding is compared against the server's parse of the literal,
   * so the driver's own coordinate formatting never enters the comparison.
   */
  @TestFactory
  List<DynamicTest> geometry() {
    List<DynamicTest> t = new ArrayList<>();
    t.add(encode("point", Oid.POINT, "point", new PGpoint(1.5, 2.5), "(1.5,2.5)"));
    t.add(encode("circle", Oid.CIRCLE, "circle", new PGcircle(1, 2, 3), "<(1,2),3>"));
    t.add(encode("lseg", Oid.LSEG, "lseg", new PGlseg(1, 2, 3, 4), "[(1,2),(3,4)]"));
    t.add(encode("line", Oid.LINE, "line", new PGline(1, 2, 3), "{1,2,3}"));
    t.add(encode("path/closed", Oid.PATH, "path",
        new PGpath(new PGpoint[]{new PGpoint(1, 1), new PGpoint(2, 2), new PGpoint(3, 3)}, false),
        "((1,1),(2,2),(3,3))"));
    t.add(encode("path/open", Oid.PATH, "path",
        new PGpath(new PGpoint[]{new PGpoint(1, 1), new PGpoint(2, 2)}, true),
        "[(1,1),(2,2)]"));
    t.add(encode("polygon", Oid.POLYGON, "polygon",
        new PGpolygon(new PGpoint[]{new PGpoint(1, 1), new PGpoint(2, 2), new PGpoint(3, 3)}),
        "((1,1),(2,2),(3,3))"));
    return t;
  }

  /**
   * Composite and array-of-composite encode-truth. {@link org.postgresql.jdbc.codec.CompositeCodec}
   * takes a {@link java.sql.Struct}; the server quotes composite fields on output, so the value and
   * the (unquoted) literal converge to the same canonical text through the server.
   */
  @TestFactory
  List<DynamicTest> composites() {
    List<DynamicTest> t = new ArrayList<>();
    t.add(DynamicTest.dynamicTest("composite/flat", () ->
        ServerTruthOracle.assertEncodeTruth(con, addrOid, "server_truth_addr",
            con.createStruct("server_truth_addr", new Object[]{"Main St", 62701}),
            "(Main St,62701)")));
    t.add(DynamicTest.dynamicTest("composite/null-field", () ->
        ServerTruthOracle.assertEncodeTruth(con, addrOid, "server_truth_addr",
            con.createStruct("server_truth_addr", new Object[]{"Elm St", null}),
            "(Elm St,)")));
    t.add(DynamicTest.dynamicTest("composite/quoted-field", () ->
        ServerTruthOracle.assertEncodeTruth(con, addrOid, "server_truth_addr",
            con.createStruct("server_truth_addr", new Object[]{"a,\"b\"", 7}),
            "(\"a,\\\"b\\\"\",7)")));
    return t;
  }

  /**
   * Array encode-truth: {@link org.postgresql.jdbc.codec.ArrayCodec} over a primitive leaf and over
   * a composite element. The array OID is what the codec encodes and what the parameter is bound as;
   * the {@code type[]} name resolves through the server's {@code regtype} cast, so it serves both the
   * bind-side lookup and the SQL cast. The array-of-composite case exercises the ArrayCodec /
   * CompositeCodec composition end to end.
   */
  @TestFactory
  List<DynamicTest> arrays() {
    List<DynamicTest> t = new ArrayList<>();
    t.add(encode("int4[]", Oid.INT4_ARRAY, "int4[]", new Integer[]{1, 2, 3}, "{1,2,3}"));
    t.add(encode("int4[]/negatives", Oid.INT4_ARRAY, "int4[]",
        new Integer[]{-1, 0, Integer.MAX_VALUE}, "{-1,0,2147483647}"));
    t.add(encode("text[]", Oid.TEXT_ARRAY, "text[]",
        new String[]{"a", "b,c", "d"}, "{a,\"b,c\",d}"));
    t.add(DynamicTest.dynamicTest("array-of-composite", () ->
        ServerTruthOracle.assertEncodeTruth(con, addrArrayOid, "server_truth_addr[]",
            new Object[]{
                con.createStruct("server_truth_addr", new Object[]{"Main St", 1}),
                con.createStruct("server_truth_addr", new Object[]{"Elm St", 2})},
            "{\"(Main St,1)\",\"(Elm St,2)\"}")));
    return t;
  }

  /**
   * Range encode-truth: {@link org.postgresql.jdbc.codec.RangeCodec} composes on the bound codec, so
   * the bound's own encoding is exercised through the range. numrange in particular carries a
   * {@code numeric} scale in each bound, so the server ::text pins the dscale of the bounds the binary
   * encoder produced -- a place a bound-scale bug would hide.
   */
  @TestFactory
  List<DynamicTest> ranges() {
    List<DynamicTest> t = new ArrayList<>();
    if (!haveRanges) {
      return t;
    }
    t.add(DynamicTest.dynamicTest("int4range/closed-open", () ->
        ServerTruthOracle.assertEncodeTruth(con, int4rangeOid, "int4range",
            new PGRange<>(1, 10, true, false), "[1,10)")));
    t.add(DynamicTest.dynamicTest("int4range/empty", () ->
        ServerTruthOracle.assertEncodeTruth(con, int4rangeOid, "int4range",
            PGRange.empty(), "empty")));
    t.add(DynamicTest.dynamicTest("int4range/infinite-upper", () ->
        ServerTruthOracle.assertEncodeTruth(con, int4rangeOid, "int4range",
            new PGRange<>(5, null, true, false), "[5,)")));
    t.add(DynamicTest.dynamicTest("int8range/closed-open", () ->
        ServerTruthOracle.assertEncodeTruth(con, int8rangeOid, "int8range",
            new PGRange<>(1L, 10L, true, false), "[1,10)")));
    // numrange bounds keep their scale: a dropped trailing zero on either bound fails here.
    t.add(DynamicTest.dynamicTest("numrange/scaled-bounds", () ->
        ServerTruthOracle.assertEncodeTruth(con, numrangeOid, "numrange",
            new PGRange<>(new BigDecimal("1.50"), new BigDecimal("2.500"), true, false),
            "[1.50,2.500)")));
    return t;
  }

  private static DynamicTest decodeTruth(String name, int oid, String typeName, String literal) {
    return DynamicTest.dynamicTest(name, () ->
        ServerTruthOracle.assertDecodeTruth(binaryCon, oid, typeName, literal));
  }

  /**
   * Decode truth: the driver's {@code getString} of a binary-received value must equal the server's
   * {@code ::text}. Unlike the round-trip parity net (which compares the two wire formats to each
   * other), this pins the binary decode against an independent server rendering, so a decoder that
   * drops precision shows up. It holds for scalar types with a canonical string form (numeric,
   * timestamptz); for {@link org.postgresql.util.PGobject} types {@code getString} is the driver's
   * own {@code getValue()} form, which is deliberately not the server's, so those are pinned as known
   * divergences via {@link #decodeKnownDivergences()} rather than asserted equal here.
   */
  /** Runs a testkit catalogue's literals through decode-truth. */
  private static List<DynamicTest> decodeEdges(String prefix, int oid, String typeName,
      List<EdgeCase> cases) {
    List<DynamicTest> t = new ArrayList<>();
    for (EdgeCase e : cases) {
      t.add(decodeTruth(prefix + "/" + e.name(), oid, typeName, e.literal()));
    }
    return t;
  }

  @TestFactory
  List<DynamicTest> decode() {
    // Read side, driven by the same testkit catalogues: getString of the binary-received value must
    // equal the server's ::text. numeric scale must survive; timestamptz renders in the session zone;
    // timetz keeps its wire offset (fixed in TimestampUtils.toStringOffsetTimeBin); interval follows
    // the session IntervalStyle -- both timetz and interval were promoted here from known divergences.
    // (box is excluded: its getString is still the driver's own PGobject form -- see
    // decodeKnownDivergences.)
    // "end_of_day" timetz is excluded here and handled as a pinned finding below: the driver's binary
    // decode of timetz 24:00:00 throws. (The numeric "tiny" scientific-notation getString was a bug,
    // now fixed in NumericCodec.decodeAsString, so it decodes truthfully here.)
    List<DynamicTest> t = new ArrayList<>();
    t.addAll(decodeEdges("numeric", Oid.NUMERIC, "numeric", NumericEdgeCases.ALL));
    t.addAll(decodeEdges("timestamptz", Oid.TIMESTAMPTZ, "timestamptz", TimestampTzEdgeCases.ALL));
    t.addAll(decodeEdges("timetz", Oid.TIMETZ, "timetz", without(TimeTzEdgeCases.ALL, "end_of_day")));
    t.addAll(decodeEdges("interval", Oid.INTERVAL, "interval", IntervalEdgeCases.ALL));
    return t;
  }

  /**
   * A boundary the testkit catalogues surfaced, pinned to its current behaviour so a change is caught:
   * the server accepts timetz {@code 24:00:00} (its documented upper bound) but the driver's binary
   * decode rejects {@code 86400000000} microseconds, because {@code OffsetTime} cannot represent
   * 24:00:00. Candidate bug, pinned until fixed.
   */
  @TestFactory
  List<DynamicTest> decodeCatalogueFindings() {
    List<DynamicTest> t = new ArrayList<>();
    t.add(DynamicTest.dynamicTest("timetz/end_of_day/binary-decode-throws", () ->
        assertThrows(SQLException.class, () ->
            ServerTruthOracle.assertDecodeTruth(binaryCon, Oid.TIMETZ, "timetz", "24:00:00+00"))));
    return t;
  }

  /**
   * Pinned divergences between the driver's {@code getString} and the server's {@code ::text}, so a
   * silent change on either side is caught. box is a deliberate rendering difference: the driver's own
   * {@link org.postgresql.util.PGobject} canonical form, not the server's.
   *
   * <ul>
   *   <li><b>box</b> — the driver normalises corners to (high, low) exactly like the server, but
   *       renders each coordinate as a Java double ({@code 2.0}) where the server prints {@code 2}.
   *       {@code PGbox.getValue()} is the driver's own canonical form.</li>
   * </ul>
   *
   * <p>timetz and interval used to belong here — timetz shifted the binary value to the client zone,
   * interval used {@code PGInterval.getValue()}'s verbose form — but both now match the server (timetz
   * fixed in {@code TimestampUtils.toStringOffsetTimeBin}, interval via the IntervalStyle-aware
   * rendering), so their cases live in {@link #decode()}.</p>
   */
  @TestFactory
  List<DynamicTest> decodeKnownDivergences() {
    List<DynamicTest> t = new ArrayList<>();
    t.add(DynamicTest.dynamicTest("box/renders-doubles", () ->
        ServerTruthOracle.assertDecodeDivergence(binaryCon, Oid.BOX, "box",
            "(1,1),(2,2)", "(2.0,2.0),(1.0,1.0)", "(2,2),(1,1)")));
    t.add(DynamicTest.dynamicTest("box/mixed-corners", () ->
        ServerTruthOracle.assertDecodeDivergence(binaryCon, Oid.BOX, "box",
            "(1,4),(3,2)", "(3.0,4.0),(1.0,2.0)", "(3,4),(1,2)")));
    return t;
  }

  // --- Generator-driven server-truth -----------------------------------------------------------
  //
  // The server-truth oracle needs a live server, which the offline JQF fuzzers deliberately lack, so
  // the fuzzing lives here: each @Test draws FUZZ_ITERATIONS cases from a seeded RNG and runs them
  // through the same oracle as the hand-picked cases above. The literal is built independently from
  // the value's components (a local formatter, never the driver's text encoder), so a bug shared by
  // the binary and text encoders cannot hide. A fixed seed keeps failures reproducible; override
  // -DserverTruth.fuzz.seed / -DserverTruth.fuzz.iterations to explore or shrink.

  private static final long FUZZ_SEED = Long.getLong("serverTruth.fuzz.seed", 0x5E2_7217L);
  private static final int FUZZ_ITERATIONS =
      Integer.getInteger("serverTruth.fuzz.iterations", 128);

  @Test
  void numericEncodeFuzz() throws SQLException {
    Random rnd = new Random(FUZZ_SEED);
    for (int i = 0; i < FUZZ_ITERATIONS; i++) {
      BigDecimal value = randomNumeric(rnd);
      // toPlainString is the independent literal; both sides pass through the server, so its scale
      // (dscale) must survive the binary encoding exactly.
      ServerTruthOracle.assertEncodeTruth(con, Oid.NUMERIC, "numeric", value, value.toPlainString());
    }
  }

  @Test
  void timetzFuzz() throws SQLException {
    Random rnd = new Random(FUZZ_SEED + 1);
    for (int i = 0; i < FUZZ_ITERATIONS; i++) {
      OffsetTime value = randomOffsetTime(rnd);
      String literal = formatOffsetTime(value);
      ServerTruthOracle.assertEncodeTruth(con, Oid.TIMETZ, "timetz", value, literal);
      // Also the decode side: getString of the binary-received value must keep the wire offset --
      // this is the direct live guard for the fixed toStringOffsetTimeBin bug, over random offsets.
      ServerTruthOracle.assertDecodeTruth(binaryCon, Oid.TIMETZ, "timetz", literal);
    }
  }

  @Test
  void timestamptzEncodeFuzz() throws SQLException {
    Random rnd = new Random(FUZZ_SEED + 2);
    for (int i = 0; i < FUZZ_ITERATIONS; i++) {
      OffsetDateTime value = randomOffsetDateTime(rnd);
      ServerTruthOracle.assertEncodeTruth(con, Oid.TIMESTAMPTZ, "timestamptz", value,
          formatOffsetDateTime(value));
    }
  }

  @Test
  void boxEncodeFuzz() throws SQLException {
    Random rnd = new Random(FUZZ_SEED + 3);
    for (int i = 0; i < FUZZ_ITERATIONS; i++) {
      double x1 = randomCoord(rnd);
      double y1 = randomCoord(rnd);
      double x2 = randomCoord(rnd);
      double y2 = randomCoord(rnd);
      // Corners in arbitrary order; the server normalises both the literal and the binary value to
      // (high, low), so a correct encoding of the four coordinates matches regardless of order.
      String literal = "(" + coord(x1) + "," + coord(y1) + "),(" + coord(x2) + "," + coord(y2) + ")";
      ServerTruthOracle.assertEncodeTruth(con, Oid.BOX, "box", new PGbox(x1, y1, x2, y2), literal);
    }
  }

  @Test
  void intervalEncodeFuzz() throws SQLException {
    Random rnd = new Random(FUZZ_SEED + 4);
    for (int i = 0; i < FUZZ_ITERATIONS; i++) {
      int months = rnd.nextInt(400) - 200;
      int days = rnd.nextInt(400) - 200;
      int hours = rnd.nextInt(96) - 48;
      int minutes = rnd.nextInt(240) - 120;
      int seconds = rnd.nextInt(240) - 120;
      PGInterval value = new PGInterval(0, months, days, hours, minutes, seconds);
      // postgres accepts signed per-component input, so the independent literal mirrors the fields.
      String literal = months + " months " + days + " days " + hours + " hours "
          + minutes + " minutes " + seconds + " seconds";
      ServerTruthOracle.assertEncodeTruth(con, Oid.INTERVAL, "interval", value, literal);
    }
  }

  private static BigDecimal randomNumeric(Random rnd) {
    BigInteger unscaled = new BigInteger(1 + rnd.nextInt(140), rnd);
    if (rnd.nextBoolean()) {
      unscaled = unscaled.negate();
    }
    return new BigDecimal(unscaled, rnd.nextInt(41));
  }

  private static OffsetTime randomOffsetTime(Random rnd) {
    return OffsetTime.of(
        LocalTime.ofSecondOfDay(rnd.nextInt(86_400)).withNano(rnd.nextInt(1_000_000) * 1000),
        randomOffset(rnd));
  }

  private static OffsetDateTime randomOffsetDateTime(Random rnd) {
    return OffsetDateTime.of(
        LocalDate.of(1900 + rnd.nextInt(300), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28)),
        LocalTime.ofSecondOfDay(rnd.nextInt(86_400)).withNano(rnd.nextInt(1_000_000) * 1000),
        randomOffset(rnd));
  }

  /** A whole-minute zone offset in the timetz/timestamptz range PostgreSQL accepts. */
  private static ZoneOffset randomOffset(Random rnd) {
    return ZoneOffset.ofTotalSeconds((rnd.nextInt(14 * 60 * 2 + 1) - 14 * 60) * 60);
  }

  private static double randomCoord(Random rnd) {
    return (rnd.nextDouble() * 2 - 1) * Math.pow(10, rnd.nextInt(12) - 4);
  }

  /** A double literal PostgreSQL's {@code float8_in} parses back to the exact same value. */
  private static String coord(double d) {
    return Double.toString(d);
  }

  private static String formatOffsetTime(OffsetTime v) {
    return String.format(Locale.ROOT, "%02d:%02d:%02d.%06d%s",
        v.getHour(), v.getMinute(), v.getSecond(), v.getNano() / 1000, formatOffset(v.getOffset()));
  }

  private static String formatOffsetDateTime(OffsetDateTime v) {
    return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d:%02d.%06d%s",
        v.getYear(), v.getMonthValue(), v.getDayOfMonth(), v.getHour(), v.getMinute(), v.getSecond(),
        v.getNano() / 1000, formatOffset(v.getOffset()));
  }

  private static String formatOffset(ZoneOffset offset) {
    int total = offset.getTotalSeconds();
    String sign = total < 0 ? "-" : "+";
    int abs = Math.abs(total);
    return String.format(Locale.ROOT, "%s%02d:%02d", sign, abs / 3600, (abs % 3600) / 60);
  }
}
