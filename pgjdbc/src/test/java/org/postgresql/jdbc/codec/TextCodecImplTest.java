/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

class TextCodecImplTest {

  private TextCodecImpl codec;
  private PgType textType;
  private CodecContext ctx;

  @BeforeEach
  void setUp() {
    codec = TextCodecImpl.INSTANCE;
    textType = new PgType(
        new ObjectName("pg_catalog", "text"),
        "text",
        Oid.TEXT,
        'b', 'S', -1, 0, 0, 0
    );
    ctx = TestCodecContext.create();
  }

  @Test
  void getTypeName() {
    assertEquals("text", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(String.class, codec.getDefaultJavaType());
  }

  // ==================== Text Decoding ====================

  @Test
  void decodeText_returnsString() throws SQLException {
    assertEquals("hello", codec.decodeText("hello", textType, ctx));
  }

  @Test
  void decodeText_emptyString() throws SQLException {
    assertEquals("", codec.decodeText("", textType, ctx));
  }

  // ==================== Binary Decoding ====================

  @Test
  void decodeBinary_utf8() throws SQLException {
    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertEquals("hello", codec.decodeBinary(data, textType, ctx));
  }

  @Test
  void decodeBinary_unicode() throws SQLException {
    String unicode = "\u00e9\u00e8\u00ea"; // accented chars
    byte[] data = unicode.getBytes(StandardCharsets.UTF_8);
    assertEquals(unicode, codec.decodeBinary(data, textType, ctx));
  }

  // ==================== Encoding ====================

  @Test
  void encodeText_string() throws SQLException {
    assertEquals("hello", codec.encodeText("hello", textType, ctx));
  }

  @Test
  void encodeText_number() throws SQLException {
    assertEquals("42", codec.encodeText(42, textType, ctx));
  }

  @Test
  void encodeText_boolean() throws SQLException {
    assertEquals("true", codec.encodeText(true, textType, ctx));
  }

  @Test
  void encodeBinary_string() throws SQLException {
    byte[] result = codec.encodeBinary("hello", textType, ctx);
    assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), result);
  }

  // ==================== Type Conversions ====================

  @Test
  void decodeAsInt_text() throws SQLException {
    assertEquals(42, codec.decodeAsInt("42", textType, ctx));
  }

  @Test
  void decodeAsInt_text_invalid() {
    assertThrows(PSQLException.class, () -> codec.decodeAsInt("abc", textType, ctx));
  }

  @Test
  void decodeAsLong_text() throws SQLException {
    assertEquals(123456789L, codec.decodeAsLong("123456789", textType, ctx));
  }

  @Test
  void decodeAsDouble_text() throws SQLException {
    assertEquals(3.14, codec.decodeAsDouble("3.14", textType, ctx), 0.001);
  }

  @Test
  void decodeAsFloat_text() throws SQLException {
    assertEquals(3.14f, codec.decodeAsFloat("3.14", textType, ctx), 0.001f);
  }

  @Test
  void decodeAsBigDecimal_text() throws SQLException {
    byte[] data = "3.14".getBytes(StandardCharsets.UTF_8);
    assertEquals(new BigDecimal("3.14"), codec.decodeAsBigDecimal(data, textType, ctx));
  }

  @Test
  void decodeAsBoolean_true() throws SQLException {
    assertTrue(codec.decodeAsBoolean("true", textType, ctx));
    assertTrue(codec.decodeAsBoolean("t", textType, ctx));
    assertTrue(codec.decodeAsBoolean("1", textType, ctx));
    assertTrue(codec.decodeAsBoolean("yes", textType, ctx));
  }

  @Test
  void decodeAsBoolean_false() throws SQLException {
    assertFalse(codec.decodeAsBoolean("false", textType, ctx));
    assertFalse(codec.decodeAsBoolean("f", textType, ctx));
    assertFalse(codec.decodeAsBoolean("0", textType, ctx));
    assertFalse(codec.decodeAsBoolean("no", textType, ctx));
  }

  // ==================== decodeBinaryAs/decodeTextAs ====================

  @Test
  void decodeBinaryAs_String() throws SQLException {
    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertEquals("hello", codec.decodeBinaryAs(data, textType, String.class, ctx));
  }

  @Test
  void decodeBinaryAs_Integer() throws SQLException {
    byte[] data = "42".getBytes(StandardCharsets.UTF_8);
    assertEquals(Integer.valueOf(42), codec.decodeBinaryAs(data, textType, Integer.class, ctx));
  }

  @Test
  void decodeBinaryAs_Long() throws SQLException {
    byte[] data = "42".getBytes(StandardCharsets.UTF_8);
    assertEquals(Long.valueOf(42), codec.decodeBinaryAs(data, textType, Long.class, ctx));
  }

  @Test
  void decodeBinaryAs_Double() throws SQLException {
    byte[] data = "3.14".getBytes(StandardCharsets.UTF_8);
    assertEquals(Double.valueOf(3.14), codec.decodeBinaryAs(data, textType, Double.class, ctx));
  }

  @Test
  void decodeBinaryAs_Boolean() throws SQLException {
    byte[] data = "true".getBytes(StandardCharsets.UTF_8);
    assertEquals(Boolean.TRUE, codec.decodeBinaryAs(data, textType, Boolean.class, ctx));
  }

  @Test
  void decodeBinaryAs_Short() throws SQLException {
    byte[] data = "42".getBytes(StandardCharsets.UTF_8);
    assertEquals(Short.valueOf((short) 42), codec.decodeBinaryAs(data, textType, Short.class, ctx));
  }

  @Test
  void decodeBinaryAs_Byte() throws SQLException {
    byte[] data = "42".getBytes(StandardCharsets.UTF_8);
    assertEquals(Byte.valueOf((byte) 42), codec.decodeBinaryAs(data, textType, Byte.class, ctx));
  }

  @Test
  void decodeBinaryAs_unsupported() {
    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, textType, java.util.Date.class, ctx));
  }

  @Test
  void decodeBinaryAs_Date() throws SQLException {
    byte[] data = "2024-01-02".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        org.postgresql.jdbc.TemporalCodecs.decodeDateText("2024-01-02", ctx),
        codec.decodeBinaryAs(data, textType, java.sql.Date.class, ctx));
  }

  @Test
  void decodeTextAs_Date() throws SQLException {
    assertEquals(
        org.postgresql.jdbc.TemporalCodecs.decodeDateText("2024-01-02", ctx),
        codec.decodeTextAs("2024-01-02", textType, java.sql.Date.class, ctx));
  }

  @Test
  void decodeTextAs_Time() throws SQLException {
    assertEquals(
        org.postgresql.jdbc.TemporalCodecs.decodeTimeText("12:34:56", ctx),
        codec.decodeTextAs("12:34:56", textType, java.sql.Time.class, ctx));
  }

  @Test
  void decodeTextAs_Timestamp() throws SQLException {
    assertEquals(
        org.postgresql.jdbc.TemporalCodecs.decodeTimestampText("2024-01-02 12:34:56", ctx),
        codec.decodeTextAs("2024-01-02 12:34:56", textType, java.sql.Timestamp.class, ctx));
  }

  @Test
  void decodeTextAs_Timestamp_invalid() {
    assertThrows(PSQLException.class,
        () -> codec.decodeTextAs("this is not a timestamp", textType, java.sql.Timestamp.class, ctx));
  }

  // ==================== Roundtrip ====================

  @Test
  void textRoundtrip() throws SQLException {
    String original = "Hello, World!";
    String encoded = codec.encodeText(original, textType, ctx);
    Object decoded = codec.decodeText(encoded, textType, ctx);
    assertEquals(original, decoded);
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    String original = "Hello, World!";
    byte[] encoded = codec.encodeBinary(original, textType, ctx);
    Object decoded = codec.decodeBinary(encoded, textType, ctx);
    assertEquals(original, decoded);
  }
}
