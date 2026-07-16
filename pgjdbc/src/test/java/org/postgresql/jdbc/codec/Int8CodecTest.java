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
import org.postgresql.api.codec.TypeDescriptor;
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
 * Unit tests for Int8Codec.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Binary decode/encode with positive, negative, zero, max, min values</li>
 *   <li>Text decode/encode with overflow handling</li>
 *   <li>Type conversions with overflow detection (decodeAsInt, decodeBinaryAs Short/Byte)</li>
 *   <li>Roundtrip encoding/decoding</li>
 * </ul>
 */
class Int8CodecTest {

  private Int8Codec codec;
  private PgType int8Type;

  @BeforeEach
  void setUp() {
    codec = Int8Codec.INSTANCE;
    // Create a minimal PgType for int8
    int8Type = new PgType(
        new ObjectName("pg_catalog", "int8"),
        "bigint",
        Oid.INT8,
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
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 42L);

    Object result = codec.decodeBinary(data, 0, data.length, int8Type, null);
    assertEquals(42L, result);
  }

  @Test
  void decodeBinary_negativeValue() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, -42L);

    Object result = codec.decodeBinary(data, 0, data.length, int8Type, null);
    assertEquals(-42L, result);
  }

  @Test
  void decodeBinary_zero() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 0L);

    Object result = codec.decodeBinary(data, 0, data.length, int8Type, null);
    assertEquals(0L, result);
  }

  @Test
  void decodeBinary_maxValue() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, Long.MAX_VALUE);

    Object result = codec.decodeBinary(data, 0, data.length, int8Type, null);
    assertEquals(Long.MAX_VALUE, result);
  }

  @Test
  void decodeBinary_minValue() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, Long.MIN_VALUE);

    Object result = codec.decodeBinary(data, 0, data.length, int8Type, null);
    assertEquals(Long.MIN_VALUE, result);
  }

  // ==================== Text Decoding ====================

  @Test
  void decodeText_positiveValue() throws SQLException {
    Object result = codec.decodeText("42", int8Type, null);
    assertEquals(42L, result);
  }

  @Test
  void decodeAsLong_charSlice() throws SQLException {
    // The fast path reads the digits off the slice with no String and no box.
    char[] buf = "x-42y".toCharArray();
    assertEquals(-42L, codec.decodeAsLong(new CharArraySequence(buf, 1, 3), int8Type, null));
    // A leading '+' is rejected by the fast path and handled by the String fallback.
    char[] plus = "+7".toCharArray();
    assertEquals(7L, codec.decodeAsLong(new CharArraySequence(plus, 0, plus.length), int8Type, null));
    // Out of int8 range surfaces the same error as the String form.
    char[] overflow = "99999999999999999999".toCharArray();
    assertThrows(PSQLException.class,
        () -> codec.decodeAsLong(new CharArraySequence(overflow, 0, overflow.length), int8Type, null));
  }

  @Test
  void decodeText_negativeValue() throws SQLException {
    Object result = codec.decodeText("-42", int8Type, null);
    assertEquals(-42L, result);
  }

  @Test
  void decodeText_maxValue() throws SQLException {
    Object result = codec.decodeText(String.valueOf(Long.MAX_VALUE), int8Type, null);
    assertEquals(Long.MAX_VALUE, result);
  }

  @Test
  void decodeText_minValue() throws SQLException {
    Object result = codec.decodeText(String.valueOf(Long.MIN_VALUE), int8Type, null);
    assertEquals(Long.MIN_VALUE, result);
  }

  @Test
  void decodeText_overflow_positive() {
    // One more than MAX_VALUE
    String overflow = "9223372036854775808";
    assertThrows(PSQLException.class, () -> codec.decodeText(overflow, int8Type, null));
  }

  @Test
  void decodeText_overflow_negative() {
    // One less than MIN_VALUE
    String overflow = "-9223372036854775809";
    assertThrows(PSQLException.class, () -> codec.decodeText(overflow, int8Type, null));
  }

  // ==================== Text-as-bytes Decoding ====================

  // decodeTextBytesAsLong reads the ASCII-number compatibility off the context's charset rather than
  // a connection Encoding, so it works through any CodecContext. TestCodecContext has no backing
  // Encoding, which is exactly the case the earlier PgCodecContext downcast could not serve.

  @Test
  void decodeTextBytesAsLong_fastPath() throws SQLException {
    CodecContext ctx = TestCodecContext.create();
    assertEquals(42L, codec.decodeTextBytesAsLong("42".getBytes(StandardCharsets.US_ASCII), int8Type, ctx));
    assertEquals(-42L, codec.decodeTextBytesAsLong("-42".getBytes(StandardCharsets.US_ASCII), int8Type, ctx));
    assertEquals(0L, codec.decodeTextBytesAsLong("0".getBytes(StandardCharsets.US_ASCII), int8Type, ctx));
    assertEquals(Long.MAX_VALUE,
        codec.decodeTextBytesAsLong(String.valueOf(Long.MAX_VALUE).getBytes(StandardCharsets.US_ASCII), int8Type, ctx));
    assertEquals(Long.MIN_VALUE,
        codec.decodeTextBytesAsLong(String.valueOf(Long.MIN_VALUE).getBytes(StandardCharsets.US_ASCII), int8Type, ctx));
  }

  @Test
  void decodeTextBytesAsLong_fallbackForFastPathReject() throws SQLException {
    // Surrounding whitespace makes the byte fast path reject, exercising the String fallback.
    CodecContext ctx = TestCodecContext.create();
    assertEquals(42L, codec.decodeTextBytesAsLong(" 42 ".getBytes(StandardCharsets.US_ASCII), int8Type, ctx));
  }

  @Test
  void decodeTextBytesAsInt_fastPath() throws SQLException {
    CodecContext ctx = TestCodecContext.create();
    assertEquals(42, codec.decodeTextBytesAsInt("42".getBytes(StandardCharsets.US_ASCII), int8Type, ctx));
    assertEquals(-42, codec.decodeTextBytesAsInt("-42".getBytes(StandardCharsets.US_ASCII), int8Type, ctx));
  }

  @Test
  void decodeTextBytesAsInt_overflow() {
    // A value beyond int range must still be rejected when decoded through the byte fast path.
    CodecContext ctx = TestCodecContext.create();
    assertThrows(PSQLException.class,
        () -> codec.decodeTextBytesAsInt("2147483648".getBytes(StandardCharsets.US_ASCII), int8Type, ctx));
  }

  // ==================== Binary Encoding ====================

  @Test
  void encodeBinary_positiveValue() throws SQLException {
    byte[] result = codec.encodeBinary(42L, int8Type, null);

    byte[] expected = new byte[8];
    ByteConverter.int8(expected, 0, 42L);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeBinary_negativeValue() throws SQLException {
    byte[] result = codec.encodeBinary(-42L, int8Type, null);

    byte[] expected = new byte[8];
    ByteConverter.int8(expected, 0, -42L);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeBinary_fromInteger() throws SQLException {
    byte[] result = codec.encodeBinary(42, int8Type, null);

    byte[] expected = new byte[8];
    ByteConverter.int8(expected, 0, 42L);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeBinary_fromString() throws SQLException {
    byte[] result = codec.encodeBinary("42", int8Type, null);

    byte[] expected = new byte[8];
    ByteConverter.int8(expected, 0, 42L);
    assertArrayEquals(expected, result);
  }

  // ==================== decodeAsInt Overflow ====================

  @Test
  void decodeAsInt_binary_withinRange() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 42L);

    int result = PrimitiveDecoders.asInt(codec, data, int8Type, null);
    assertEquals(42, result);
  }

  @Test
  void decodeAsInt_binary_maxIntValue() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, Integer.MAX_VALUE);

    int result = PrimitiveDecoders.asInt(codec, data, int8Type, null);
    assertEquals(Integer.MAX_VALUE, result);
  }

  @Test
  void decodeAsInt_binary_minIntValue() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, Integer.MIN_VALUE);

    int result = PrimitiveDecoders.asInt(codec, data, int8Type, null);
    assertEquals(Integer.MIN_VALUE, result);
  }

  @Test
  void decodeAsInt_binary_overflow_positive() {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, (long) Integer.MAX_VALUE + 1);

    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asInt(codec, data, int8Type, null));
  }

  @Test
  void decodeAsInt_binary_overflow_negative() {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, (long) Integer.MIN_VALUE - 1);

    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asInt(codec, data, int8Type, null));
  }

  @Test
  void decodeAsInt_text_overflow_positive() {
    String value = String.valueOf((long) Integer.MAX_VALUE + 1);
    assertThrows(PSQLException.class, () -> codec.decodeAsInt(value, int8Type, null));
  }

  @Test
  void decodeAsInt_text_overflow_negative() {
    String value = String.valueOf((long) Integer.MIN_VALUE - 1);
    assertThrows(PSQLException.class, () -> codec.decodeAsInt(value, int8Type, null));
  }

  // ==================== decodeBinaryAs Overflow ====================

  @Test
  void decodeBinaryAs_Integer_overflow() {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, (long) Integer.MAX_VALUE + 1);

    assertThrows(PSQLException.class, () ->
        codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) int8Type, Integer.class, (CodecContext) null));
  }

  @Test
  void decodeBinaryAs_Short_withinRange() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 100L);

    Short result = codec.decodeBinaryAs(data, 0, data.length, int8Type, Short.class, null);
    assertEquals(Short.valueOf((short) 100), result);
  }

  @Test
  void decodeBinaryAs_Short_overflow_positive() {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, (long) Short.MAX_VALUE + 1);

    assertThrows(PSQLException.class, () ->
        codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) int8Type, Short.class, (CodecContext) null));
  }

  @Test
  void decodeBinaryAs_Short_overflow_negative() {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, (long) Short.MIN_VALUE - 1);

    assertThrows(PSQLException.class, () ->
        codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) int8Type, Short.class, (CodecContext) null));
  }

  @Test
  void decodeBinaryAs_Byte_withinRange() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 100L);

    Byte result = codec.decodeBinaryAs(data, 0, data.length, int8Type, Byte.class, null);
    assertEquals(Byte.valueOf((byte) 100), result);
  }

  @Test
  void decodeBinaryAs_Byte_overflow_positive() {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, (long) Byte.MAX_VALUE + 1);

    assertThrows(PSQLException.class, () ->
        codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) int8Type, Byte.class, (CodecContext) null));
  }

  @Test
  void decodeBinaryAs_Byte_overflow_negative() {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, (long) Byte.MIN_VALUE - 1);

    assertThrows(PSQLException.class, () ->
        codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) int8Type, Byte.class, (CodecContext) null));
  }

  @Test
  void decodeBinaryAs_BigDecimal() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, Long.MAX_VALUE);

    BigDecimal result = codec.decodeBinaryAs(data, 0, data.length, int8Type, BigDecimal.class, null);
    assertEquals(BigDecimal.valueOf(Long.MAX_VALUE), result);
  }

  @Test
  void decodeBinaryAs_String() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 12345L);

    String result = codec.decodeBinaryAs(data, 0, data.length, int8Type, String.class, null);
    assertEquals("12345", result);
  }

  @Test
  void decodeBinaryAs_Boolean_nonZero() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 1L);

    Boolean result = codec.decodeBinaryAs(data, 0, data.length, int8Type, Boolean.class, null);
    assertEquals(Boolean.TRUE, result);
  }

  @Test
  void decodeBinaryAs_Boolean_zero() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 0L);

    Boolean result = codec.decodeBinaryAs(data, 0, data.length, int8Type, Boolean.class, null);
    assertEquals(Boolean.FALSE, result);
  }

  // ==================== Roundtrip Tests ====================

  @Test
  void binaryRoundtrip_positiveValue() throws SQLException {
    long original = 123456789012345L;
    byte[] encoded = codec.encodeBinary(original, int8Type, null);
    long decoded = PrimitiveDecoders.asLong(codec, encoded, int8Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void binaryRoundtrip_negativeValue() throws SQLException {
    long original = -123456789012345L;
    byte[] encoded = codec.encodeBinary(original, int8Type, null);
    long decoded = PrimitiveDecoders.asLong(codec, encoded, int8Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void binaryRoundtrip_maxValue() throws SQLException {
    long original = Long.MAX_VALUE;
    byte[] encoded = codec.encodeBinary(original, int8Type, null);
    long decoded = PrimitiveDecoders.asLong(codec, encoded, int8Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void binaryRoundtrip_minValue() throws SQLException {
    long original = Long.MIN_VALUE;
    byte[] encoded = codec.encodeBinary(original, int8Type, null);
    long decoded = PrimitiveDecoders.asLong(codec, encoded, int8Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip_positiveValue() throws SQLException {
    long original = 123456789012345L;
    String encoded = codec.encodeText(original, int8Type, null);
    long decoded = codec.decodeAsLong(encoded, int8Type, null);
    assertEquals(original, decoded);
  }

  // ==================== Codec Metadata ====================

  @Test
  void getPrimaryTypeName() {
    assertEquals("int8", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Long.class, codec.getDefaultJavaType());
  }

  // ==================== Invalid Data Length ====================

  @Test
  void decodeBinary_invalidLength() {
    byte[] data = new byte[4]; // Should be 8
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asLong(codec, data, int8Type, null));
  }
}
