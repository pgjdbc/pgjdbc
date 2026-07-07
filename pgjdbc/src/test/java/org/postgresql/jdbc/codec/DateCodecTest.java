/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Unit tests for DateCodec.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>java.time preference support via prefersJavaTimeForDate()</li>
 *   <li>Type conversions (Date, LocalDate)</li>
 *   <li>Text encoding/decoding</li>
 * </ul>
 */
class DateCodecTest {

  private DateCodec codec;
  private PgType dateType;
  private CodecContext ctx;
  private CodecContext ctxJavaTime;

  @BeforeEach
  void setUp() {
    codec = DateCodec.INSTANCE;
    dateType = new PgType(
        new ObjectName("pg_catalog", "date"),
        "date",
        1082, // Oid.DATE
        'b',  // base type
        'D',  // date/time category
        -1,   // typmod
        0,    // typelem
        0,    // typarray
        0     // typbasetype
    );

    ctx = TestCodecContext.create();
    ctxJavaTime = TestCodecContext.create(true, false, false, false, false);
  }

  // ==================== Codec Metadata ====================

  @Test
  void getTypeName() {
    assertEquals("date", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Date.class, codec.getDefaultJavaType());
  }

  // ==================== decodeText with java.time preference ====================

  @Test
  void decodeText_prefersDate_whenFalse() throws SQLException {
    Object result = codec.decodeText("2024-01-15", dateType, ctx);

    assertInstanceOf(Date.class, result);
    Date d = (Date) result;
    assertEquals("2024-01-15", d.toString());
  }

  @Test
  void decodeText_prefersLocalDate_whenTrue() throws SQLException {
    Object result = codec.decodeText("2024-01-15", dateType, ctxJavaTime);

    assertInstanceOf(LocalDate.class, result);
    LocalDate ld = (LocalDate) result;
    assertEquals(2024, ld.getYear());
    assertEquals(1, ld.getMonthValue());
    assertEquals(15, ld.getDayOfMonth());
  }

  // ==================== encodeText ====================

  @Test
  void encodeText_fromDate() throws SQLException {
    Date d = Date.valueOf("2024-01-15");

    String result = codec.encodeText(d, dateType, ctx);

    assertNotNull(result);
    // Should be parseable back
    assertEquals(d, TestCodecContext.timestampUtils().toDate(null, result));
  }

  @Test
  void encodeText_fromLocalDate() throws SQLException {
    LocalDate ld = LocalDate.of(2024, 1, 15);

    String result = codec.encodeText(ld, dateType, ctx);

    assertNotNull(result);
    assertEquals("2024-01-15", result);
  }

  @Test
  void encodeText_fromUtilDate() throws SQLException {
    java.util.Date date = new java.util.Date(Date.valueOf("2024-01-15").getTime());

    String result = codec.encodeText(date, dateType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_unsupportedType_throws() {
    assertThrows(PSQLException.class, () ->
        codec.encodeText("not a date", dateType, ctx));
  }

  // ==================== decodeTextAs ====================

  @Test
  void decodeTextAs_Date() throws SQLException {
    Date result = codec.decodeTextAs("2024-01-15", dateType, Date.class, ctx);

    assertNotNull(result);
    assertEquals("2024-01-15", result.toString());
  }

  @Test
  void decodeTextAs_LocalDate() throws SQLException {
    LocalDate result = codec.decodeTextAs("2024-01-15", dateType, LocalDate.class, ctx);

    assertNotNull(result);
    assertEquals(2024, result.getYear());
    assertEquals(1, result.getMonthValue());
    assertEquals(15, result.getDayOfMonth());
  }

  @Test
  void decodeTextAs_Long() throws SQLException {
    Long result = codec.decodeTextAs("2024-01-15", dateType, Long.class, ctx);

    assertNotNull(result);
    // Should be milliseconds since epoch
    Date d = Date.valueOf("2024-01-15");
    assertEquals(d.getTime(), result);
  }

  @Test
  void decodeTextAs_String() throws SQLException {
    String result = codec.decodeTextAs("2024-01-15", dateType, String.class, ctx);

    assertEquals("2024-01-15", result);
  }

  @Test
  void decodeTextAs_unsupportedType_throws() {
    assertThrows(PSQLException.class, () ->
        codec.decodeTextAs("2024-01-15", dateType, Integer.class, ctx));
  }

  // ==================== decodeAsLong ====================

  @Test
  void decodeAsLong_text_throws() {
    assertThrows(PSQLException.class, () ->
        PrimitiveDecoders.asLong(codec, "2024-01-15", dateType, ctx));
  }

  // ==================== decodeAsInt throws ====================

  @Test
  void decodeAsInt_text_throws() {
    assertThrows(PSQLException.class, () ->
        PrimitiveDecoders.asInt(codec, "2024-01-15", dateType, ctx));
  }

  // ==================== decodeAsDouble ====================

  @Test
  void decodeAsDouble_text_throws() {
    assertThrows(PSQLException.class, () ->
        PrimitiveDecoders.asDouble(codec, "2024-01-15", dateType, ctx));
  }

  // ==================== Roundtrip ====================

  @Test
  void textRoundtrip_date() throws SQLException {
    Date original = Date.valueOf("2024-06-15");
    String encoded = codec.encodeText(original, dateType, ctx);
    Date decoded = codec.decodeTextAs(encoded, dateType, Date.class, ctx);

    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip_localDate() throws SQLException {
    LocalDate original = LocalDate.of(2024, 6, 15);
    String encoded = codec.encodeText(original, dateType, ctxJavaTime);
    LocalDate decoded = codec.decodeTextAs(encoded, dateType, LocalDate.class, ctxJavaTime);

    assertEquals(original, decoded);
  }
}
