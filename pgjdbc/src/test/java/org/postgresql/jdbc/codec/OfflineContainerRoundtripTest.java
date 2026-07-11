/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.core.Oid;
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGline;
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGmoney;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Struct;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Round-trips container values — composites, SQLData objects, arrays and hstore — through the public
 * offline (connectionless) codec surface. These exercise the slice-3b work that lifted the container
 * codecs and SQLData adapters off a live {@link java.sql.Connection}: built-in element and field
 * types resolve through the driver's catalog, the encoding readers run from a charset-derived
 * encoding, and a genuinely connection-bound result (a {@code java.sql.Array} inside a struct) still
 * reports a clear error.
 */
class OfflineContainerRoundtripTest {

  private static final int POINT_OID = 90_001;
  private static final int HAS_ARRAY_OID = 90_002;
  private static final int HSTORE_OID = 90_003;
  private static final int POINT_ARRAY_OID = 90_004;

  private static final PgType TEXT_ARRAY = new PgType(
      new ObjectName("pg_catalog", "_text"), "text[]", Oid.TEXT_ARRAY, 'b', 'A', -1,
      Oid.TEXT, 0, 0);

  private static final PgType INT4_ARRAY = new PgType(
      new ObjectName("pg_catalog", "_int4"), "int4[]", Oid.INT4_ARRAY, 'b', 'A', -1,
      Oid.INT4, 0, 0);

  private static final PgType BYTEA_ARRAY = new PgType(
      new ObjectName("pg_catalog", "_bytea"), "bytea[]", Oid.BYTEA_ARRAY, 'b', 'A', -1,
      Oid.BYTEA, 0, 0);

  private static final PgType NUMERIC_ARRAY = new PgType(
      new ObjectName("pg_catalog", "_numeric"), "numeric[]", Oid.NUMERIC_ARRAY, 'b', 'A', -1,
      Oid.NUMERIC, 0, 0);

  private static PgField field(String name, int oid, int position) {
    return new PgField(name, oid, position, -1);
  }

  private static PgType composite(String simpleName, int oid, PgField... fields) {
    return new PgType(new ObjectName("public", simpleName), "public." + simpleName, oid, 'c', 'C',
        -1, 0, 0, 0, ',', Arrays.asList(fields));
  }

  private static PgType anonymousRecord(PgField... fields) {
    return new PgType(new ObjectName("pg_catalog", "record"), "record", Oid.RECORD, 'c', 'C',
        -1, 0, 0, 0, ',', Arrays.asList(fields));
  }

  private static Point point(int x, int y, String label) {
    Point p = new Point();
    p.x = x;
    p.y = y;
    p.label = label;
    return p;
  }

