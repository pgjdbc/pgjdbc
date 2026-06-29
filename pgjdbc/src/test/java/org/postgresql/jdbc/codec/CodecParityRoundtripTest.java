/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.postgresql.jdbc.codec.ParityHarness.NO_PARAMS;
import static org.postgresql.jdbc.codec.ParityHarness.assertParity;
import static org.postgresql.jdbc.codec.ParityHarness.assertParityEquals;

import org.postgresql.PGConnection;
import org.postgresql.core.Oid;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGRange;
import org.postgresql.util.PGmultirange;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Text/binary parity regression net for scalar, composite, and domain decode.
 *
 * <p>Each case sends a value through {@link ParityHarness}'s text and binary connections and asserts
 * {@code binary-decode == text-decode == original}. The net is a safety harness for the Phase 2 SPI
 * refactor: it pins the current cross-format behaviour, not a new specification.</p>
 *
 * <p>It deliberately omits anonymous records ({@code SELECT ROW(1, 'a')}) because they diverge by
 * design, not by bug: text {@code record_out} carries no field OIDs, so the text path yields untyped
 * strings while binary recovers typed fields. The binary path is covered by
 * {@code NestedRecordBinaryTransferTest}.</p>
 *
 * <p>Range types are included: now that {@code RangeCodec} resolves its subtype from
 * {@code pg_range.rngsubtype} (C2), both paths decode the bounds into typed values, so
 * {@code binary == text} holds.</p>
 */
class CodecParityRoundtripTest {

  private static Connection text;
  private static Connection binary;
  private static Connection setup;
  private static boolean binaryReady;
  private static boolean haveJsonb;
  private static boolean haveRanges;
  private static boolean haveMultiranges;
  private static long int4rangeOid;
  private static long int4multirangeOid;

