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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Unit tests for TimestamptzCodec.
 */
class TimestamptzCodecTest {

  private TimestamptzCodec codec;
  private PgType timestamptzType;
  private CodecContext ctx;
  private CodecContext ctxJavaTime;

  @BeforeEach
  void setUp() {
    codec = TimestamptzCodec.INSTANCE;
    timestamptzType = new PgType(
        new ObjectName("pg_catalog", "timestamptz"),
        "timestamp with time zone",
        1184, // Oid.TIMESTAMPTZ
        'b', 'D', -1, 0, 0, 0
    );

    ctx = TestCodecContext.create();
    ctxJavaTime = TestCodecContext.create(false, false, false, false, true);
  }

  @Test
  void getTypeName() {
    assertEquals("timestamptz", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Timestamp.class, codec.getDefaultJavaType());
  }

  // ==================== decodeText with java.time preference ====================

  @Test
  void decodeText_prefersTimestamp_whenFalse() throws SQLException {
    Object result = codec.decodeText("2024-01-15 10:30:00+00", timestamptzType, ctx);

    assertInstanceOf(Timestamp.class, result);
  }

  @Test
  void decodeText_prefersOffsetDateTime_whenTrue() throws SQLException {
    Object result = codec.decodeText("2024-01-15 10:30:00+00", timestamptzType, ctxJavaTime);

    assertInstanceOf(OffsetDateTime.class, result);
    OffsetDateTime odt = (OffsetDateTime) result;
    assertEquals(2024, odt.getYear());
    assertEquals(1, odt.getMonthValue());
    assertEquals(15, odt.getDayOfMonth());
    assertEquals(10, odt.getHour());
    assertEquals(30, odt.getMinute());
  }

  // ==================== encodeText ====================

  @Test
  void encodeText_fromTimestamp() throws SQLException {
    Timestamp ts = Timestamp.valueOf("2024-01-15 10:30:00");

    String result = codec.encodeText(ts, timestamptzType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_fromOffsetDateTime() throws SQLException {
    OffsetDateTime odt = OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC);

    String result = codec.encodeText(odt, timestamptzType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_fromZonedDateTime() throws SQLException {
    ZonedDateTime zdt = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC);

    String result = codec.encodeText(zdt, timestamptzType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_fromInstant() throws SQLException {
    Instant instant = OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC).toInstant();

    String result = codec.encodeText(instant, timestamptzType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_fromLocalDateTime() throws SQLException {
    LocalDateTime ldt = LocalDateTime.of(2024, 1, 15, 10, 30, 0);

    String result = codec.encodeText(ldt, timestamptzType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_unsupportedType_throws() {
    assertThrows(PSQLException.class, () ->
        codec.encodeText("not a timestamp", timestamptzType, ctx));
  }

  // ==================== decodeTextAs ====================

  @Test
  void decodeTextAs_Timestamp() throws SQLException {
    Timestamp result = codec.decodeTextAs("2024-01-15 10:30:00+00", timestamptzType, Timestamp.class, ctx);

    assertNotNull(result);
  }

  @Test
  void decodeTextAs_OffsetDateTime() throws SQLException {
    OffsetDateTime result = codec.decodeTextAs("2024-01-15 10:30:00+00", timestamptzType, OffsetDateTime.class, ctx);

    assertNotNull(result);
    assertEquals(2024, result.getYear());
    assertEquals(1, result.getMonthValue());
    assertEquals(15, result.getDayOfMonth());
  }

  @Test
  void decodeTextAs_ZonedDateTime() throws SQLException {
    ZonedDateTime result = codec.decodeTextAs("2024-01-15 10:30:00+00", timestamptzType, ZonedDateTime.class, ctx);

    assertNotNull(result);
    assertEquals(2024, result.getYear());
    assertEquals(1, result.getMonthValue());
    assertEquals(15, result.getDayOfMonth());
  }

  @Test
  void decodeTextAs_Instant() throws SQLException {
    Instant result = codec.decodeTextAs("2024-01-15 10:30:00+00", timestamptzType, Instant.class, ctx);

    assertNotNull(result);
    OffsetDateTime odt = codec.decodeTextAs("2024-01-15 10:30:00+00", timestamptzType, OffsetDateTime.class, ctx);
    assertEquals(odt.toInstant(), result);
  }

  @Test
  void decodeTextAs_LocalDateTime() throws SQLException {
    LocalDateTime result = codec.decodeTextAs("2024-01-15 10:30:00+00", timestamptzType, LocalDateTime.class, ctx);

    assertNotNull(result);
    assertEquals(2024, result.getYear());
    assertEquals(1, result.getMonthValue());
    assertEquals(15, result.getDayOfMonth());
  }

  @Test
  void decodeTextAs_LocalDate() throws SQLException {
    LocalDate result = codec.decodeTextAs("2024-01-15 10:30:00+00", timestamptzType, LocalDate.class, ctx);

    assertNotNull(result);
    assertEquals(LocalDate.of(2024, 1, 15), result);
  }

  @Test
  void decodeTextAs_SqlDate() throws SQLException {
    java.sql.Date result = codec.decodeTextAs("2024-01-15 10:30:00+00", timestamptzType, java.sql.Date.class, ctx);

    assertNotNull(result);
  }

  @Test
  void decodeTextAs_Long() throws SQLException {
    Long result = codec.decodeTextAs("2024-01-15 10:30:00+00", timestamptzType, Long.class, ctx);

    assertNotNull(result);
  }

  @Test
  void decodeTextAs_String() throws SQLException {
    String result = codec.decodeTextAs("2024-01-15 10:30:00+00", timestamptzType, String.class, ctx);

    assertEquals("2024-01-15 10:30:00+00", result);
  }

  @Test
  void decodeTextAs_unsupportedType_throws() {
    assertThrows(PSQLException.class, () ->
        codec.decodeTextAs("2024-01-15 10:30:00+00", timestamptzType, Integer.class, ctx));
  }

  // ==================== decodeAsLong ====================

  @Test
  void decodeAsLong_text() throws SQLException {
    long result = codec.decodeAsLong("2024-01-15 10:30:00+00", timestamptzType, ctx);

    // The value is parsed as UTC, so compare with UTC-based instant
    Instant expected = OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC).toInstant();
    assertEquals(expected.toEpochMilli(), result);
  }

  // ==================== decodeAsInt throws ====================

  @Test
  void decodeAsInt_text_throws() {
    assertThrows(PSQLException.class, () ->
        codec.decodeAsInt("2024-01-15 10:30:00+00", timestamptzType, ctx));
  }

  // ==================== Roundtrip ====================

  @Test
  void textRoundtrip_timestamp() throws SQLException {
    Timestamp original = Timestamp.valueOf("2024-06-15 14:30:45.123");
    String encoded = codec.encodeText(original, timestamptzType, ctx);
    Timestamp decoded = codec.decodeTextAs(encoded, timestamptzType, Timestamp.class, ctx);

    assertEquals(original.getTime(), decoded.getTime());
  }

  @Test
  void textRoundtrip_offsetDateTime() throws SQLException {
    OffsetDateTime original = OffsetDateTime.of(2024, 6, 15, 14, 30, 45, 0, ZoneOffset.UTC);
    String encoded = codec.encodeText(original, timestamptzType, ctx);
    OffsetDateTime decoded = codec.decodeTextAs(encoded, timestamptzType, OffsetDateTime.class, ctx);

    assertEquals(original.toInstant(), decoded.toInstant());
  }
}
