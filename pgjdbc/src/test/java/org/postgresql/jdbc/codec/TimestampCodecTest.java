/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * Unit tests for TimestampCodec.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>java.time preference support via prefersJavaTimeForTimestamp()</li>
 *   <li>Type conversions (Timestamp, LocalDateTime)</li>
 *   <li>Text encoding/decoding</li>
 * </ul>
 */
class TimestampCodecTest {

  private TimestampCodec codec;
  private PgType timestampType;
  private CodecContext ctx;
  private CodecContext ctxJavaTime;

  @BeforeEach
  void setUp() {
    codec = TimestampCodec.INSTANCE;
    timestampType = new PgType(
        new ObjectName("pg_catalog", "timestamp"),
        "timestamp without time zone",
        1114, // Oid.TIMESTAMP
        'b',  // base type
        'D',  // date/time category
        -1,   // typmod
        0,    // typelem
        0,    // typarray
        0     // typbasetype
    );

    ctx = TestCodecContext.create();
    ctxJavaTime = TestCodecContext.create(false, false, false, true, false);
  }

  // ==================== Codec Metadata ====================

  @Test
  void getTypeName() {
    assertEquals("timestamp", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Timestamp.class, codec.getDefaultJavaType());
  }

  // ==================== decodeText with java.time preference ====================

  @Test
  void decodeText_prefersTimestamp_whenFalse() throws SQLException {
    Object result = codec.decodeText("2024-01-15 10:30:00", timestampType, ctx);

    assertInstanceOf(Timestamp.class, result);
    Timestamp ts = (Timestamp) result;
    assertEquals("2024-01-15 10:30:00.0", ts.toString());
  }

  @Test
  void decodeText_prefersLocalDateTime_whenTrue() throws SQLException {
    Object result = codec.decodeText("2024-01-15 10:30:00", timestampType, ctxJavaTime);

    assertInstanceOf(LocalDateTime.class, result);
    LocalDateTime ldt = (LocalDateTime) result;
    assertEquals(2024, ldt.getYear());
    assertEquals(1, ldt.getMonthValue());
    assertEquals(15, ldt.getDayOfMonth());
    assertEquals(10, ldt.getHour());
    assertEquals(30, ldt.getMinute());
    assertEquals(0, ldt.getSecond());
  }

  // ==================== encodeText ====================

  @Test
  void encodeText_fromTimestamp() throws SQLException {
    Timestamp ts = Timestamp.valueOf("2024-01-15 10:30:00");

    String result = codec.encodeText(ts, timestampType, ctx);

    assertNotNull(result);
    // Result should be parseable back
    assertEquals(ts, TestCodecContext.timestampUtils().toTimestamp(null, result));
  }

  @Test
  void encodeText_fromLocalDateTime() throws SQLException {
    LocalDateTime ldt = LocalDateTime.of(2024, 1, 15, 10, 30, 0);

    String result = codec.encodeText(ldt, timestampType, ctx);

    assertNotNull(result);
    // Should contain the date and time
    assertEquals(ldt, TestCodecContext.timestampUtils().toLocalDateTime(result));
  }

  @Test
  void encodeText_fromUtilDate() throws SQLException {
    java.util.Date date = new java.util.Date(Timestamp.valueOf("2024-01-15 10:30:00").getTime());

    String result = codec.encodeText(date, timestampType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_fromOffsetDateTime() throws SQLException {
    OffsetDateTime odt = OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, java.time.ZoneOffset.UTC);

    String result = codec.encodeText(odt, timestampType, ctx);

    assertNotNull(result);
    // Should encode the local date/time part
    LocalDateTime decoded = TestCodecContext.timestampUtils().toLocalDateTime(result);
    assertEquals(LocalDateTime.of(2024, 1, 15, 10, 30, 0), decoded);
  }

  @Test
  void encodeText_fromZonedDateTime() throws SQLException {
    ZonedDateTime zdt = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, java.time.ZoneOffset.UTC);

    String result = codec.encodeText(zdt, timestampType, ctx);

    assertNotNull(result);
    LocalDateTime decoded = TestCodecContext.timestampUtils().toLocalDateTime(result);
    assertEquals(LocalDateTime.of(2024, 1, 15, 10, 30, 0), decoded);
  }

