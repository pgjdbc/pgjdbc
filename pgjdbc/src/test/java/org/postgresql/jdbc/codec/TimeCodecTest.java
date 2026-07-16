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

import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalTime;

/**
 * Unit tests for TimeCodec.
 */
class TimeCodecTest {

  private TimeCodec codec;
  private PgType timeType;
  private CodecContext ctx;
  private CodecContext ctxJavaTime;

  @BeforeEach
  void setUp() {
    codec = TimeCodec.INSTANCE;
    timeType = new PgType(
        new ObjectName("pg_catalog", "time"),
        "time without time zone",
        1083, // Oid.TIME
        'b', 'D', -1, 0, 0, 0
    );

    ctx = TestCodecContext.create();
    ctxJavaTime = TestCodecContext.create(false, true, false, false, false);
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("time", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Time.class, codec.getDefaultJavaType());
  }

  // ==================== decodeText with java.time preference ====================

  @Test
  void decodeText_prefersTime_whenFalse() throws SQLException {
    Object result = codec.decodeText("10:30:00", timeType, ctx);

    assertInstanceOf(Time.class, result);
  }

  @Test
  void decodeText_prefersLocalTime_whenTrue() throws SQLException {
    Object result = codec.decodeText("10:30:00", timeType, ctxJavaTime);

    assertInstanceOf(LocalTime.class, result);
    LocalTime lt = (LocalTime) result;
    assertEquals(10, lt.getHour());
    assertEquals(30, lt.getMinute());
    assertEquals(0, lt.getSecond());
  }

  // ==================== encodeText ====================

  @Test
  void encodeText_fromTime() throws SQLException {
    Time t = Time.valueOf("10:30:00");

    String result = codec.encodeText(t, timeType, ctx);

    assertNotNull(result);
  }

  @Test
  void encodeText_fromLocalTime() throws SQLException {
    LocalTime lt = LocalTime.of(10, 30, 0);

    String result = codec.encodeText(lt, timeType, ctx);

    assertNotNull(result);
    assertEquals(lt, TestCodecContext.timestampUtils().toLocalTime(result));
  }

  @Test
  void encodeText_unsupportedType_throws() {
    assertThrows(PSQLException.class, () ->
        codec.encodeText("not a time", timeType, ctx));
  }

  // ==================== decodeTextAs ====================

  @Test
  void decodeTextAs_Time() throws SQLException {
    Time result = codec.decodeTextAs("10:30:00", timeType, Time.class, ctx);

    assertNotNull(result);
  }

  @Test
  void decodeTextAs_LocalTime() throws SQLException {
    LocalTime result = codec.decodeTextAs("10:30:00", timeType, LocalTime.class, ctx);

    assertNotNull(result);
    assertEquals(10, result.getHour());
    assertEquals(30, result.getMinute());
    assertEquals(0, result.getSecond());
  }

  @Test
  void decodeTextAs_Long() throws SQLException {
    Long result = codec.decodeTextAs("10:30:00", timeType, Long.class, ctx);

    assertNotNull(result);
  }

  @Test
  void decodeTextAs_String() throws SQLException {
    String result = codec.decodeTextAs("10:30:00", timeType, String.class, ctx);

    assertEquals("10:30:00", result);
  }

  @Test
  void decodeTextAs_unsupportedType_throws() {
    assertThrows(PSQLException.class, () ->
        codec.decodeTextAs("10:30:00", timeType, Integer.class, ctx));
  }

  // ==================== decodeAsInt throws ====================

  @Test
  void decodeAsInt_text_throws() {
    assertThrows(PSQLException.class, () ->
        PrimitiveDecoders.asInt(codec, "10:30:00", timeType, ctx));
  }

  // ==================== Roundtrip ====================

  @Test
  void textRoundtrip_time() throws SQLException {
    Time original = Time.valueOf("14:30:45");
    String encoded = codec.encodeText(original, timeType, ctx);
    Time decoded = codec.decodeTextAs(encoded, timeType, Time.class, ctx);

    assertEquals(original.toString(), decoded.toString());
  }

  @Test
  void textRoundtrip_localTime() throws SQLException {
    LocalTime original = LocalTime.of(14, 30, 45);
    String encoded = codec.encodeText(original, timeType, ctx);
    LocalTime decoded = codec.decodeTextAs(encoded, timeType, LocalTime.class, ctx);

    assertEquals(original, decoded);
  }
}
