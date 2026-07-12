/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.CharArraySequence;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class Float4CodecTest {

  private Float4Codec codec;
  private PgType float4Type;

  @BeforeEach
  void setUp() {
    codec = Float4Codec.INSTANCE;
    float4Type = new PgType(
        new ObjectName("pg_catalog", "float4"),
        "real",
        Oid.FLOAT4,
        'b', 'N', -1, 0, 0, 0
    );
  }

  @Test
  void decodeBinary_positiveValue() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.float4(data, 0, 3.14f);
    Object result = codec.decodeBinary(data, 0, data.length, float4Type, null);
    assertEquals(3.14f, result);
  }

  @Test
  void decodeBinary_negativeValue() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.float4(data, 0, -3.14f);
    Object result = codec.decodeBinary(data, 0, data.length, float4Type, null);
    assertEquals(-3.14f, result);
  }

  @Test
  void decodeBinary_zero() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.float4(data, 0, 0.0f);
    Object result = codec.decodeBinary(data, 0, data.length, float4Type, null);
    assertEquals(0.0f, result);
  }

  @Test
  void decodeText_positiveValue() throws SQLException {
    Object result = codec.decodeText("3.14", float4Type, null);
    assertEquals(3.14f, result);
  }

  @Test
  void decodeText_negativeValue() throws SQLException {
    Object result = codec.decodeText("-3.14", float4Type, null);
    assertEquals(-3.14f, result);
  }

  @Test
  void decodeText_nan() throws SQLException {
    Object result = codec.decodeText("NaN", float4Type, null);
    assertEquals(Float.NaN, result);
  }

  @Test
  void decodeText_infinity() throws SQLException {
    Object result = codec.decodeText("Infinity", float4Type, null);
    assertEquals(Float.POSITIVE_INFINITY, result);
  }

  @Test
  void decodeText_negativeInfinity() throws SQLException {
    Object result = codec.decodeText("-Infinity", float4Type, null);
    assertEquals(Float.NEGATIVE_INFINITY, result);
  }

  @Test
  void encodeBinary_positiveValue() throws SQLException {
    byte[] result = codec.encodeBinary(3.14f, float4Type, null);
    byte[] expected = new byte[4];
    ByteConverter.float4(expected, 0, 3.14f);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeText_positiveValue() throws SQLException {
    String result = codec.encodeText(3.14f, float4Type, null);
    assertEquals("3.14", result);
  }

  @Test
  void decodeAsFloat_binary() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.float4(data, 0, 3.14f);
    float result = PrimitiveDecoders.asFloat(codec, data, float4Type, null);
    assertEquals(3.14f, result);
  }

  @Test
  void decodeAsDouble_binary() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.float4(data, 0, 3.14f);
    double result = PrimitiveDecoders.asDouble(codec, data, float4Type, null);
    assertEquals(3.14f, (float) result);
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    float original = 42.5f;
    byte[] encoded = codec.encodeBinary(original, float4Type, null);
    float decoded = PrimitiveDecoders.asFloat(codec, encoded, float4Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip() throws SQLException {
    float original = 42.5f;
    String encoded = codec.encodeText(original, float4Type, null);
    Object decoded = codec.decodeText(encoded, float4Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void getTypeName() {
    assertEquals("float4", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Float.class, codec.getDefaultJavaType());
  }

  // Regression: the char[] int/long accessors must round (Math.rint) like the String/binary forms, not
  // fall to the truncating boxToInt/boxToLong default -- "0.6" rounds to 1, "1.5" to 2, not 0 and 1.
  @Test
  void decodeAsInt_charArray_roundsLikeString() throws SQLException {
    char[] chars = "0.6".toCharArray();
    assertEquals(1, codec.decodeAsInt(new CharArraySequence(chars, 0, chars.length), float4Type, null));
    assertEquals(codec.decodeAsInt("0.6", float4Type, null),
        codec.decodeAsInt(new CharArraySequence(chars, 0, chars.length), float4Type, null));
  }

  @Test
  void decodeAsLong_charArray_roundsLikeString() throws SQLException {
    char[] chars = "1.5".toCharArray();
    assertEquals(2L, codec.decodeAsLong(new CharArraySequence(chars, 0, chars.length), float4Type, null));
    assertEquals(codec.decodeAsLong("1.5", float4Type, null),
        codec.decodeAsLong(new CharArraySequence(chars, 0, chars.length), float4Type, null));
  }
}
