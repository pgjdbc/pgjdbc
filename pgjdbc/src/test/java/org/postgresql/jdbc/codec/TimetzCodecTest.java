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
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

/**
 * Unit tests for TimetzCodec.
 */
class TimetzCodecTest {

  private TimetzCodec codec;
  private PgType timetzType;
  private CodecContext ctx;
  private CodecContext ctxJavaTime;

  @BeforeEach
  void setUp() {
    codec = TimetzCodec.INSTANCE;
    timetzType = new PgType(
        new ObjectName("pg_catalog", "timetz"),
        "time with time zone",
        1266, // Oid.TIMETZ
        'b', 'D', -1, 0, 0, 0
    );

    ctx = TestCodecContext.create();
    ctxJavaTime = TestCodecContext.create(false, false, true, false, false);
  }

  @Test
  void getTypeName() {
    assertEquals("timetz", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Time.class, codec.getDefaultJavaType());
  }

  // ==================== decodeText with java.time preference ====================

  @Test
  void decodeText_prefersTime_whenFalse() throws SQLException {
    Object result = codec.decodeText("10:30:00+00", timetzType, ctx);

    assertInstanceOf(Time.class, result);
  }

  @Test
  void decodeText_prefersOffsetTime_whenTrue() throws SQLException {
    Object result = codec.decodeText("10:30:00+00", timetzType, ctxJavaTime);

    assertInstanceOf(OffsetTime.class, result);
    OffsetTime ot = (OffsetTime) result;
    assertEquals(10, ot.getHour());
    assertEquals(30, ot.getMinute());
    assertEquals(0, ot.getSecond());
  }

  // ==================== encodeText ====================

  @Test
  void encodeText_fromTime() throws SQLException {
    Time t = Time.valueOf("10:30:00");

    String result = codec.encodeText(t, timetzType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_fromOffsetTime() throws SQLException {
    OffsetTime ot = OffsetTime.of(10, 30, 0, 0, ZoneOffset.UTC);

    String result = codec.encodeText(ot, timetzType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_fromLocalTime() throws SQLException {
    LocalTime lt = LocalTime.of(10, 30, 0);

    String result = codec.encodeText(lt, timetzType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_unsupportedType_throws() {
    assertThrows(PSQLException.class, () ->
        codec.encodeText("not a time", timetzType, ctx));
  }

  // ==================== decodeTextAs ====================

  @Test
  void decodeTextAs_Time() throws SQLException {
    Time result = codec.decodeTextAs("10:30:00+00", timetzType, Time.class, ctx);

    assertNotNull(result);
  }

  @Test
  void decodeTextAs_OffsetTime() throws SQLException {
    OffsetTime result = codec.decodeTextAs("10:30:00+00", timetzType, OffsetTime.class, ctx);

    assertNotNull(result);
    assertEquals(10, result.getHour());
    assertEquals(30, result.getMinute());
  }

  @Test
  void decodeTextAs_LocalTime() throws SQLException {
    LocalTime result = codec.decodeTextAs("10:30:00+00", timetzType, LocalTime.class, ctx);

    assertNotNull(result);
    assertEquals(10, result.getHour());
    assertEquals(30, result.getMinute());
  }

  @Test
  void decodeTextAs_OffsetDateTime() throws SQLException {
    OffsetDateTime result = codec.decodeTextAs("10:30:00+00", timetzType, OffsetDateTime.class, ctx);

    assertNotNull(result);
    // Should use epoch date (1970-01-01)
    assertEquals(LocalDate.ofEpochDay(0), result.toLocalDate());
    assertEquals(10, result.getHour());
    assertEquals(30, result.getMinute());
  }

  @Test
  void decodeTextAs_Long() throws SQLException {
    Long result = codec.decodeTextAs("10:30:00+00", timetzType, Long.class, ctx);

    assertNotNull(result);
  }

  @Test
  void decodeTextAs_String() throws SQLException {
    String result = codec.decodeTextAs("10:30:00+00", timetzType, String.class, ctx);

    assertEquals("10:30:00+00", result);
  }

  @Test
  void decodeTextAs_unsupportedType_throws() {
    assertThrows(PSQLException.class, () ->
        codec.decodeTextAs("10:30:00+00", timetzType, Integer.class, ctx));
  }

  // ==================== decodeAsInt throws ====================

  @Test
  void decodeAsInt_text_throws() {
    assertThrows(PSQLException.class, () ->
        codec.decodeAsInt("10:30:00+00", timetzType, ctx));
  }

  // ==================== Roundtrip ====================

  @Test
  void textRoundtrip_time() throws SQLException {
    Time original = Time.valueOf("14:30:45");
    String encoded = codec.encodeText(original, timetzType, ctx);
    Time decoded = codec.decodeTextAs(encoded, timetzType, Time.class, ctx);

    assertEquals(original.toString(), decoded.toString());
  }
}