  @Test
  void encodeText_fromInstant() throws SQLException {
    Instant instant = LocalDateTime.of(2024, 1, 15, 10, 30, 0).toInstant(java.time.ZoneOffset.UTC);

    String result = codec.encodeText(instant, timestampType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_unsupportedType_throws() {
    assertThrows(PSQLException.class, () ->
        codec.encodeText("not a timestamp", timestampType, ctx));
  }

  // ==================== decodeBinaryAs ====================

  @Test
  void decodeTextAs_Timestamp() throws SQLException {
    Timestamp result = codec.decodeTextAs("2024-01-15 10:30:00", timestampType, Timestamp.class, ctx);

    assertNotNull(result);
    assertEquals("2024-01-15 10:30:00.0", result.toString());
  }

  @Test
  void decodeTextAs_LocalDateTime() throws SQLException {
    LocalDateTime result = codec.decodeTextAs("2024-01-15 10:30:00", timestampType, LocalDateTime.class, ctx);

    assertNotNull(result);
    assertEquals(2024, result.getYear());
    assertEquals(1, result.getMonthValue());
    assertEquals(15, result.getDayOfMonth());
    assertEquals(10, result.getHour());
    assertEquals(30, result.getMinute());
  }

  @Test
  void decodeTextAs_Long() throws SQLException {
    Long result = codec.decodeTextAs("2024-01-15 10:30:00", timestampType, Long.class, ctx);

    assertNotNull(result);
    // Should be milliseconds since epoch
    Timestamp ts = Timestamp.valueOf("2024-01-15 10:30:00");
    assertEquals(ts.getTime(), result);
  }

  @Test
  void decodeTextAs_String() throws SQLException {
    String result = codec.decodeTextAs("2024-01-15 10:30:00", timestampType, String.class, ctx);

    assertEquals("2024-01-15 10:30:00", result);
  }

  @Test
  void decodeTextAs_LocalDate() throws SQLException {
    LocalDate result = codec.decodeTextAs("2024-01-15 10:30:00", timestampType, LocalDate.class, ctx);

    assertNotNull(result);
    assertEquals(LocalDate.of(2024, 1, 15), result);
  }

  @Test
  void decodeTextAs_OffsetDateTime() throws SQLException {
    OffsetDateTime result = codec.decodeTextAs("2024-01-15 10:30:00", timestampType, OffsetDateTime.class, ctx);

    assertNotNull(result);
    assertEquals(2024, result.getYear());
    assertEquals(1, result.getMonthValue());
    assertEquals(15, result.getDayOfMonth());
    assertEquals(10, result.getHour());
    assertEquals(30, result.getMinute());
  }

  @Test
  void decodeTextAs_ZonedDateTime() throws SQLException {
    ZonedDateTime result = codec.decodeTextAs("2024-01-15 10:30:00", timestampType, ZonedDateTime.class, ctx);

    assertNotNull(result);
    assertEquals(2024, result.getYear());
    assertEquals(1, result.getMonthValue());
    assertEquals(15, result.getDayOfMonth());
    assertEquals(10, result.getHour());
  }

  @Test
  void decodeTextAs_Instant() throws SQLException {
    Instant result = codec.decodeTextAs("2024-01-15 10:30:00", timestampType, Instant.class, ctx);

    assertNotNull(result);
    // Instant should represent the same point in time
    OffsetDateTime odt = codec.decodeTextAs("2024-01-15 10:30:00", timestampType, OffsetDateTime.class, ctx);
    assertEquals(odt.toInstant(), result);
  }

  @Test
  void decodeTextAs_SqlDate() throws SQLException {
    java.sql.Date result = codec.decodeTextAs("2024-01-15 10:30:00", timestampType, java.sql.Date.class, ctx);

    assertNotNull(result);
  }

  @Test
  void decodeTextAs_unsupportedType_throws() {
    assertThrows(PSQLException.class, () ->
        codec.decodeTextAs("2024-01-15 10:30:00", timestampType, Integer.class, ctx));
  }

  // ==================== decodeAsLong ====================

  @Test
  void decodeAsLong_text() throws SQLException {
    long result = codec.decodeAsLong("2024-01-15 10:30:00", timestampType, ctx);

    Timestamp ts = Timestamp.valueOf("2024-01-15 10:30:00");
    assertEquals(ts.getTime(), result);
  }

  // ==================== decodeAsInt throws ====================

  @Test
  void decodeAsInt_text_throws() {
    assertThrows(PSQLException.class, () ->
        codec.decodeAsInt("2024-01-15 10:30:00", timestampType, ctx));
  }

  // ==================== decodeAsDouble ====================

  @Test
  void decodeAsDouble_text() throws SQLException {
    double result = codec.decodeAsDouble("2024-01-15 10:30:00", timestampType, ctx);

    Timestamp ts = Timestamp.valueOf("2024-01-15 10:30:00");
    assertEquals((double) ts.getTime(), result);
  }

  // ==================== Roundtrip ====================

  @Test
  void textRoundtrip_timestamp() throws SQLException {
    Timestamp original = Timestamp.valueOf("2024-06-15 14:30:45.123");
    String encoded = codec.encodeText(original, timestampType, ctx);
    Timestamp decoded = codec.decodeTextAs(encoded, timestampType, Timestamp.class, ctx);

    // Compare time values (may differ in nanos representation)
    assertEquals(original.getTime(), decoded.getTime());
  }

  @Test
  void textRoundtrip_localDateTime() throws SQLException {
    LocalDateTime original = LocalDateTime.of(2024, 6, 15, 14, 30, 45);
    String encoded = codec.encodeText(original, timestampType, ctxJavaTime);
    LocalDateTime decoded = codec.decodeTextAs(encoded, timestampType, LocalDateTime.class, ctxJavaTime);

    assertEquals(original, decoded);
  }
}
