/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.IntervalStyle;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;

class IntervalCodecTest {

  private IntervalCodec codec;
  private PgType intervalType;

  @BeforeEach
  void setUp() {
    codec = IntervalCodec.INSTANCE;
    intervalType = new PgType(
        new ObjectName("pg_catalog", "interval"),
        "interval",
        Oid.INTERVAL,
        'b', 'T', -1, 0, 0, 0
    );
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("interval", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(PGInterval.class, codec.getDefaultJavaType());
  }

  // ==================== Text Decoding ====================

  @Test
  void decodeText_simpleInterval() throws SQLException {
    PGInterval result = (PGInterval) codec.decodeText("1 year 2 mons 3 days 04:05:06", intervalType, null);
    assertEquals(1, result.getYears());
    assertEquals(2, result.getMonths());
    assertEquals(3, result.getDays());
    assertEquals(4, result.getHours());
    assertEquals(5, result.getMinutes());
    assertEquals(6.0, result.getSeconds(), 0.001);
  }

  @Test
  void decodeText_hoursOnly() throws SQLException {
    PGInterval result = (PGInterval) codec.decodeText("04:05:06", intervalType, null);
    assertEquals(0, result.getYears());
    assertEquals(0, result.getMonths());
    assertEquals(0, result.getDays());
    assertEquals(4, result.getHours());
    assertEquals(5, result.getMinutes());
    assertEquals(6.0, result.getSeconds(), 0.001);
  }

  // ==================== Binary Decoding ====================

  @Test
  void decodeBinary_simpleInterval() throws SQLException {
    // 1 hour, 2 days, 3 months
    byte[] data = new byte[16];
    long microseconds = 3600_000_000L; // 1 hour
    ByteConverter.int8(data, 0, microseconds);
    ByteConverter.int4(data, 8, 2); // days
    ByteConverter.int4(data, 12, 3); // months

    PGInterval result = (PGInterval) codec.decodeBinary(data, 0, data.length, intervalType, null);
    assertEquals(0, result.getYears());
    assertEquals(3, result.getMonths());
    assertEquals(2, result.getDays());
    assertEquals(1, result.getHours());
    assertEquals(0, result.getMinutes());
    assertEquals(0.0, result.getSeconds(), 0.001);
  }

  @Test
  void decodeBinary_withYears() throws SQLException {
    // 14 months = 1 year 2 months
    byte[] data = new byte[16];
    ByteConverter.int8(data, 0, 0);
    ByteConverter.int4(data, 8, 0);
    ByteConverter.int4(data, 12, 14);

    PGInterval result = (PGInterval) codec.decodeBinary(data, 0, data.length, intervalType, null);
    assertEquals(1, result.getYears());
    assertEquals(2, result.getMonths());
  }

  @Test
  void decodeBinary_invalidLength() {
    byte[] data = new byte[8]; // wrong length
    assertThrows(PSQLException.class, () -> codec.decodeBinary(data, 0, data.length, (TypeDescriptor) intervalType, (CodecContext) null));
  }

  // ==================== Max-range microseconds (issue: RuntimeException leak) ====================

  /** The interval whose microsecond field is Long.MAX_VALUE: {@code '2562047788:00:54.775807'}. */
  private static byte[] maxRangeInterval() {
    byte[] data = new byte[16];
    ByteConverter.int8(data, 0, Long.MAX_VALUE); // microseconds
    ByteConverter.int4(data, 8, 0); // days
    ByteConverter.int4(data, 12, 0); // months
    return data;
  }

  /**
   * getObject builds a PGInterval, whose hours are an int; Long.MAX_VALUE microseconds are ~2562047788
   * hours, past int range. The decode must refuse with a checked SQLException, not leak the unchecked
   * exception PGInterval.setSeconds throws once the overflowed hour corrupts the seconds.
   */
  @Test
  void decodeBinary_maxMicroseconds_refusesOutOfRange() {
    byte[] data = maxRangeInterval();
    PSQLException ex = assertThrows(PSQLException.class,
        () -> codec.decodeBinary(data, 0, data.length, intervalType, null));
    assertEquals(PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE.getState(), ex.getSQLState());
  }

  /**
   * getString renders straight from the wire fields, so the same max-range value that getObject
   * refuses still produces the server's text form for every style.
   */
  @Test
  void decodeAsString_binary_maxMicroseconds_rendersServerForm() throws SQLException {
    byte[] data = maxRangeInterval();
    assertEquals("2562047788:00:54.775807",
        codec.decodeAsString(data, 0, data.length, intervalType, contextWithStyle(IntervalStyle.POSTGRES)));
    assertEquals("@ 2562047788 hours 54.775807 secs",
        codec.decodeAsString(data, 0, data.length, intervalType, contextWithStyle(IntervalStyle.POSTGRES_VERBOSE)));
    assertEquals("2562047788:00:54.775807",
        codec.decodeAsString(data, 0, data.length, intervalType, contextWithStyle(IntervalStyle.SQL_STANDARD)));
    assertEquals("PT2562047788H54.775807S",
        codec.decodeAsString(data, 0, data.length, intervalType, contextWithStyle(IntervalStyle.ISO_8601)));
  }

  /** decodeBinaryAs(String) shares the render path, so it renders the max-range value too. */
  @Test
  void decodeBinaryAs_String_maxMicroseconds_renders() throws SQLException {
    byte[] data = maxRangeInterval();
    assertEquals("2562047788:00:54.775807",
        codec.decodeBinaryAs(data, 0, data.length, intervalType, String.class, null));
  }

  /** decodeBinaryAs(PGInterval) shares the PGInterval path, so it refuses the max-range value. */
  @Test
  void decodeBinaryAs_PGInterval_maxMicroseconds_refuses() {
    byte[] data = maxRangeInterval();
    PSQLException ex = assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, 0, data.length, intervalType, PGInterval.class, null));
    assertEquals(PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE.getState(), ex.getSQLState());
  }

  // ==================== Encoding ====================

  @Test
  void encodeText_pgInterval() throws SQLException {
    PGInterval interval = new PGInterval(1, 2, 3, 4, 5, 6.0);
    String result = codec.encodeText(interval, intervalType, null);
    // PGInterval.getValue() returns the PostgreSQL text representation
    assertEquals(interval.getValue(), result);
  }

  @Test
  void encodeBinary_pgInterval() throws SQLException {
    PGInterval interval = new PGInterval(0, 3, 2, 1, 0, 0);
    byte[] result = codec.encodeBinary(interval, intervalType, null);

    assertEquals(16, result.length);
    long microseconds = ByteConverter.int8(result, 0);
    int days = ByteConverter.int4(result, 8);
    int months = ByteConverter.int4(result, 12);

    assertEquals(3600_000_000L, microseconds); // 1 hour
    assertEquals(2, days);
    assertEquals(3, months);
  }

  @Test
  void encodeBinary_fromString() throws SQLException {
    byte[] result = codec.encodeBinary("1 year 2 mons", intervalType, null);
    assertEquals(16, result.length);
    int months = ByteConverter.int4(result, 12);
    assertEquals(14, months); // 1 year + 2 months
  }

  @Test
  void encodeBinary_unsupportedType() {
    assertThrows(PSQLException.class,
        () -> codec.encodeBinary(42, intervalType, null));
  }

  // ==================== Type Conversions ====================

  @Test
  void decodeAsInt_throws() {
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asInt(codec, "1 hour", intervalType, null));
  }

  @Test
  void decodeAsLong_throws() {
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asLong(codec, "1 hour", intervalType, null));
  }

  @Test
  void decodeAsDouble_throws() {
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asDouble(codec, "1 hour", intervalType, null));
  }

  @Test
  void decodeAsString_text() throws SQLException {
    assertEquals("1 year", codec.decodeAsString("1 year", intervalType, null));
  }

  /**
   * getString must be wire-format independent: reading a value in binary has to yield the same string
   * as the server's default {@code IntervalStyle=postgres} text form, not PGInterval.getValue()'s
   * verbose {@code 1 days 2 hours 3 mins 4 secs} rendering.
   */
  @Test
  void decodeAsString_binary_matchesServerPostgresStyle() throws SQLException {
    // '1 day 02:03:04'::interval
    assertEquals("1 day 02:03:04", binaryDecodeAsString(0, 1, 2 * 3600 + 3 * 60 + 4, 0));
    // Sign carries into the next field, exactly like the backend's EncodeInterval.
    assertEquals("-1 days +02:03:04", binaryDecodeAsString(0, -1, 2 * 3600 + 3 * 60 + 4, 0));
    // A pure zero interval renders as 00:00:00, not the empty string.
    assertEquals("00:00:00", binaryDecodeAsString(0, 0, 0, 0));
    // Sub-second: fractional digits with trailing zeros trimmed.
    assertEquals("00:00:00.5", binaryDecodeAsString(0, 0, 0, 500_000));
    assertEquals("-00:00:00.000001", binaryDecodeAsString(0, 0, 0, -1));
  }

  /**
   * The three non-default {@code IntervalStyle}s, cross-checked against the live server by
   * {@code IntervalStyleGetStringTest}. Here they pin the exact strings without a database.
   */
  @Test
  void decodeAsString_binary_postgresVerbose() throws SQLException {
    IntervalStyle s = IntervalStyle.POSTGRES_VERBOSE;
    assertEquals("@ 1 day 2 hours 3 mins 4 secs", binaryDecodeAsString(0, 1, 2 * 3600 + 3 * 60 + 4, 0, s));
    // First non-zero field (the negative day) fixes the sign; later fields flip and " ago" is added.
    assertEquals("@ 1 day -2 hours -3 mins -4 secs ago",
        binaryDecodeAsString(0, -1, 2 * 3600 + 3 * 60 + 4, 0, s));
    assertEquals("@ 0", binaryDecodeAsString(0, 0, 0, 0, s));
    assertEquals("@ 1 day 0.5 secs", binaryDecodeAsString(0, 1, 0, 500_000, s));
    assertEquals("@ 0.5 secs ago", binaryDecodeAsString(0, 0, 0, -500_000, s));
  }

  @Test
  void decodeAsString_binary_sqlStandard() throws SQLException {
    IntervalStyle s = IntervalStyle.SQL_STANDARD;
    assertEquals("1 2:03:04", binaryDecodeAsString(0, 1, 2 * 3600 + 3 * 60 + 4, 0, s));
    // All-negative day-time collapses to one leading sign.
    assertEquals("-1 2:03:04", binaryDecodeAsString(0, -1, -(2 * 3600 + 3 * 60 + 4), 0, s));
    // Mixed day/time signs force the fully-signed form.
    assertEquals("+0-0 -1 +2:03:04", binaryDecodeAsString(0, -1, 2 * 3600 + 3 * 60 + 4, 0, s));
    assertEquals("1-2", binaryDecodeAsString(14, 0, 0, 0, s));
    assertEquals("-1-9", binaryDecodeAsString(-21, 0, 0, 0, s));
    assertEquals("0", binaryDecodeAsString(0, 0, 0, 0, s));
    assertEquals("2:03:04", binaryDecodeAsString(0, 0, 2 * 3600 + 3 * 60 + 4, 0, s));
  }

  @Test
  void decodeAsString_binary_iso8601() throws SQLException {
    IntervalStyle s = IntervalStyle.ISO_8601;
    assertEquals("P1Y2M3DT4H5M6S",
        binaryDecodeAsString(14, 3, 4 * 3600 + 5 * 60 + 6, 0, s));
    assertEquals("P-1D", binaryDecodeAsString(0, -1, 0, 0, s));
    assertEquals("PT0S", binaryDecodeAsString(0, 0, 0, 0, s));
    assertEquals("P1DT0.5S", binaryDecodeAsString(0, 1, 0, 500_000, s));
    assertEquals("PT-0.5S", binaryDecodeAsString(0, 0, 0, -500_000, s));
    assertEquals("P-1Y-9M", binaryDecodeAsString(-21, 0, 0, 0, s));
  }

  /** Builds a binary interval wire value and decodes it through the getString path (postgres style). */
  private String binaryDecodeAsString(int months, int days, long wholeSeconds, int micros)
      throws SQLException {
    return binaryDecodeAsString(months, days, wholeSeconds, micros, IntervalStyle.POSTGRES);
  }

  /** As above, but rendered for the given {@link IntervalStyle}. */
  private String binaryDecodeAsString(int months, int days, long wholeSeconds, int micros,
      IntervalStyle style) throws SQLException {
    byte[] data = new byte[16];
    ByteConverter.int8(data, 0, wholeSeconds * 1_000_000L + micros);
    ByteConverter.int4(data, 8, days);
    ByteConverter.int4(data, 12, months);
    String s = codec.decodeAsString(data, 0, data.length, intervalType, contextWithStyle(style));
    return castNonNull(s);
  }

  /**
   * A {@link CodecContext} whose only meaningful method is {@link CodecContext#getIntervalStyle()};
   * the interval decode path consults nothing else, so any other call is an error, not a silent
   * default.
   */
  private static CodecContext contextWithStyle(IntervalStyle style) {
    return (CodecContext) Proxy.newProxyInstance(
        CodecContext.class.getClassLoader(),
        new Class<?>[]{CodecContext.class},
        (proxy, method, args) -> {
          if ("getIntervalStyle".equals(method.getName())) {
            return style;
          }
          throw new UnsupportedOperationException(method.getName());
        });
  }

  private static <T> T castNonNull(T value) {
    if (value == null) {
      throw new AssertionError("value must not be null");
    }
    return value;
  }

  // ==================== decodeBinaryAs/decodeTextAs ====================

  @Test
  void decodeBinaryAs_PGInterval() throws SQLException {
    byte[] data = new byte[16];
    ByteConverter.int8(data, 0, 0);
    ByteConverter.int4(data, 8, 5);
    ByteConverter.int4(data, 12, 0);

    PGInterval result = codec.decodeBinaryAs(data, 0, data.length, intervalType, PGInterval.class, null);
    assertEquals(5, result.getDays());
  }

  @Test
  void decodeBinaryAs_String() throws SQLException {
    // 1 day 02:03:04 — a case where PGInterval.getValue()'s verbose form ("1 days 2 hours 3 mins
    // 4 secs") differs from the server text form, so this pins the server-style rendering.
    byte[] data = new byte[16];
    ByteConverter.int8(data, 0, (2 * 3600 + 3 * 60 + 4) * 1_000_000L);
    ByteConverter.int4(data, 8, 1);
    ByteConverter.int4(data, 12, 0);

    String result = codec.decodeBinaryAs(data, 0, data.length, intervalType, String.class, null);
    assertEquals("1 day 02:03:04", result);
  }

  @Test
  void decodeBinaryAs_unsupported() {
    byte[] data = new byte[16];
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) intervalType, Integer.class, (CodecContext) null));
  }

  @Test
  void decodeTextAs_PGInterval() throws SQLException {
    PGInterval result = codec.decodeTextAs("5 days", intervalType, PGInterval.class, null);
    assertEquals(5, result.getDays());
  }

  @Test
  void decodeTextAs_String() throws SQLException {
    assertEquals("5 days", codec.decodeTextAs("5 days", intervalType, String.class, null));
  }

  // ==================== Roundtrip ====================

  @Test
  void binaryRoundtrip() throws SQLException {
    PGInterval original = new PGInterval(1, 2, 3, 4, 5, 6.0);
    byte[] encoded = codec.encodeBinary(original, intervalType, null);
    PGInterval decoded = (PGInterval) codec.decodeBinary(encoded, 0, encoded.length, intervalType, null);

    assertEquals(original.getYears(), decoded.getYears());
    assertEquals(original.getMonths(), decoded.getMonths());
    assertEquals(original.getDays(), decoded.getDays());
    assertEquals(original.getHours(), decoded.getHours());
    assertEquals(original.getMinutes(), decoded.getMinutes());
    assertEquals(original.getSeconds(), decoded.getSeconds(), 0.001);
  }

  private PGInterval binaryRoundTrip(PGInterval interval) throws SQLException {
    byte[] wire = codec.encodeBinary(interval, intervalType, null);
    return (PGInterval) codec.decodeBinary(wire, 0, wire.length, intervalType, null);
  }

  @Test
  void binaryRoundtrip_subSecondMicros() throws SQLException {
    // 0.999999 s is 999998.999... as a double, so encoding it with a truncating (long) cast dropped
    // the last microsecond. Rounding to the nearest microsecond keeps all six digits, so the value
    // round-trips exactly (compared by PGInterval.equals, not a tolerance).
    PGInterval original = new PGInterval(0, 0, 0, 0, 0, 0.999999);
    assertEquals(original, binaryRoundTrip(original));
  }

  @Test
  void binaryRoundtrip_negativeSubSecond() throws SQLException {
    PGInterval original = new PGInterval(0, 0, 0, 0, 0, -0.123456);
    assertEquals(original, binaryRoundTrip(original));
  }

  @Test
  void binaryRoundtrip_largeHours() throws SQLException {
    // 700000 hours is past 596_523, where the decoder's hours * 3600 term overflowed int and corrupted
    // the minutes. In long arithmetic it round-trips exactly.
    PGInterval original = new PGInterval(0, 0, 0, 700_000, 45, 30.5);
    assertEquals(original, binaryRoundTrip(original));
  }

  @Test
  void binaryRoundtrip_mixedWithMicros() throws SQLException {
    PGInterval original = new PGInterval(2, 3, 15, 10, 20, 30.500001);
    assertEquals(original, binaryRoundTrip(original));
  }
}
