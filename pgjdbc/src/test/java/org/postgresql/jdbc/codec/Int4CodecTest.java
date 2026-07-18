/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CharArraySequence;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * Unit tests for Int4Codec.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Binary decode/encode with positive, negative, zero, max, min values</li>
 *   <li>Text decode/encode with overflow handling</li>
 *   <li>Type conversions (decodeAsInt, decodeAsLong, etc.)</li>
 *   <li>Roundtrip encoding/decoding</li>
 * </ul>
 */
class Int4CodecTest {

  private Int4Codec codec;
  private PgType int4Type;

  @BeforeEach
  void setUp() {
    codec = Int4Codec.INSTANCE;
    // Create a minimal PgType for int4
    int4Type = new PgType(
        new ObjectName("pg_catalog", "int4"),
        "integer",
        Oid.INT4,
        'b', // base type
        'N', // numeric category
        -1,  // typmod
        0,   // typelem
        0,   // typarray
        0    // typbasetype
    );
  }

  // ==================== Binary Decoding ====================

  @Test
  void decodeBinary_positiveValue() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);

    Object result = codec.decodeBinary(data, 0, data.length, int4Type, null);
    assertEquals(42, result);
  }

  @Test
  void decodeBinary_negativeValue() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, -42);

    Object result = codec.decodeBinary(data, 0, data.length, int4Type, null);
    assertEquals(-42, result);
  }

  @Test
  void decodeBinary_zero() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 0);

    Object result = codec.decodeBinary(data, 0, data.length, int4Type, null);
    assertEquals(0, result);
  }

  @Test
  void decodeBinary_maxValue() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, Integer.MAX_VALUE);

    Object result = codec.decodeBinary(data, 0, data.length, int4Type, null);
    assertEquals(Integer.MAX_VALUE, result);
  }

  @Test
  void decodeBinary_minValue() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, Integer.MIN_VALUE);

    Object result = codec.decodeBinary(data, 0, data.length, int4Type, null);
    assertEquals(Integer.MIN_VALUE, result);
  }

  // ==================== Text Decoding ====================

  @Test
  void decodeText_positiveValue() throws SQLException {
    Object result = codec.decodeText("42", int4Type, null);
    assertEquals(42, result);
  }

  @Test
  void decodeAsInt_charSlice() throws SQLException {
    // The fast path reads the digits off the slice with no String and no box.
    char[] buf = "x-42y".toCharArray();
    assertEquals(-42, codec.decodeAsInt(new CharArraySequence(buf, 1, 3), int4Type, null));
    // A leading '+' is rejected by the fast path and handled by the String fallback.
    char[] plus = "+7".toCharArray();
    assertEquals(7, codec.decodeAsInt(new CharArraySequence(plus, 0, plus.length), int4Type, null));
    // Out of int4 range surfaces the same error as the String form.
    char[] overflow = "99999999999".toCharArray();
    assertThrows(PSQLException.class,
        () -> codec.decodeAsInt(new CharArraySequence(overflow, 0, overflow.length), int4Type, null));
  }

  @Test
  void decodeText_negativeValue() throws SQLException {
    Object result = codec.decodeText("-42", int4Type, null);
    assertEquals(-42, result);
  }

  @Test
  void decodeText_zero() throws SQLException {
    Object result = codec.decodeText("0", int4Type, null);
    assertEquals(0, result);
  }

  @Test
  void decodeText_maxValue() throws SQLException {
    Object result = codec.decodeText(String.valueOf(Integer.MAX_VALUE), int4Type, null);
    assertEquals(Integer.MAX_VALUE, result);
  }

  @Test
  void decodeText_minValue() throws SQLException {
    Object result = codec.decodeText(String.valueOf(Integer.MIN_VALUE), int4Type, null);
    assertEquals(Integer.MIN_VALUE, result);
  }

  @Test
  void decodeText_overflow_positive() {
    // One more than MAX_VALUE
    String overflow = "2147483648";
    assertThrows(PSQLException.class, () -> codec.decodeText(overflow, int4Type, null));
  }

  @Test
  void decodeText_overflow_negative() {
    // One less than MIN_VALUE
    String overflow = "-2147483649";
    assertThrows(PSQLException.class, () -> codec.decodeText(overflow, int4Type, null));
  }

  // ==================== Text-as-bytes Decoding ====================

  // decodeTextBytesAsInt reads the ASCII-number compatibility off the context's charset rather than
  // a connection Encoding, so it works through any CodecContext. TestCodecContext has no backing
  // Encoding, which is exactly the case the earlier PgCodecContext downcast could not serve.

  @Test
  void decodeTextBytesAsInt_fastPath() throws SQLException {
    CodecContext ctx = TestCodecContext.create();
    assertEquals(42, codec.decodeTextBytesAsInt("42".getBytes(StandardCharsets.US_ASCII), int4Type, ctx));
    assertEquals(-42, codec.decodeTextBytesAsInt("-42".getBytes(StandardCharsets.US_ASCII), int4Type, ctx));
    assertEquals(0, codec.decodeTextBytesAsInt("0".getBytes(StandardCharsets.US_ASCII), int4Type, ctx));
    assertEquals(Integer.MAX_VALUE,
        codec.decodeTextBytesAsInt(String.valueOf(Integer.MAX_VALUE).getBytes(StandardCharsets.US_ASCII), int4Type, ctx));
    assertEquals(Integer.MIN_VALUE,
        codec.decodeTextBytesAsInt(String.valueOf(Integer.MIN_VALUE).getBytes(StandardCharsets.US_ASCII), int4Type, ctx));
  }

  @Test
  void decodeTextBytesAsInt_fallbackForFastPathReject() throws SQLException {
    // Surrounding whitespace makes the byte fast path reject, exercising the String fallback.
    CodecContext ctx = TestCodecContext.create();
    assertEquals(42, codec.decodeTextBytesAsInt(" 42 ".getBytes(StandardCharsets.US_ASCII), int4Type, ctx));
  }

  @Test
  void decodeTextBytesAsLong_fastPath() throws SQLException {
    CodecContext ctx = TestCodecContext.create();
    assertEquals(42L, codec.decodeTextBytesAsLong("42".getBytes(StandardCharsets.US_ASCII), int4Type, ctx));
    assertEquals(-42L, codec.decodeTextBytesAsLong("-42".getBytes(StandardCharsets.US_ASCII), int4Type, ctx));
  }

  // ==================== Binary Encoding ====================

  @Test
  void encodeBinary_positiveValue() throws SQLException {
    byte[] result = codec.encodeBinary(42, int4Type, null);

    byte[] expected = new byte[4];
    ByteConverter.int4(expected, 0, 42);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeBinary_negativeValue() throws SQLException {
    byte[] result = codec.encodeBinary(-42, int4Type, null);

    byte[] expected = new byte[4];
    ByteConverter.int4(expected, 0, -42);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeBinary_fromLong() throws SQLException {
    byte[] result = codec.encodeBinary(42L, int4Type, null);

    byte[] expected = new byte[4];
    ByteConverter.int4(expected, 0, 42);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeBinary_fromString() throws SQLException {
    byte[] result = codec.encodeBinary("42", int4Type, null);

    byte[] expected = new byte[4];
    ByteConverter.int4(expected, 0, 42);
    assertArrayEquals(expected, result);
  }

  // ==================== Text Encoding ====================

  @Test
  void encodeText_positiveValue() throws SQLException {
    String result = codec.encodeText(42, int4Type, null);
    assertEquals("42", result);
  }

  @Test
  void encodeText_negativeValue() throws SQLException {
    String result = codec.encodeText(-42, int4Type, null);
    assertEquals("-42", result);
  }

  @Test
  void encodeText_zero() throws SQLException {
    String result = codec.encodeText(0, int4Type, null);
    assertEquals("0", result);
  }

  // ==================== Type Conversions ====================

  @Test
  void decodeAsInt_binary() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);

    int result = PrimitiveDecoders.asInt(codec, data, int4Type, null);
    assertEquals(42, result);
  }

  @Test
  void decodeAsInt_text() throws SQLException {
    int result = codec.decodeAsInt("42", int4Type, null);
    assertEquals(42, result);
  }

  @Test
  void decodeAsLong_binary() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);

    long result = PrimitiveDecoders.asLong(codec, data, int4Type, null);
    assertEquals(42L, result);
  }

  @Test
  void decodeAsLong_text() throws SQLException {
    long result = codec.decodeAsLong("42", int4Type, null);
    assertEquals(42L, result);
  }

  @Test
  void decodeAsDouble_binary() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);

    double result = PrimitiveDecoders.asDouble(codec, data, int4Type, null);
    assertEquals(42.0, result);
  }

  @Test
  void decodeAsDouble_text() throws SQLException {
    double result = codec.decodeAsDouble("42", int4Type, null);
    assertEquals(42.0, result);
  }

  @Test
  void decodeAsBigDecimal_binary() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);

    BigDecimal result = codec.decodeAsBigDecimal(data, 0, data.length, int4Type, null);
    assertEquals(BigDecimal.valueOf(42), result);
  }

  @Test
  void decodeAsBigDecimal_text() throws SQLException {
    BigDecimal result = codec.decodeAsBigDecimal("42", int4Type, null);
    assertEquals(BigDecimal.valueOf(42), result);
  }

  @Test
  void decodeAsString_binary() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);

    String result = codec.decodeAsString(data, 0, data.length, int4Type, null);
    assertEquals("42", result);
  }

  @Test
  void decodeAsString_text() throws SQLException {
    String result = codec.decodeAsString("42", int4Type, null);
    assertEquals("42", result);
  }

  // ==================== decodeBinaryAs/decodeTextAs ====================

  @Test
  void decodeBinaryAs_Integer() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);

    Integer result = codec.decodeBinaryAs(data, 0, data.length, int4Type, Integer.class, null);
    assertEquals(Integer.valueOf(42), result);
  }

  @Test
  void decodeBinaryAs_Long() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);

    Long result = codec.decodeBinaryAs(data, 0, data.length, int4Type, Long.class, null);
    assertEquals(Long.valueOf(42), result);
  }

  @Test
  void decodeBinaryAs_String() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);

    String result = codec.decodeBinaryAs(data, 0, data.length, int4Type, String.class, null);
    assertEquals("42", result);
  }

  @Test
  void decodeTextAs_Integer() throws SQLException {
    Integer result = codec.decodeTextAs("42", int4Type, Integer.class, null);
    assertEquals(Integer.valueOf(42), result);
  }

  @Test
  void decodeTextAs_Long() throws SQLException {
    Long result = codec.decodeTextAs("42", int4Type, Long.class, null);
    assertEquals(Long.valueOf(42), result);
  }

  @Test
  void decodeTextAs_BigDecimal() throws SQLException {
    BigDecimal result = codec.decodeTextAs("42", int4Type, BigDecimal.class, null);
    assertEquals(BigDecimal.valueOf(42), result);
  }

  // ==================== Roundtrip Tests ====================

  @Test
  void binaryRoundtrip_positiveValue() throws SQLException {
    int original = 12345;
    byte[] encoded = codec.encodeBinary(original, int4Type, null);
    int decoded = PrimitiveDecoders.asInt(codec, encoded, int4Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void binaryRoundtrip_negativeValue() throws SQLException {
    int original = -12345;
    byte[] encoded = codec.encodeBinary(original, int4Type, null);
    int decoded = PrimitiveDecoders.asInt(codec, encoded, int4Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void binaryRoundtrip_maxValue() throws SQLException {
    int original = Integer.MAX_VALUE;
    byte[] encoded = codec.encodeBinary(original, int4Type, null);
    int decoded = PrimitiveDecoders.asInt(codec, encoded, int4Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void binaryRoundtrip_minValue() throws SQLException {
    int original = Integer.MIN_VALUE;
    byte[] encoded = codec.encodeBinary(original, int4Type, null);
    int decoded = PrimitiveDecoders.asInt(codec, encoded, int4Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip_positiveValue() throws SQLException {
    int original = 12345;
    String encoded = codec.encodeText(original, int4Type, null);
    int decoded = codec.decodeAsInt(encoded, int4Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip_negativeValue() throws SQLException {
    int original = -12345;
    String encoded = codec.encodeText(original, int4Type, null);
    int decoded = codec.decodeAsInt(encoded, int4Type, null);
    assertEquals(original, decoded);
  }

  // ==================== Codec Metadata ====================

  @Test
  void getPrimaryTypeName() {
    assertEquals("int4", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Integer.class, codec.getDefaultJavaType());
  }
}