  @BeforeAll
  static void setUpClass() throws Exception {
    setup = TestUtil.openDB();
    haveJsonb = TestUtil.haveMinimumServerVersion(setup, ServerVersion.v9_4);
    haveRanges = TestUtil.haveMinimumServerVersion(setup, ServerVersion.v9_2);
    haveMultiranges = TestUtil.haveMinimumServerVersion(setup, ServerVersion.v14);

    TestUtil.createCompositeType(setup, "codec_parity_addr", "street text, city text, zip int");
    TestUtil.createCompositeType(setup, "codec_parity_person",
        "name text, home_address codec_parity_addr, age int");
    TestUtil.createDomain(setup, "codec_parity_posint", "integer CHECK (value > 0)");
    TestUtil.createDomain(setup, "codec_parity_intarr", "int[]");
    TestUtil.createCompositeType(setup, "codec_parity_holder", "arr codec_parity_intarr, tag text");

    // Force binary receive for every OID this class reads. The driver's capability-driven binary
    // receive only kicks in for a user type once a result set has warmed its PgType memo, so a single
    // execute of a named composite would silently fall back to text unless it is opted in explicitly
    // via binaryTransferEnable. Built-ins are listed too so useBinaryForReceive() reflects the whole
    // matrix and the sanity check below is meaningful. Domains report their base type OID for a
    // result column (codec_parity_posint -> int4), and the embedded domain-over-array travels inside
    // the holder record resolved from the wire, so neither domain needs its own entry.
    long[] addr = ParityHarness.oidAndArray(setup, "codec_parity_addr");
    long[] person = ParityHarness.oidAndArray(setup, "codec_parity_person");
    long[] holder = ParityHarness.oidAndArray(setup, "codec_parity_holder");
    StringBuilder enable = new StringBuilder(ParityHarness.oids(
        Oid.INT2, Oid.INT4, Oid.INT8, Oid.FLOAT4, Oid.FLOAT8, Oid.NUMERIC, Oid.BOOL, Oid.BYTEA,
        Oid.TEXT, Oid.UUID, Oid.DATE, Oid.TIME, Oid.TIMETZ, Oid.TIMESTAMP, Oid.TIMESTAMPTZ,
        addr[0], addr[1], person[0], holder[0]));
    if (haveJsonb) {
      enable.append(',').append(Oid.JSON).append(',').append(Oid.JSONB);
    }
    if (haveRanges) {
      // Range OIDs are not Oid constants; resolve them by name. The driver defers a range
      // to text until a result set warms its PgType, so force binary receive explicitly.
      int4rangeOid = ParityHarness.oidAndArray(setup, "int4range")[0];
      long int8rangeOid = ParityHarness.oidAndArray(setup, "int8range")[0];
      long numrangeOid = ParityHarness.oidAndArray(setup, "numrange")[0];
      long tsrangeOid = ParityHarness.oidAndArray(setup, "tsrange")[0];
      enable.append(',').append(ParityHarness.oids(int4rangeOid, int8rangeOid, numrangeOid,
          tsrangeOid));
    }
    if (haveMultiranges) {
      // Multirange OIDs are not Oid constants either; resolve them by name and force binary receive.
      int4multirangeOid = ParityHarness.oidAndArray(setup, "int4multirange")[0];
      long int8multirangeOid = ParityHarness.oidAndArray(setup, "int8multirange")[0];
      long nummultirangeOid = ParityHarness.oidAndArray(setup, "nummultirange")[0];
      enable.append(',').append(ParityHarness.oids(int4multirangeOid, int8multirangeOid,
          nummultirangeOid));
    }

    text = ParityHarness.openText();
    binary = ParityHarness.openBinary(enable.toString());

    binaryReady = binary.unwrap(PGConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE;
    if (binaryReady) {
      // Guard against a false green: if these are text, the "binary" column never exercised binary.
      assertTrue(ParityHarness.binaryActiveFor(binary, Oid.INT4),
          "int4 must be received in binary on the binary connection");
      assertTrue(ParityHarness.binaryActiveFor(binary, Oid.NUMERIC),
          "numeric must be received in binary on the binary connection");
      assertTrue(ParityHarness.binaryActiveFor(binary, (int) addr[0]),
          "named composite must be received in binary on the binary connection");
      if (haveRanges) {
        assertTrue(ParityHarness.binaryActiveFor(binary, (int) int4rangeOid),
            "int4range must be received in binary on the binary connection");
      }
      if (haveMultiranges) {
        assertTrue(ParityHarness.binaryActiveFor(binary, (int) int4multirangeOid),
            "int4multirange must be received in binary on the binary connection");
      }
    }
  }

  @AfterAll
  static void tearDownClass() throws Exception {
    TestUtil.closeDB(text);
    TestUtil.closeDB(binary);
    if (setup != null) {
      TestUtil.dropType(setup, "codec_parity_holder");
      TestUtil.dropDomain(setup, "codec_parity_intarr");
      TestUtil.dropDomain(setup, "codec_parity_posint");
      TestUtil.dropType(setup, "codec_parity_person");
      TestUtil.dropType(setup, "codec_parity_addr");
      TestUtil.closeDB(setup);
    }
  }

  private static DynamicTest withExpected(String name, String sql, ParityHarness.Binder binder,
      Object expected) {
    return DynamicTest.dynamicTest(name, () -> assertParityEquals(text, binary, sql, binder, expected));
  }

  private static DynamicTest parityOnly(String name, String sql) {
    return DynamicTest.dynamicTest(name, () -> assertParity(text, binary, sql, NO_PARAMS));
  }

  @TestFactory
  List<DynamicTest> scalars() {
    List<DynamicTest> t = new ArrayList<>();

    // int2 / int4 / int8 — getObject maps smallint and integer to Integer, bigint to Long.
    t.add(withExpected("int2/zero", "SELECT ?::int2", ps -> ps.setShort(1, (short) 0), 0));
    t.add(withExpected("int2/min", "SELECT ?::int2", ps -> ps.setShort(1, Short.MIN_VALUE),
        (int) Short.MIN_VALUE));
    t.add(withExpected("int2/max", "SELECT ?::int2", ps -> ps.setShort(1, Short.MAX_VALUE),
        (int) Short.MAX_VALUE));
    t.add(withExpected("int4/answer", "SELECT ?::int4", ps -> ps.setInt(1, 42), 42));
    t.add(withExpected("int4/min", "SELECT ?::int4", ps -> ps.setInt(1, Integer.MIN_VALUE),
        Integer.MIN_VALUE));
    t.add(withExpected("int4/max", "SELECT ?::int4", ps -> ps.setInt(1, Integer.MAX_VALUE),
        Integer.MAX_VALUE));
    t.add(withExpected("int8/neg", "SELECT ?::int8", ps -> ps.setLong(1, -1L), -1L));
    t.add(withExpected("int8/min", "SELECT ?::int8", ps -> ps.setLong(1, Long.MIN_VALUE),
        Long.MIN_VALUE));
    t.add(withExpected("int8/max", "SELECT ?::int8", ps -> ps.setLong(1, Long.MAX_VALUE),
        Long.MAX_VALUE));

    // float4 / float8, including the IEEE specials PostgreSQL round-trips.
    t.add(withExpected("float4/value", "SELECT ?::float4", ps -> ps.setFloat(1, 1.5f), 1.5f));
    t.add(withExpected("float4/neg", "SELECT ?::float4", ps -> ps.setFloat(1, -2.25f), -2.25f));
    t.add(withExpected("float4/nan", "SELECT ?::float4", ps -> ps.setFloat(1, Float.NaN), Float.NaN));
    t.add(withExpected("float4/+inf", "SELECT ?::float4",
        ps -> ps.setFloat(1, Float.POSITIVE_INFINITY), Float.POSITIVE_INFINITY));
    t.add(withExpected("float4/-inf", "SELECT ?::float4",
        ps -> ps.setFloat(1, Float.NEGATIVE_INFINITY), Float.NEGATIVE_INFINITY));
    t.add(withExpected("float8/pi", "SELECT ?::float8", ps -> ps.setDouble(1, Math.PI), Math.PI));
    t.add(withExpected("float8/nan", "SELECT ?::float8", ps -> ps.setDouble(1, Double.NaN),
        Double.NaN));
    t.add(withExpected("float8/+inf", "SELECT ?::float8",
        ps -> ps.setDouble(1, Double.POSITIVE_INFINITY), Double.POSITIVE_INFINITY));
    t.add(withExpected("float8/-inf", "SELECT ?::float8",
        ps -> ps.setDouble(1, Double.NEGATIVE_INFINITY), Double.NEGATIVE_INFINITY));

    // numeric — getObject returns BigDecimal; the scale the server reports is part of the value.
    t.add(withExpected("numeric/int", "SELECT ?::numeric", ps -> ps.setBigDecimal(1,
        new BigDecimal("42")), new BigDecimal("42")));
    t.add(withExpected("numeric/neg", "SELECT ?::numeric", ps -> ps.setBigDecimal(1,
        new BigDecimal("-1.5")), new BigDecimal("-1.5")));
    t.add(withExpected("numeric/scale", "SELECT ?::numeric", ps -> ps.setBigDecimal(1,
        new BigDecimal("123.45")), new BigDecimal("123.45")));
    t.add(withExpected("numeric/big", "SELECT ?::numeric", ps -> ps.setBigDecimal(1,
        new BigDecimal("123456789012345678901234567890.12345")),
        new BigDecimal("123456789012345678901234567890.12345")));

    t.add(withExpected("bool/true", "SELECT ?::bool", ps -> ps.setBoolean(1, true), true));
    t.add(withExpected("bool/false", "SELECT ?::bool", ps -> ps.setBoolean(1, false), false));

    // text — empty, the array/record metacharacters, and multibyte.
    t.add(withExpected("text/empty", "SELECT ?::text", ps -> ps.setString(1, ""), ""));
    t.add(withExpected("text/ascii", "SELECT ?::text", ps -> ps.setString(1, "hello"), "hello"));
    t.add(withExpected("text/meta", "SELECT ?::text",
        ps -> ps.setString(1, "a,\"b\"\\{c}"), "a,\"b\"\\{c}"));
    t.add(withExpected("text/unicode", "SELECT ?::text",
        ps -> ps.setString(1, "юникод 日本語 🐘"), "юникод 日本語 🐘"));

    // bytea — getObject returns byte[]; cover an embedded NUL and the high bit.
    t.add(withExpected("bytea/empty", "SELECT ?::bytea", ps -> ps.setBytes(1, new byte[0]),
        new byte[0]));
    t.add(withExpected("bytea/bytes", "SELECT ?::bytea",
        ps -> ps.setBytes(1, new byte[]{0, 1, (byte) 0xFE, (byte) 0xFF}),
        new byte[]{0, 1, (byte) 0xFE, (byte) 0xFF}));

    UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    t.add(withExpected("uuid", "SELECT ?::uuid", ps -> ps.setObject(1, uuid), uuid));

    // Temporal getObject mapping depends on the connection's codec context (java.time vs java.sql),
    // so these are parity-only — both connections share the context, the decode formats differ.
    t.add(parityOnly("date", "SELECT DATE '2023-09-05'"));
    t.add(parityOnly("time", "SELECT TIME '16:21:50.123456'"));
    t.add(parityOnly("timetz", "SELECT TIMETZ '16:21:50.123456+03:30'"));
    t.add(parityOnly("timestamp", "SELECT TIMESTAMP '2023-09-05 16:21:50.123456'"));
    t.add(parityOnly("timestamptz", "SELECT TIMESTAMPTZ '2023-09-05 16:21:50.123456+02'"));

    if (haveJsonb) {
      t.add(parityOnly("json", "SELECT '{\"k\": [1, 2, 3], \"s\": \"v\"}'::json"));
      t.add(parityOnly("jsonb", "SELECT '{\"k\": [1, 2, 3], \"s\": \"v\"}'::jsonb"));
    }

    return t;
  }

  @TestFactory
  List<DynamicTest> composites() {
    List<DynamicTest> t = new ArrayList<>();

    t.add(withExpected("composite/flat",
        "SELECT ROW('Main St', 'Springfield', 62701)::codec_parity_addr", NO_PARAMS,
        new Object[]{"Main St", "Springfield", 62701}));
    t.add(withExpected("composite/null-fields",
        "SELECT ROW(NULL, 'CityOnly', NULL)::codec_parity_addr", NO_PARAMS,
        new Object[]{null, "CityOnly", null}));
    t.add(withExpected("composite/nested",
        "SELECT ROW('John Doe', ROW('Elm St', 'Boston', 2101)::codec_parity_addr, 30)"
            + "::codec_parity_person", NO_PARAMS,
        new Object[]{"John Doe", new Object[]{"Elm St", "Boston", 2101}, 30}));
    t.add(withExpected("composite/array-of",
        "SELECT ARRAY[ROW('Oak Ave', 'Shelbyville', 62702)::codec_parity_addr,"
            + " ROW('Elm St', 'Capitol', 62703)::codec_parity_addr]", NO_PARAMS,
        new Object[]{
            new Object[]{"Oak Ave", "Shelbyville", 62702},
            new Object[]{"Elm St", "Capitol", 62703}}));

    return t;
  }

  @TestFactory
  List<DynamicTest> domains() {
    List<DynamicTest> t = new ArrayList<>();

    // Domain over a scalar: the server reports the base int4 OID, so this also covers int4 send.
    t.add(withExpected("domain/positive-int", "SELECT ?::codec_parity_posint",
        ps -> ps.setInt(1, 7), 7));

    // Domain over an array, embedded as a composite field (the C1 repro): the holder record carries
    // the domain's OID, which must resolve through DomainCodec to the base int[] codec in both text
    // and binary.
    t.add(withExpected("domain/over-array-in-composite",
        "SELECT ROW('{1,2,3}'::codec_parity_intarr, 'x')::codec_parity_holder", NO_PARAMS,
        new Object[]{new Integer[]{1, 2, 3}, "x"}));

    return t;
  }

  @TestFactory
  List<DynamicTest> ranges() {
    List<DynamicTest> t = new ArrayList<>();
    if (!haveRanges) {
      return t;
    }

    // C2 regression: binary range decode used to throw because RangeCodec read the subtype from
    // typelem (0) instead of pg_range.rngsubtype. With the subtype resolved, the bounds decode into
    // typed values (Integer/Long/BigDecimal) identically over text and binary.
    t.add(withExpected("int4range/closed-open", "SELECT '[1,10)'::int4range", NO_PARAMS,
        new PGRange<>(1, 10, true, false)));
    t.add(withExpected("int4range/empty", "SELECT 'empty'::int4range", NO_PARAMS,
        PGRange.empty()));
    t.add(withExpected("int4range/infinite-upper", "SELECT '[5,)'::int4range", NO_PARAMS,
        new PGRange<>(5, null, true, false)));
    t.add(withExpected("int8range/closed-open", "SELECT '[1,10)'::int8range", NO_PARAMS,
        new PGRange<>(1L, 10L, true, false)));
    t.add(withExpected("numrange/continuous", "SELECT '[1.5,2.5)'::numrange", NO_PARAMS,
        new PGRange<>(new BigDecimal("1.5"), new BigDecimal("2.5"), true, false)));

    // tsrange bounds are timestamps, whose getObject mapping depends on the connection's codec
    // context (java.time vs java.sql), so this is parity-only, like the temporal scalars above.
    t.add(parityOnly("tsrange", "SELECT '[2020-01-01 00:00:00,2020-02-01 00:00:00)'::tsrange"));

    return t;
  }

  @TestFactory
  List<DynamicTest> multiranges() {
    List<DynamicTest> t = new ArrayList<>();
    if (!haveMultiranges) {
      return t;
    }

    // MultirangeCodec composes on RangeCodec, so each range's bounds decode into typed values
    // (Integer/Long/BigDecimal) identically over text and binary, the same as the range cases above.
    t.add(withExpected("int4multirange/two", "SELECT '{[1,5),[10,20)}'::int4multirange", NO_PARAMS,
        new PGmultirange<>(new PGRange<>(1, 5, true, false), new PGRange<>(10, 20, true, false))));
    t.add(withExpected("int4multirange/single", "SELECT '{[1,5)}'::int4multirange", NO_PARAMS,
        new PGmultirange<>(new PGRange<>(1, 5, true, false))));
    t.add(withExpected("int4multirange/empty", "SELECT '{}'::int4multirange", NO_PARAMS,
        new PGmultirange<>()));
    t.add(withExpected("int8multirange/single", "SELECT '{[1,10)}'::int8multirange", NO_PARAMS,
        new PGmultirange<>(new PGRange<>(1L, 10L, true, false))));
    t.add(withExpected("nummultirange/single", "SELECT '{[1.5,2.5)}'::nummultirange", NO_PARAMS,
        new PGmultirange<>(new PGRange<>(new BigDecimal("1.5"), new BigDecimal("2.5"), true, false))));

    return t;
  }
}
