/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
  void getTypeName() {
    assertEquals("interval", codec.getTypeName());
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
    byte[] data = new byte[16];
    ByteConverter.int8(data, 0, 0);
    ByteConverter.int4(data, 8, 5);
    ByteConverter.int4(data, 12, 0);

    String result = codec.decodeBinaryAs(data, 0, data.length, intervalType, String.class, null);
    // Should contain the interval string representation
    assertEquals(new PGInterval(0, 0, 5, 0, 0, 0).getValue(), result);
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
