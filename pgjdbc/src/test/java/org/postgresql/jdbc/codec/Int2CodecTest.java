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

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

class Int2CodecTest {

  private Int2Codec codec;
  private PgType int2Type;

  @BeforeEach
  void setUp() {
    codec = Int2Codec.INSTANCE;
    int2Type = new PgType(
        new ObjectName("pg_catalog", "int2"),
        "smallint",
        Oid.INT2,
        'b', 'N', -1, 0, 0, 0
    );
  }

  @Test
  void decodeBinary_positiveValue() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, 42);
    Object result = codec.decodeBinary(data, 0, data.length, int2Type, null);
    assertEquals(42, result);
  }

  @Test
  void decodeBinary_negativeValue() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, -42);
    Object result = codec.decodeBinary(data, 0, data.length, int2Type, null);
    assertEquals(-42, result);
  }

  @Test
  void decodeBinary_zero() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, 0);
    Object result = codec.decodeBinary(data, 0, data.length, int2Type, null);
    assertEquals(0, result);
  }

  @Test
  void decodeBinary_maxValue() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, Short.MAX_VALUE);
    Object result = codec.decodeBinary(data, 0, data.length, int2Type, null);
    assertEquals((int) Short.MAX_VALUE, result);
  }

  @Test
  void decodeBinary_minValue() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, Short.MIN_VALUE);
    Object result = codec.decodeBinary(data, 0, data.length, int2Type, null);
    assertEquals((int) Short.MIN_VALUE, result);
  }

  @Test
  void decodeText_positiveValue() throws SQLException {
    Object result = codec.decodeText("42", int2Type, null);
    assertEquals(42, result);
  }

  @Test
  void decodeAsInt_charSlice() throws SQLException {
    // The fast path reads the digits off the slice with no String and no box.
    char[] buf = "x-42y".toCharArray();
    assertEquals(-42, codec.decodeAsInt(new CharArraySequence(buf, 1, 3), int2Type, null));
    // A leading '+' is rejected by the fast path and handled by the String fallback.
    char[] plus = "+7".toCharArray();
    assertEquals(7, codec.decodeAsInt(new CharArraySequence(plus, 0, plus.length), int2Type, null));
    // Out of int2 range surfaces the same error as the String form.
    char[] overflow = "40000".toCharArray();
    assertThrows(PSQLException.class,
        () -> codec.decodeAsInt(new CharArraySequence(overflow, 0, overflow.length), int2Type, null));
  }

  @Test
  void decodeText_negativeValue() throws SQLException {
    Object result = codec.decodeText("-42", int2Type, null);
    assertEquals(-42, result);
  }

  // ==================== Text-as-bytes Decoding ====================

  // int2 overrides decodeTextBytesAsInt/Long so getShort/getInt/getLong parse the digits straight
  // off the wire bytes with no per-row String, matching the int4/int8 fast paths.

  @Test
  void decodeTextBytesAsInt_fastPath() throws SQLException {
    CodecContext ctx = TestCodecContext.create();
    assertEquals(42, codec.decodeTextBytesAsInt("42".getBytes(StandardCharsets.US_ASCII), int2Type, ctx));
    assertEquals(-42, codec.decodeTextBytesAsInt("-42".getBytes(StandardCharsets.US_ASCII), int2Type, ctx));
    assertEquals(0, codec.decodeTextBytesAsInt("0".getBytes(StandardCharsets.US_ASCII), int2Type, ctx));
    assertEquals(Short.MAX_VALUE,
        codec.decodeTextBytesAsInt(String.valueOf(Short.MAX_VALUE).getBytes(StandardCharsets.US_ASCII), int2Type, ctx));
    assertEquals(Short.MIN_VALUE,
        codec.decodeTextBytesAsInt(String.valueOf(Short.MIN_VALUE).getBytes(StandardCharsets.US_ASCII), int2Type, ctx));
  }

  @Test
  void decodeTextBytesAsLong_fastPath() throws SQLException {
    CodecContext ctx = TestCodecContext.create();
    assertEquals(42L, codec.decodeTextBytesAsLong("42".getBytes(StandardCharsets.US_ASCII), int2Type, ctx));
    assertEquals(-42L, codec.decodeTextBytesAsLong("-42".getBytes(StandardCharsets.US_ASCII), int2Type, ctx));
  }

  @Test
  void decodeTextBytesAsInt_fallbackForFastPathReject() throws SQLException {
    // Surrounding whitespace makes the byte fast path reject, exercising the String fallback.
    CodecContext ctx = TestCodecContext.create();
    assertEquals(42, codec.decodeTextBytesAsInt(" 42 ".getBytes(StandardCharsets.US_ASCII), int2Type, ctx));
  }

  @Test
  void decodeTextBytesAsInt_overflow() {
    // A value beyond int2 range must still be rejected when decoded through the byte fast path.
    CodecContext ctx = TestCodecContext.create();
    assertThrows(PSQLException.class,
        () -> codec.decodeTextBytesAsInt("40000".getBytes(StandardCharsets.US_ASCII), int2Type, ctx));
  }

  @Test
  void encodeBinary_positiveValue() throws SQLException {
    byte[] result = codec.encodeBinary(42, int2Type, null);
    byte[] expected = new byte[2];
    ByteConverter.int2(expected, 0, 42);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeText_positiveValue() throws SQLException {
    String result = codec.encodeText(42, int2Type, null);
    assertEquals("42", result);
  }

  @Test
  void decodeAsInt_binary() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, 42);
    int result = PrimitiveDecoders.asInt(codec, data, int2Type, null);
    assertEquals(42, result);
  }

  @Test
  void decodeAsLong_binary() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, 42);
    long result = PrimitiveDecoders.asLong(codec, data, int2Type, null);
    assertEquals(42L, result);
  }

  @Test
  void decodeAsString_binary() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, 42);
    String result = codec.decodeAsString(data, 0, data.length, int2Type, null);
    assertEquals("42", result);
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    short original = 12345;
    byte[] encoded = codec.encodeBinary((int) original, int2Type, null);
    int decoded = PrimitiveDecoders.asInt(codec, encoded, int2Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip() throws SQLException {
    int original = 12345;
    String encoded = codec.encodeText(original, int2Type, null);
    Object decoded = codec.decodeText(encoded, int2Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("int2", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Integer.class, codec.getDefaultJavaType());
  }
}