  @Test
  void compositeStructRoundtripsOffline() throws SQLException {
    PgType type = composite("pt", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    Object[] attributes = {10, 20, "hello, struct"};

    for (Format format : Format.values()) {
      // Built-in field types (int4, text) resolve through the driver catalog with no registration.
      RawValue raw = Codecs.encode(new PgStruct(type, attributes, null), type, ctx, format);
      Struct decoded = Codecs.decode(raw, type, ctx, Struct.class);
      assertNotNull(decoded, "composite " + format);
      assertArrayEquals(attributes, decoded.getAttributes(), "composite " + format);
    }
  }

  @Test
  void compositeStructGetValueRebuiltOffline() throws SQLException {
    // The bug: offline binary (and text -> Struct.class) decode left PgStruct.getValue() null,
    // because the struct reached its codec context only through a connection. With the context
    // carried directly, getValue() and toString() rebuild the record_out literal from the
    // attributes with no connection.
    PgType type = composite("pt", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    Object[] attributes = {10, 20, "hello"};

    String canonical = null;
    for (Format format : Format.values()) {
      // Struct.class decode never records the raw literal, so getValue() must rebuild it from the
      // attributes through the carried offline context.
      RawValue raw = Codecs.encode(new PgStruct(type, attributes, null), type, ctx, format);
      PgStruct decoded = (PgStruct) Codecs.decode(raw, type, ctx, Struct.class);
      assertNotNull(decoded, "struct " + format);

      String literal = decoded.getValue();
      assertNotNull(literal, "getValue offline " + format);
      assertEquals(literal, decoded.toString(), "toString offline " + format);

      // Binary and text decode must rebuild the identical canonical literal.
      if (canonical == null) {
        canonical = literal;
      } else {
        assertEquals(canonical, literal, "canonical literal " + format);
      }
    }
    // The text codec quotes the string field unconditionally; the literal round-trips either way.
    assertEquals("(10,20,\"hello\")", canonical, "literal form");
  }

  @Test
  void nestedAnonymousRecordRoundtripsOffline() throws SQLException {
    // An anonymous record (OID 2249) whose second field is itself an anonymous record. The wire
    // reports OID 2249 for the nested field, which resolves to the fieldless record pseudo-type, so
    // the binary encoder must fall back to the fields the nested struct carries. Before the fix a
    // nested record could be decoded but not re-encoded, forcing callers onto named composites.
    PgType inner = anonymousRecord(field("g1", Oid.INT4, 1), field("g2", Oid.INT4, 2));
    PgType outer = anonymousRecord(field("f1", Oid.INT4, 1), field("f2", Oid.RECORD, 2));
    // One record descriptor lets the context route OID 2249 to the composite codec; the per-node
    // fields come from each struct's own carried type on encode and from the wire on decode.
    CodecContext ctx = PgCodecContext.offlineBuilder().type(outer).build();

    PgStruct nested = new PgStruct(inner, new Object[]{2, 3}, null);
    PgStruct value = new PgStruct(outer, new Object[]{1, nested}, null);

    RawValue raw = Codecs.encode(value, outer, ctx, Format.BINARY);
    PgStruct decoded = (PgStruct) Codecs.decode(raw, outer, ctx, Struct.class);
    assertNotNull(decoded, "nested anonymous record");
    Object[] attributes = decoded.getAttributes();
    assertEquals(1, attributes[0], "outer scalar field");
    Struct decodedNested = assertInstanceOf(Struct.class, attributes[1], "nested record field");
    assertArrayEquals(new Object[]{2, 3}, decodedNested.getAttributes(), "nested record attributes");

    // getValue() rebuilds the record_out literal recursively; record_out quotes the nested record
    // because it contains commas and parentheses.
    assertEquals("(1,\"(2,3)\")", decoded.getValue(), "rebuilt nested record literal");
  }

  @Test
  void deeplyNestedAnonymousRecordRoundtripsOffline() throws SQLException {
    // record(record(record(int4))): each level recurses through the child struct's own fields, so
    // the encoder compounds the nesting exactly as record_send would on the server.
    PgType level3 = anonymousRecord(field("h1", Oid.INT4, 1));
    PgType level2 = anonymousRecord(field("g1", Oid.RECORD, 1));
    PgType level1 = anonymousRecord(field("f1", Oid.RECORD, 1));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(level1).build();

    PgStruct value = new PgStruct(level1, new Object[]{
        new PgStruct(level2, new Object[]{
            new PgStruct(level3, new Object[]{1}, null)}, null)}, null);

    RawValue raw = Codecs.encode(value, level1, ctx, Format.BINARY);
    PgStruct decoded = (PgStruct) Codecs.decode(raw, level1, ctx, Struct.class);
    Struct l2 = assertInstanceOf(Struct.class, decoded.getAttributes()[0], "level 2 record");
    Struct l3 = assertInstanceOf(Struct.class, l2.getAttributes()[0], "level 3 record");
    assertArrayEquals(new Object[]{1}, l3.getAttributes(), "leaf record attributes");

    // record_out doubles the embedded quotes at each level, so the escaping compounds with depth.
    assertEquals("(\"(\"\"(1)\"\")\")", decoded.getValue(), "rebuilt deeply nested record literal");
  }

  @Test
  void nestedAnonymousRecordResolvesFromBuiltinCatalogOffline() throws SQLException {
    // Regression for a coverage-guided fuzzer finding (NestedCodecFuzzTest.structRoundTrip): a named
    // composite with a nested anonymous RECORD field, encoded offline without registering the record
    // pseudo-type. The nested field's OID (2249) is resolved by OID during encode, so the record
    // pseudo-type must resolve to CompositeCodec from the built-in catalog alone -- otherwise it fell
    // through to FallbackCodec and failed with "Cannot convert PgStruct to record". The fix is the
    // built-in record/record[] entries in BaseTypes; the context registers only the named outer type.
    PgType inner = anonymousRecord(field("g1", Oid.INT4, 1), field("g2", Oid.INT4, 2));
    PgType outer = composite("outer_rec", POINT_OID,
        field("f1", Oid.INT4, 1), field("f2", Oid.RECORD, 2));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(outer).build();

    PgStruct value = new PgStruct(outer,
        new Object[]{1, new PgStruct(inner, new Object[]{2, 3}, null)}, null);

    RawValue raw = Codecs.encode(value, outer, ctx, Format.BINARY);
    PgStruct decoded = (PgStruct) Codecs.decode(raw, outer, ctx, Struct.class);
    assertNotNull(decoded, "named record with nested anonymous record");
    Object[] attributes = decoded.getAttributes();
    assertEquals(1, attributes[0], "outer scalar field");
    Struct nested = assertInstanceOf(Struct.class, attributes[1], "nested record field");
    assertArrayEquals(new Object[]{2, 3}, nested.getAttributes(), "nested record attributes");
    assertEquals("(1,\"(2,3)\")", decoded.getValue(), "rebuilt nested record literal");
  }

  private static final PgType NUMERIC = new PgType(
      new ObjectName("pg_catalog", "numeric"), "numeric", Oid.NUMERIC, 'b', 'N', -1, 0, 0, 0);

  private int getIntOffline(String literal, Format format) throws SQLException {
    CodecContext ctx = PgCodecContext.offlineBuilder().type(NUMERIC).build();
    RawValue raw = Codecs.encode(new BigDecimal(literal), NUMERIC, ctx, format);
    Integer decoded = Codecs.decode(raw, NUMERIC, ctx, Integer.class);
    assertNotNull(decoded, () -> "getInt offline " + format + " " + literal);
    return decoded;
  }

  @Test
  void numericGetIntBoundaryFractionRoundsThenRangeChecksOffline() throws SQLException {
    // Offline getObject(Integer.class) on a numeric shares bigDecimalToInt with ResultSet.getInt.
    // The value rounds half-away-from-zero like PostgreSQL's numeric->int4 cast: a fraction just past
    // the boundary that rounds back to it fits, while a fraction that rounds past it overflows.
    for (Format format : Format.values()) {
      assertEquals(Integer.MAX_VALUE, getIntOffline("2147483647.4", format),
          () -> "MAX+0.4 " + format);
      assertEquals(Integer.MIN_VALUE, getIntOffline("-2147483648.4", format),
          () -> "MIN-0.4 " + format);
      // In range: 2147483646.5 rounds up to 2147483647, matching the server's cast.
      assertEquals(2147483647, getIntOffline("2147483646.5", format), () -> "MAX-0.5 " + format);
      // x.5 rounds past the boundary and overflows, like the server's cast.
      assertThrows(PSQLException.class, () -> getIntOffline("2147483647.5", format),
          () -> "MAX+0.5 overflow " + format);
      assertThrows(PSQLException.class, () -> getIntOffline("-2147483648.5", format),
          () -> "MIN-0.5 overflow " + format);
    }
  }

  private static final PgType FLOAT4 = new PgType(
      new ObjectName("pg_catalog", "float4"), "float4", Oid.FLOAT4, 'b', 'N', -1, 0, 0, 0);

  private static final PgType FLOAT8 = new PgType(
      new ObjectName("pg_catalog", "float8"), "float8", Oid.FLOAT8, 'b', 'N', -1, 0, 0, 0);

  // Runs the primitive getInt path (ResultSet.getInt -> decodeAsInt) offline for both wire formats.
  private static int float4AsInt(float value, Format format) throws SQLException {
    CodecContext ctx = offlineCtx(FLOAT4);
    if (format == Format.BINARY) {
      byte[] b = Float4Codec.INSTANCE.encodeBinary(value, FLOAT4, ctx);
      return Float4Codec.INSTANCE.decodeAsInt(b, 0, b.length, FLOAT4, ctx);
    }
    return Float4Codec.INSTANCE.decodeAsInt(
        Float4Codec.INSTANCE.encodeText(value, FLOAT4, ctx), FLOAT4, ctx);
  }

  private static long float4AsLong(float value, Format format) throws SQLException {
    CodecContext ctx = offlineCtx(FLOAT4);
    if (format == Format.BINARY) {
      byte[] b = Float4Codec.INSTANCE.encodeBinary(value, FLOAT4, ctx);
      return Float4Codec.INSTANCE.decodeAsLong(b, 0, b.length, FLOAT4, ctx);
    }
    return Float4Codec.INSTANCE.decodeAsLong(
        Float4Codec.INSTANCE.encodeText(value, FLOAT4, ctx), FLOAT4, ctx);
  }

  private static int float8AsInt(double value, Format format) throws SQLException {
    CodecContext ctx = offlineCtx(FLOAT8);
    if (format == Format.BINARY) {
      byte[] b = Float8Codec.INSTANCE.encodeBinary(value, FLOAT8, ctx);
      return Float8Codec.INSTANCE.decodeAsInt(b, 0, b.length, FLOAT8, ctx);
    }
    return Float8Codec.INSTANCE.decodeAsInt(
        Float8Codec.INSTANCE.encodeText(value, FLOAT8, ctx), FLOAT8, ctx);
  }

  private static long float8AsLong(double value, Format format) throws SQLException {
    CodecContext ctx = offlineCtx(FLOAT8);
    if (format == Format.BINARY) {
      byte[] b = Float8Codec.INSTANCE.encodeBinary(value, FLOAT8, ctx);
      return Float8Codec.INSTANCE.decodeAsLong(b, 0, b.length, FLOAT8, ctx);
    }
    return Float8Codec.INSTANCE.decodeAsLong(
        Float8Codec.INSTANCE.encodeText(value, FLOAT8, ctx), FLOAT8, ctx);
  }

  private static void assertOutOfRange(String desc, Executable exec) {
    PSQLException ex = assertThrows(PSQLException.class, exec, desc);
    assertEquals(PSQLState.NUMERIC_VALUE_OUT_OF_RANGE.getState(), ex.getSQLState(),
        () -> desc + " must report SQLState 22003");
  }

  @Test
  void float4NaNAndInfinityIntegerGettersThrowOffline() {
    // 'NaN'::float4 and +/-Infinity have no integer value: the server's real->int cast errors, so the
    // integer getters must throw 22003 rather than silently return 0 (NaN) or a clamped bound.
    for (Format format : Format.values()) {
      for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
        assertOutOfRange("getInt " + value + " " + format, () -> float4AsInt(value, format));
        assertOutOfRange("getLong " + value + " " + format, () -> float4AsLong(value, format));
      }
    }
  }

  @Test
  void float8NaNAndInfinityIntegerGettersThrowOffline() {
    for (Format format : Format.values()) {
      for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
          Double.NEGATIVE_INFINITY}) {
        assertOutOfRange("getInt " + value + " " + format, () -> float8AsInt(value, format));
        assertOutOfRange("getLong " + value + " " + format, () -> float8AsLong(value, format));
      }
    }
  }

  @Test
  void float4IntOverflowThrowsOffline() {
    // (float) 2147483647 rounds up to 2^31, one past Integer.MAX_VALUE. A float-space range check
    // would accept it and clamp to Integer.MAX_VALUE; the fixed check widens to double and throws,
    // matching the server's real->int4 cast.
    for (Format format : Format.values()) {
      assertOutOfRange("getInt 2^31 " + format, () -> float4AsInt(2147483647f, format));
    }
  }

  @Test
  void float8LongOverflowThrowsOffline() {
    // (double) 9223372036854775807 rounds up to 2^63, one past Long.MAX_VALUE. The upper bound is
    // exclusive so this overflows, matching the server's double->int8 cast, instead of clamping to
    // Long.MAX_VALUE.
    for (Format format : Format.values()) {
      assertOutOfRange("getLong 2^63 " + format, () -> float8AsLong(9.223372036854776E18, format));
    }
  }

  @Test
  void floatInRangeIntegerGettersRoundOffline() throws SQLException {
    // A representable, in-range value rounds to the nearest integer, ties to even (PostgreSQL's
    // float8->intN parity), so 42.9 -> 43 and -42.9 -> -43 rather than truncating toward zero.
    for (Format format : Format.values()) {
      assertEquals(43, float4AsInt(42.9f, format), () -> "float4 getInt " + format);
      assertEquals(-43L, float4AsLong(-42.9f, format), () -> "float4 getLong " + format);
      assertEquals(43, float8AsInt(42.9, format), () -> "float8 getInt " + format);
      assertEquals(9_000_000_000L, float8AsLong(9.0E9, format), () -> "float8 getLong " + format);
    }
  }

  @Test
  void floatGetObjectIntegerAndLongRejectNaNOffline() throws SQLException {
    // The getObject(Integer.class)/getObject(Long.class) path shares the same range checks; NaN must
    // not decode to a boxed 0.
    for (Format format : Format.values()) {
      RawValue f4 = Codecs.encode(Float.NaN, FLOAT4, offlineCtx(FLOAT4), format);
      assertOutOfRange("float4 getObject(Integer) NaN " + format,
          () -> Codecs.decode(f4, FLOAT4, offlineCtx(FLOAT4), Integer.class));
      assertOutOfRange("float4 getObject(Long) NaN " + format,
          () -> Codecs.decode(f4, FLOAT4, offlineCtx(FLOAT4), Long.class));
      RawValue f8 = Codecs.encode(Double.NaN, FLOAT8, offlineCtx(FLOAT8), format);
      assertOutOfRange("float8 getObject(Integer) NaN " + format,
          () -> Codecs.decode(f8, FLOAT8, offlineCtx(FLOAT8), Integer.class));
      assertOutOfRange("float8 getObject(Long) NaN " + format,
          () -> Codecs.decode(f8, FLOAT8, offlineCtx(FLOAT8), Long.class));
    }
  }

  private static CodecContext offlineCtx(PgType type) {
    return PgCodecContext.offlineBuilder().type(type).build();
  }

  private static final PgType OID = new PgType(
      new ObjectName("pg_catalog", "oid"), "oid", Oid.OID, 'b', 'N', -1, 0, 0, 0);

  // Runs the primitive getInt path (ResultSet.getInt -> decodeAsInt) offline for both wire formats.
  private static int oidAsInt(long value, Format format) throws SQLException {
    CodecContext ctx = offlineCtx(OID);
    if (format == Format.BINARY) {
      byte[] b = OidCodec.INSTANCE.encodeBinary(value, OID, ctx);
      return OidCodec.INSTANCE.decodeAsInt(b, 0, b.length, OID, ctx);
    }
    return OidCodec.INSTANCE.decodeAsInt(OidCodec.INSTANCE.encodeText(value, OID, ctx), OID, ctx);
  }

  private static long oidAsLong(long value, Format format) throws SQLException {
    CodecContext ctx = offlineCtx(OID);
    if (format == Format.BINARY) {
      byte[] b = OidCodec.INSTANCE.encodeBinary(value, OID, ctx);
      return OidCodec.INSTANCE.decodeAsLong(b, 0, b.length, OID, ctx);
    }
    return OidCodec.INSTANCE.decodeAsLong(OidCodec.INSTANCE.encodeText(value, OID, ctx), OID, ctx);
  }

  @Test
  void oidAboveIntRangeGetIntWrapsButGetLongKeepsUnsignedOffline() throws SQLException {
    // oid is unsigned 32-bit. getInt reinterprets the raw 32-bit value as a signed int (wrapping above
    // Integer.MAX_VALUE), matching the legacy driver, rather than throwing; getLong keeps the full
    // unsigned value. Both wire formats decode the same way.
    for (Format format : Format.values()) {
      // 2147483648 = Integer.MAX_VALUE + 1: getInt wraps to Integer.MIN_VALUE.
      assertEquals(Integer.MIN_VALUE, oidAsInt(2147483648L, format),
          () -> "oid getInt 2147483648 " + format);
      assertEquals(2147483648L, oidAsLong(2147483648L, format),
          () -> "oid getLong 2147483648 " + format);
      // 4294967295 = unsigned max: getInt wraps to -1.
      assertEquals(-1, oidAsInt(4294967295L, format), () -> "oid getInt 4294967295 " + format);
      assertEquals(4294967295L, oidAsLong(4294967295L, format),
          () -> "oid getLong 4294967295 " + format);
    }
  }

  @Test
  void oidAboveIntRangeGetObjectIntegerWrapsButLongKeepsUnsignedOffline() throws SQLException {
    // getObject(Integer.class) shares getInt's wrapping: an oid above Integer.MAX_VALUE boxes to the
    // wrapped bit pattern rather than throwing. getObject(Long.class) keeps the full unsigned value,
    // and getObject(String.class) renders it unsigned (never negative).
    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(4294967295L, OID, offlineCtx(OID), format);
      assertEquals(-1, Codecs.decode(raw, OID, offlineCtx(OID), Integer.class),
          () -> "oid getObject(Integer) 4294967295 " + format);
      assertEquals(4294967295L, Codecs.decode(raw, OID, offlineCtx(OID), Long.class),
          () -> "oid getObject(Long) 4294967295 " + format);
      assertEquals("4294967295", Codecs.decode(raw, OID, offlineCtx(OID), String.class),
          () -> "oid getObject(String) 4294967295 " + format);
    }
  }

  @Test
  void oidWithinIntRangeGetIntRoundtripsOffline() throws SQLException {
    // A value that fits a signed int still decodes normally through getInt.
    for (Format format : Format.values()) {
      assertEquals(0, oidAsInt(0L, format), () -> "oid getInt 0 " + format);
      assertEquals(16, oidAsInt(16L, format), () -> "oid getInt 16 " + format);
      assertEquals(Integer.MAX_VALUE, oidAsInt(2147483647L, format),
          () -> "oid getInt Integer.MAX_VALUE " + format);
    }
  }

  private static final PgType INTERVAL = new PgType(
      new ObjectName("pg_catalog", "interval"), "interval", Oid.INTERVAL, 'b', 'T', -1, 0, 0, 0);

  /**
   * A binary {@code interval} at the far end of its microsecond range ({@code '2562047788:00:54.775807'},
   * i.e. Long.MAX_VALUE microseconds) once leaked an unchecked exception through getString and
   * getObject: the ~2562047788-hour value overflowed PGInterval's int hours and corrupted the seconds,
   * tripping the IllegalArgumentException in PGInterval.setSeconds. getString now renders straight from
   * the wire fields, while getObject refuses with a checked SQLException rather than leak. Both wire
   * formats are checked, though only binary can carry a value this large.
   */
  @Test
  void intervalMaxRangeRendersStringButRefusesPgIntervalOffline() throws SQLException {
    CodecContext ctx = PgCodecContext.offlineBuilder().type(INTERVAL).build();
    byte[] wire = new byte[16];
    ByteConverter.int8(wire, 0, Long.MAX_VALUE); // microseconds; days and months stay zero
    RawValue raw = RawValue.binary(wire);

    // getString renders the server's default (postgres) text form with no connection.
    assertEquals("2562047788:00:54.775807", Codecs.decode(raw, INTERVAL, ctx, String.class),
        "interval getString");

    // getObject builds a PGInterval, whose int hours cannot hold ~2562047788 hours: refuse cleanly.
    PSQLException ex = assertThrows(PSQLException.class,
        () -> Codecs.decode(raw, INTERVAL, ctx, PGInterval.class), "interval getObject");
    assertEquals(PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE.getState(), ex.getSQLState(),
        "interval getObject SQLState");
  }

  // An offline tsrange: typtype 'r' carrying its timestamp subtype directly, so RangeCodec resolves the
  // bound codec without a connection. The OID is synthetic (ranges have no pinned OID in the driver) and
  // routes to RangeCodec by typtype rather than by a name alias.
  private static final int TSRANGE_OID = 90_005;
  private static final PgType TSRANGE =
      new PgType(new ObjectName("pg_catalog", "tsrange"), "pg_catalog.tsrange",
          TSRANGE_OID, 'r', 'R', -1, 0, 0, 0).withRangeSubtype(Oid.TIMESTAMP);

  /**
   * A {@code tsrange} whose timestamp bound has a zero fraction must render in the server's text form —
   * {@code 2020-12-31 00:00:00}, not {@code java.sql.Timestamp}'s {@code 2020-12-31 00:00:00.0}. The
   * bound is a typed {@code Timestamp} on the decoded {@code PGRange}; only its text rendering diverged,
   * so both {@code getObject().toString()} (text transfer) and {@code getString} (binary transfer) once
   * carried the trailing {@code .0}. The differential backward-compat oracle (pgjdbc-compat-test)
   * flagged it on the tsrange-edge axis.
   */
  @Test
  void tsrangeZeroFractionBoundRendersServerTextOffline() throws SQLException {
    CodecContext ctx = PgCodecContext.offlineBuilder().type(TSRANGE).build();

    // getObject in text transfer: the decoded PGRange's toString is the range literal.
    Object closed = RangeCodec.INSTANCE.decodeText(
        "[\"2020-01-01 00:00:00\",\"2020-12-31 00:00:00\"]", TSRANGE, ctx);
    assertEquals("[\"2020-01-01 00:00:00\",\"2020-12-31 00:00:00\"]", closed.toString(),
        "tsrange getObject");

    // getString in binary transfer: round-trip the lower-unbounded case through the wire and read it
    // straight back through decodeAsString, the entry point PgResultSet#getString takes for binary.
    Object lowerUnbounded = RangeCodec.INSTANCE.decodeText("(,\"2020-12-31 00:00:00\"]", TSRANGE, ctx);
    assertNotNull(lowerUnbounded, "tsrange decode");
    byte[] wire = RangeCodec.INSTANCE.encodeBinary(lowerUnbounded, TSRANGE, ctx);
    assertEquals("(,\"2020-12-31 00:00:00\"]",
        RangeCodec.INSTANCE.decodeAsString(wire, 0, wire.length, TSRANGE, ctx), "tsrange getString");

    // The sub-second case is unaffected: PostgreSQL and Timestamp.toString agree on microsecond fractions.
    Object micros = RangeCodec.INSTANCE.decodeText(
        "[\"2020-01-01 00:00:00.000001\",\"2020-01-01 00:00:00.999999\"]", TSRANGE, ctx);
    assertEquals("[\"2020-01-01 00:00:00.000001\",\"2020-01-01 00:00:00.999999\"]", micros.toString(),
        "tsrange micros getObject");
  }

  private static PgType geometric(String name, int oid) {
    return new PgType(new ObjectName("pg_catalog", name), name, oid, 'b', 'G', -1, 0, 0, 0);
  }

  /**
   * Encodes {@code value} to its binary wire form and reads it straight back through {@code
   * decodeAsString} — the exact entry point {@link org.postgresql.jdbc.PgResultSet#getString} takes for
   * a binary column.
   */
  private static String binaryGetString(BinaryCodec codec, PgType type, Object value)
      throws SQLException {
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    byte[] wire = codec.encodeBinary(value, type, ctx);
    return codec.decodeAsString(wire, 0, wire.length, type, ctx);
  }

  /**
   * getString on a geometric value in binary transfer must render whole-number coordinates the way the
   * server's text form does — {@code 1}, not Java's {@code 1.0}, and {@code 1e+100}, not {@code 1.0E100}
   * — so it agrees with the text-transfer rendering. line/lseg/path/polygon/circle formatted the
   * coordinates through {@code Double.toString} and drifted; box and point are unaffected and stay as
   * they were. The differential backward-compat oracle (pgjdbc-compat-test) flagged the drift.
   */
  @Test
  void binaryGeometricGetStringMatchesServerTextOffline() throws SQLException {
    PgType line = geometric("line", Oid.LINE);
    assertEquals("{1,-1,0}",
        binaryGetString(LineCodec.INSTANCE, line, new PGline(1, -1, 0)), "line diagonal");
    assertEquals("{1e+100,1,0}",
        binaryGetString(LineCodec.INSTANCE, line, new PGline(1e100, 1, 0)), "line large");

    PgType lseg = geometric("lseg", Oid.LSEG);
    assertEquals("[(0,0),(1,1)]",
        binaryGetString(LsegCodec.INSTANCE, lseg,
            new PGlseg(new PGpoint(0, 0), new PGpoint(1, 1))), "lseg unit");
    assertEquals("[(-1.5,-2.5),(3,4)]",
        binaryGetString(LsegCodec.INSTANCE, lseg,
            new PGlseg(new PGpoint(-1.5, -2.5), new PGpoint(3, 4))), "lseg signed");

    PgType path = geometric("path", Oid.PATH);
    assertEquals("[(0,0),(1,1),(2,0)]",
        binaryGetString(PathCodec.INSTANCE, path,
            new PGpath(new PGpoint[]{new PGpoint(0, 0), new PGpoint(1, 1), new PGpoint(2, 0)}, true)),
        "path open");
    assertEquals("((0,0),(1,1),(2,0))",
        binaryGetString(PathCodec.INSTANCE, path,
            new PGpath(new PGpoint[]{new PGpoint(0, 0), new PGpoint(1, 1), new PGpoint(2, 0)}, false)),
        "path closed");

    assertEquals("((0,0),(1,0),(1,1),(0,1))",
        binaryGetString(PolygonCodec.INSTANCE, geometric("polygon", Oid.POLYGON),
            new PGpolygon(new PGpoint[]{new PGpoint(0, 0), new PGpoint(1, 0), new PGpoint(1, 1),
                new PGpoint(0, 1)})), "polygon square");

    PgType circle = geometric("circle", Oid.CIRCLE);
    assertEquals("<(0,0),1>",
        binaryGetString(CircleCodec.INSTANCE, circle, new PGcircle(0, 0, 1)), "circle unit");
    assertEquals("<(-1.5,-2.5),3>",
        binaryGetString(CircleCodec.INSTANCE, circle, new PGcircle(-1.5, -2.5, 3)),
        "circle signed centre");
    assertEquals("<(0,0),1e+100>",
        binaryGetString(CircleCodec.INSTANCE, circle, new PGcircle(0, 0, 1e100)),
        "circle large radius");
    // The fixed<->scientific switch (leading digit's exponent leaving [-4, 14]) rendered straight
    // through the codec, so the boundary is pinned offline too: 1e14 stays fixed, 1e15 flips.
    assertEquals("<(0,0),100000000000000>",
        binaryGetString(CircleCodec.INSTANCE, circle, new PGcircle(0, 0, 1e14)), "circle 1e14 radius");
    assertEquals("<(0,0),1e+15>",
        binaryGetString(CircleCodec.INSTANCE, circle, new PGcircle(0, 0, 1e15)), "circle 1e15 radius");
    assertEquals("<(0,0),0.0001>",
        binaryGetString(CircleCodec.INSTANCE, circle, new PGcircle(0, 0, 1e-4)), "circle 1e-4 radius");
    assertEquals("<(0,0),1e-05>",
        binaryGetString(CircleCodec.INSTANCE, circle, new PGcircle(0, 0, 1e-5)), "circle 1e-5 radius");
  }

  @Test
  void structFastPathStreamingMatchesMaterializedBytesOffline() throws SQLException, IOException {
    // The Struct fast path streams each attribute straight into a BackpatchingBinarySink,
    // back-patching per-field length prefixes. Assert it produces the SAME bytes as the
    // materializing byte[] path. This proves output equivalence only — not the absence of an
    // intermediate byte[] (that would need an allocation-counting sink).
    PgType type = composite("pt", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    PgStruct value = new PgStruct(type, new Object[]{10, 20, "hello, struct"}, null);

    byte[] materialized = CompositeCodec.INSTANCE.encodeBinary(value, type, ctx);
    BackpatchByteArrayOutputStream streamed = new BackpatchByteArrayOutputStream();
    CompositeCodec.INSTANCE.encodeBinary(value, type, ctx, streamed);
    assertArrayEquals(materialized, streamed.toByteArray(), "Struct fast path");
  }

  @Test
  void sqlDataStreamingFallbackMatchesMaterializedBytesOffline() throws SQLException, IOException {
    // An SQLData value does not take the Struct fast path: streaming encodeBinary falls back to
    // out.write(encodeBinary(...)), a length-correct materialize-then-copy. Assert the fallback
    // writes the same bytes as the byte[] path. (A plain PGobject is never binary-encoded here —
    // canEncodeBinary() gates it to text — so SQLData is the value that actually exercises the
    // fallback branch.)
    PgType type = composite("point_t", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    Point value = point(3, 4, "corner");

    byte[] materialized = CompositeCodec.INSTANCE.encodeBinary(value, type, ctx);
    BackpatchByteArrayOutputStream streamed = new BackpatchByteArrayOutputStream();
    CompositeCodec.INSTANCE.encodeBinary(value, type, ctx, streamed);
    assertArrayEquals(materialized, streamed.toByteArray(), "SQLData fallback");
  }

  // A delegate codec that forwards to a streaming inner codec must emit the same bytes through its
  // streaming form as through its materialising byte[] form.
  private static void assertStreamMatchesMaterialized(BinaryCodec codec, PgType type, Object value,
      CodecContext ctx) throws SQLException, IOException {
    byte[] materialized = codec.encodeBinary(value, type, ctx);
    BackpatchByteArrayOutputStream sink = new BackpatchByteArrayOutputStream();
    ((StreamingBinaryCodec) codec).encodeBinary(value, type, ctx, sink);
    assertArrayEquals(materialized, sink.toByteArray());
  }

  @Test
  void pgobjectDelegateStreamingMatchesMaterializedOffline() throws SQLException, IOException {
    // PGobjectCodec forwards to its delegate; with a streaming delegate (int4) the sink path must
    // match the materialised bytes.
    CodecContext ctx = PgCodecContext.offlineBuilder().build();
    PgType int4 = new PgType(new ObjectName("pg_catalog", "int4"), "int4", Oid.INT4, 'b', 'N', -1,
        0, 0, 0);
    PGobjectCodec codec = new PGobjectCodec(PGobject.class, Int4Codec.INSTANCE);
    assertStreamMatchesMaterialized(codec, int4, 42, ctx);
  }

  @Test
  void domainDelegateStreamingMatchesMaterializedOffline() throws SQLException, IOException {
    // DomainCodec resolves its base type (int4, a streaming codec) and forwards to it.
    PgType domain = new PgType(new ObjectName("public", "dom_int"), "public.dom_int", 90_010,
        'd', 'N', -1, 0, 0, Oid.INT4);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(domain).build();
    assertStreamMatchesMaterialized(DomainCodec.INSTANCE, domain, 7, ctx);
  }

  @Test
  void sqlDataRoundtripsOffline() throws SQLException {
    PgType type = composite("point_t", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    Point corner = point(3, 4, "corner");

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(corner, type, ctx, format);
      Point back = Codecs.decode(raw, type, ctx, Point.class);
      assertEquals(corner, back, "SQLData " + format);
    }
  }

  @Test
  void hstoreRoundtripsOffline() throws SQLException {
    // hstore decodes from bytes through the wire encoding; offline that encoding is derived from the
    // context charset, so the binary path works without a connection.
    PgType type = new PgType(new ObjectName("public", "hstore"), "hstore", HSTORE_OID, 'b', 'U', -1,
        0, 0, 0);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    Map<String, String> value = new LinkedHashMap<>();
    value.put("one", "1");
    value.put("two", "2");

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, type, ctx, format);
      Object back = Codecs.decode(raw, type, ctx, Map.class);
      assertEquals(value, back, "hstore " + format);
    }
  }

  @Test
  void textArrayRoundtripsToStringArrayOffline() throws SQLException {
    CodecContext ctx = PgCodecContext.offlineBuilder().build();
    String[] value = {"a", "b,c", "d e"};

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, TEXT_ARRAY, ctx, format);
      assertArrayEquals(value, Codecs.decode(raw, TEXT_ARRAY, ctx, String[].class),
          "text[] " + format);
    }
  }

  /**
   * {@code byte[].class.isArray()} is true, so a {@code getObject(col, byte[].class)} request lands in
   * the array-decode branch — but no element mapping of an {@code int4[]}/{@code text[]} column yields
   * a {@code byte[]}. The codec must refuse rather than hand back the element mapping
   * ({@code Integer[]} / {@code String[]}), which would raise a {@link ClassCastException} at the
   * {@code <T> T getObject(int, Class<T>)} call site. Verified directly on the codec, over both wire
   * formats, with no connection.
   */
  @Test
  void byteArrayTargetRefusedOnNonByteaArrayOffline() throws SQLException {
    CodecContext ctx = PgCodecContext.offlineBuilder().build();

    for (Format format : Format.values()) {
      RawValue int4Raw = Codecs.encode(new Integer[]{1, 2, 3}, INT4_ARRAY, ctx, format);
      RawValue textRaw = Codecs.encode(new String[]{"a", "b"}, TEXT_ARRAY, ctx, format);
      PSQLException int4Ex = assertThrows(PSQLException.class,
          () -> Codecs.decode(int4Raw, INT4_ARRAY, ctx, byte[].class),
          () -> "int4[] as byte[] " + format);
      assertEquals(PSQLState.DATA_TYPE_MISMATCH.getState(), int4Ex.getSQLState(),
          () -> "int4[] as byte[] " + format);
      PSQLException textEx = assertThrows(PSQLException.class,
          () -> Codecs.decode(textRaw, TEXT_ARRAY, ctx, byte[].class),
          () -> "text[] as byte[] " + format);
      assertEquals(PSQLState.DATA_TYPE_MISMATCH.getState(), textEx.getSQLState(),
          () -> "text[] as byte[] " + format);
    }
  }

  /**
   * The refusal above must not catch the one array whose leaf genuinely is a {@code byte[]}: a
   * {@code bytea[]} column maps to a {@code byte[][]}, so that target still decodes through the shared
   * walker and round-trips its element {@code byte[]}s.
   */
  @Test
  void byteMatrixTargetStillDecodesByteaArrayOffline() throws SQLException {
    CodecContext ctx = PgCodecContext.offlineBuilder().build();
    byte[][] value = {{1, 2}, {(byte) 0xff}};

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, BYTEA_ARRAY, ctx, format);
      byte[][] back = Codecs.decode(raw, BYTEA_ARRAY, ctx, byte[][].class);
      assertNotNull(back, () -> "bytea[] as byte[][] " + format);
      assertArrayEquals(value[0], back[0], () -> "bytea[] element 0 " + format);
      assertArrayEquals(value[1], back[1], () -> "bytea[] element 1 " + format);
    }
  }

  /**
   * A {@code numeric[]} decodes to a {@code BigDecimal[]}, but {@code numeric} carries NaN / ±Infinity
   * that {@link BigDecimal} cannot represent (the scalar path surfaces them as {@link Double}
   * sentinels). Storing such a sentinel into the {@code BigDecimal[]} leaf once leaked an unchecked
   * {@link ArrayStoreException}; the element decode now refuses a non-finite value with a checked
   * {@code SQLState 22003}, matching the scalar {@code getBigDecimal} path and the 42.7.13 baseline.
   * Both wire formats and both the 1-D and 2-D shapes are checked.
   */
  @Test
  void numericArrayWithNonFiniteElementRefusesOffline() throws SQLException {
    CodecContext ctx = PgCodecContext.offlineBuilder().build();
    double[] nonFinite = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};

    for (Format format : Format.values()) {
      for (double value : nonFinite) {
        RawValue dim1 = Codecs.encode(new Double[]{value}, NUMERIC_ARRAY, ctx, format);
        assertOutOfRange("numeric[] {" + value + "} " + format,
            () -> Codecs.decode(dim1, NUMERIC_ARRAY, ctx, Object.class));

        RawValue dim2 = Codecs.encode(new Double[][]{{value}}, NUMERIC_ARRAY, ctx, format);
        assertOutOfRange("numeric[][] {{" + value + "}} " + format,
            () -> Codecs.decode(dim2, NUMERIC_ARRAY, ctx, Object.class));
      }
    }
  }

  /** A finite {@code numeric[]} still decodes to its {@code BigDecimal[]} contract, both formats. */
  @Test
  void numericArrayFiniteRoundtripsToBigDecimalArrayOffline() throws SQLException {
    CodecContext ctx = PgCodecContext.offlineBuilder().build();
    BigDecimal[] value = {new BigDecimal("1.5"), new BigDecimal("-2.25")};

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, NUMERIC_ARRAY, ctx, format);
      BigDecimal[] back = Codecs.decode(raw, NUMERIC_ARRAY, ctx, BigDecimal[].class);
      assertArrayEquals(value, back, "numeric[] " + format);
    }
  }

  /**
   * A {@code Double[]} target keeps the non-finite values that {@code BigDecimal[]} rejects:
   * {@link Double} can hold NaN / ±Infinity, so {@code getObject(col, Double[].class)} decodes each
   * element through the numeric codec's {@code decodeAsDouble} and returns the sentinel rather than
   * refusing. Both wire formats and both the 1-D and 2-D shapes are checked.
   */
  @Test
  void numericArrayNonFiniteDecodesToDoubleArrayOffline() throws SQLException {
    CodecContext ctx = PgCodecContext.offlineBuilder().build();
    Double[] value = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.5};

    for (Format format : Format.values()) {
      RawValue dim1 = Codecs.encode(value, NUMERIC_ARRAY, ctx, format);
      assertArrayEquals(value, Codecs.decode(dim1, NUMERIC_ARRAY, ctx, Double[].class),
          "numeric[] as Double[] " + format);

      Double[][] nested = {{Double.NaN}, {Double.POSITIVE_INFINITY}};
      RawValue dim2 = Codecs.encode(nested, NUMERIC_ARRAY, ctx, format);
      Double[][] back = Codecs.decode(dim2, NUMERIC_ARRAY, ctx, Double[][].class);
      assertNotNull(back, "numeric[][] as Double[][] " + format);
      assertArrayEquals(nested[0], back[0], "numeric[][] as Double[][] row 0 " + format);
      assertArrayEquals(nested[1], back[1], "numeric[][] as Double[][] row 1 " + format);
    }
  }

  @Test
  void compositeArrayDecodesToStructsOffline() throws SQLException {
    PgType element = composite("pt2", POINT_OID, field("x", Oid.INT4, 1), field("y", Oid.INT4, 2));
    PgType arrayType = new PgType(new ObjectName("public", "_pt2"), "public.pt2[]", POINT_ARRAY_OID,
        'b', 'A', -1, POINT_OID, 0, 0);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(element).type(arrayType).build();
    PgStruct[] structs = {
        new PgStruct(element, new Object[]{1, 2}, null),
        new PgStruct(element, new Object[]{3, 4}, null),
    };

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(structs, arrayType, ctx, format);
      // An array of composites decodes offline to an Object[] of connectionless structs.
      Object[] decoded = (Object[]) Codecs.decode(raw, arrayType, ctx, Object.class);
      assertNotNull(decoded, "array-of-struct " + format);
      assertEquals(2, decoded.length, "array-of-struct " + format);
      assertArrayEquals(new Object[]{1, 2}, ((Struct) decoded[0]).getAttributes(),
          "array-of-struct " + format);
      assertArrayEquals(new Object[]{3, 4}, ((Struct) decoded[1]).getAttributes(),
          "array-of-struct " + format);
    }
  }

  @Test
  void sqlDataArrayRoundtripsOffline() throws SQLException {
    PgType element = composite("point_t", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    PgType arrayType = new PgType(new ObjectName("public", "_point_t"), "public.point_t[]",
        POINT_ARRAY_OID, 'b', 'A', -1, POINT_OID, 0, 0);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(element).type(arrayType).build();
    Point[] points = {point(1, 2, "a"), point(3, 4, "b,c")};

    for (Format format : Format.values()) {
      // A typed CustomDto[] target decodes each element to the SQLData class — no connection.
      RawValue raw = Codecs.encode(points, arrayType, ctx, format);
      Point[] back = Codecs.decode(raw, arrayType, ctx, Point[].class);
      assertArrayEquals(points, back, "CustomDto[] " + format);
    }
  }

  @Test
  void nestedArrayInSqlDataReportsClearErrorOffline() throws SQLException {
    PgType type = composite("has_array", HAS_ARRAY_OID, field("arr", Oid.INT4_ARRAY, 1));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    // Encode a struct whose only attribute is an int4[]; the binary wire form is connectionless.
    RawValue raw = Codecs.encode(
        new PgStruct(type, new Object[]{new Integer[]{1, 2}}, null), type, ctx, Format.BINARY);

    // Decoding it as an SQLData object that calls readArray() needs a connection-bound PgArray.
    PSQLException ex = assertThrows(PSQLException.class,
        () -> Codecs.decode(raw, type, ctx, ArrayHolder.class));
    assertEquals(PSQLState.NOT_IMPLEMENTED.getState(), ex.getSQLState());
  }

  @Test
  void offlineCompositeWithoutFieldsReportsClearError() {
    // A composite registered without its attributes cannot be materialized as a struct offline.
    PgType fieldless = new PgType(new ObjectName("public", "bare"), "public.bare", POINT_OID, 'c',
        'C', -1, 0, 0, 0);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(fieldless).build();

    PSQLException ex = assertThrows(PSQLException.class,
        () -> Codecs.decode(RawValue.binary(new byte[]{0, 0, 0, 0}), fieldless, ctx, Point.class));
    assertEquals(PSQLState.INVALID_PARAMETER_TYPE.getState(), ex.getSQLState());
  }

  // ------------------------------------------------------------------------ money

  private static final PgType MONEY = new PgType(
      new ObjectName("pg_catalog", "money"), "money", Oid.MONEY, 'b', 'N', -1, 0, 0, 0);

  /** Builds the 8-byte binary wire form of a {@code money} value: an int64 count of minor units. */
  private static byte[] moneyWire(long cents) {
    byte[] wire = new byte[8];
    ByteConverter.int8(wire, 0, cents);
    return wire;
  }

  @Test
  void moneyStaysTextOnReceive() {
    // money renders per lc_monetary; the driver cannot rebuild that string from the raw int64, so it
    // never requests money in binary. This is what keeps the numeric getters and getString correct.
    assertEquals(false, MoneyCodec.INSTANCE.supportsBinaryRead(), "money supportsBinaryRead");
    assertEquals(Double.class, MoneyCodec.INSTANCE.getDefaultJavaType(), "money default Java type");
  }

  @Test
  void moneyTextGettersStripLocaleFormattingOffline() throws SQLException {
    CodecContext ctx = offlineCtx(MONEY);
    // Every server rendering the en_US/C locales produce for these amounts, including the two negative
    // forms ("-$1.00" and the parenthesized "($1.00)") and grouping separators near the int64 ends.
    assertMoneyText(ctx, "$0.00", 0L, new BigDecimal("0.00"));
    assertMoneyText(ctx, "$1.00", 1L, new BigDecimal("1.00"));
    assertMoneyText(ctx, "-$1.00", -1L, new BigDecimal("-1.00"));
    assertMoneyText(ctx, "($1.00)", -1L, new BigDecimal("-1.00"));
    assertMoneyText(ctx, "$92,233,720,368,547,758.07", 92233720368547758L,
        new BigDecimal("92233720368547758.07"));
    assertMoneyText(ctx, "-$92,233,720,368,547,758.08", -92233720368547758L,
        new BigDecimal("-92233720368547758.08"));
  }

  private static void assertMoneyText(CodecContext ctx, String literal, long wholeUnits, BigDecimal exact)
      throws SQLException {
    MoneyCodec codec = MoneyCodec.INSTANCE;
    // getObject default is Double, matching the legacy Types.DOUBLE contract.
    assertEquals(exact.doubleValue(), codec.decodeText(literal, MONEY, ctx), () -> "getObject " + literal);
    // getString hands back the server's locale literal untouched.
    assertEquals(literal, codec.decodeAsString(literal, MONEY, ctx), () -> "getString " + literal);
    assertEquals(exact.doubleValue(), codec.decodeAsDouble(literal, MONEY, ctx), () -> "getDouble " + literal);
    assertEquals(wholeUnits, codec.decodeAsLong(literal, MONEY, ctx), () -> "getLong " + literal);
    assertEquals(exact, codec.decodeAsBigDecimal(literal, MONEY, ctx), () -> "getBigDecimal " + literal);
    // Explicit getObject(int, PGmoney.class) still yields PGmoney, now parsing the negative forms too.
    PGmoney money = codec.decodeTextAs(literal, MONEY, PGmoney.class, ctx);
    assertNotNull(money, () -> "PGmoney " + literal);
    assertEquals(exact.doubleValue(), money.val, () -> "PGmoney.val " + literal);
  }

  @Test
  void moneyBinaryDecodesInt64Offline() throws SQLException {
    CodecContext ctx = offlineCtx(MONEY);
    // Defensive path: money is text-only on receive, but a binary value can still arrive nested in a
    // binary record/array or when a caller forces money into binary. Decode the int64 count of cents.
    assertMoneyBinary(ctx, 0L, 0L, new BigDecimal("0.00"));
    assertMoneyBinary(ctx, 100L, 1L, new BigDecimal("1.00"));
    assertMoneyBinary(ctx, -100L, -1L, new BigDecimal("-1.00"));
    assertMoneyBinary(ctx, Long.MAX_VALUE, 92233720368547758L, new BigDecimal("92233720368547758.07"));
    assertMoneyBinary(ctx, Long.MIN_VALUE, -92233720368547758L, new BigDecimal("-92233720368547758.08"));
  }

  private static void assertMoneyBinary(CodecContext ctx, long cents, long wholeUnits, BigDecimal exact)
      throws SQLException {
    MoneyCodec codec = MoneyCodec.INSTANCE;
    byte[] wire = moneyWire(cents);
    assertEquals(exact.doubleValue(), codec.decodeBinary(wire, 0, wire.length, MONEY, ctx),
        () -> "binary getObject " + cents);
    assertEquals(exact.doubleValue(), codec.decodeAsDouble(wire, 0, wire.length, MONEY, ctx),
        () -> "binary getDouble " + cents);
    assertEquals(wholeUnits, codec.decodeAsLong(wire, 0, wire.length, MONEY, ctx),
        () -> "binary getLong " + cents);
    assertEquals(exact, codec.decodeAsBigDecimal(wire, 0, wire.length, MONEY, ctx),
        () -> "binary getBigDecimal " + cents);
    // No locale is available offline, so binary getString emits a plain decimal.
    assertEquals(exact.toPlainString(), codec.decodeAsString(wire, 0, wire.length, MONEY, ctx),
        () -> "binary getString " + cents);
  }

  @Test
  void moneyGetIntOverflowsForLargeValuesOffline() throws SQLException {
    CodecContext ctx = offlineCtx(MONEY);
    MoneyCodec codec = MoneyCodec.INSTANCE;
    assertEquals(1, codec.decodeAsInt("$1.00", MONEY, ctx), "small money fits int");
    // The whole-unit value overflows int, matching the legacy string conversion (SQLState 22003).
    PSQLException ex = assertThrows(PSQLException.class,
        () -> codec.decodeAsInt("$92,233,720,368,547,758.07", MONEY, ctx), "money getInt overflow");
    assertEquals(PSQLState.NUMERIC_VALUE_OUT_OF_RANGE.getState(), ex.getSQLState(), "money getInt SQLState");
  }

  @Test
  void moneyBinaryWrongLengthRefusedOffline() {
    CodecContext ctx = offlineCtx(MONEY);
    PSQLException ex = assertThrows(PSQLException.class,
        () -> MoneyCodec.INSTANCE.decodeAsLong(new byte[]{0, 0, 0, 0}, 0, 4, MONEY, ctx),
        "money binary must be 8 bytes");
    assertEquals(PSQLState.DATA_ERROR.getState(), ex.getSQLState(), "money binary length SQLState");
  }

  @Test
  void moneyTextParsingIsLocaleIndependentOffline() throws SQLException {
    CodecContext ctx = offlineCtx(MONEY);
    // The parser picks the decimal separator by position (the rightmost separator), so it reads every
    // locale rendering without knowing whether the locale uses ',' or '.' for the decimal point, and
    // regardless of where the currency symbol sits.
    assertMoneyParse(ctx, "$1.00", "1.00");                       // en_US
    assertMoneyParse(ctx, "-$1.00", "-1.00");                     // en_US negative
    assertMoneyParse(ctx, "($1.00)", "-1.00");                    // parenthesized negative
    assertMoneyParse(ctx, "$1,234.56", "1234.56");                // en_US grouping
    assertMoneyParse(ctx, "1.234,56 €", "1234.56");               // de_DE: comma decimal, dot grouping
    assertMoneyParse(ctx, "-1.234,56 €", "-1234.56");             // de_DE negative
    assertMoneyParse(ctx, "1 234,56 €", "1234.56");               // fr_FR: space grouping, comma decimal
    assertMoneyParse(ctx, "$92,233,720,368,547,758.07", "92233720368547758.07");
  }

  private static void assertMoneyParse(CodecContext ctx, String rendering, String expected)
      throws SQLException {
    BigDecimal parsed = MoneyCodec.INSTANCE.decodeAsBigDecimal(rendering, MONEY, ctx);
    assertNotNull(parsed, () -> "parse " + rendering);
    assertEquals(0, parsed.compareTo(new BigDecimal(expected)), () -> rendering + " -> " + parsed);
  }

  /** A simple {@link SQLData} value object whose attributes are all built-in scalars. */
  public static final class Point implements SQLData {
    private int x;
    private int y;
    private String label = "";

    @Override
    public String getSQLTypeName() {
      return "public.point_t";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.x = stream.readInt();
      this.y = stream.readInt();
      this.label = Objects.requireNonNull(stream.readString());
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(x);
      stream.writeInt(y);
      stream.writeString(label);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Point)) {
        return false;
      }
      Point point = (Point) o;
      return x == point.x && y == point.y && label.equals(point.label);
    }

    @Override
    public int hashCode() {
      return Objects.hash(x, y, label);
    }
  }

  /** An {@link SQLData} value whose single attribute is an array, used for the nested-array case. */
  public static final class ArrayHolder implements SQLData {
    private Array array;

    @Override
    public String getSQLTypeName() {
      return "public.has_array";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.array = Objects.requireNonNull(stream.readArray());
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeArray(array);
    }
  }
}
