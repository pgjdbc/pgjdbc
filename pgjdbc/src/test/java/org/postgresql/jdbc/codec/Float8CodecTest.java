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
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;

class Float8CodecTest {

  private Float8Codec codec;
  private PgType float8Type;

  @BeforeEach
  void setUp() {
    codec = Float8Codec.INSTANCE;
    float8Type = new PgType(
        new ObjectName("pg_catalog", "float8"),
        "double precision",
        Oid.FLOAT8,
        'b', 'N', -1, 0, 0, 0
    );
  }

  @Test
  void decodeBinary_positiveValue() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.float8(data, 0, 3.14159265358979);
    Object result = codec.decodeBinary(data, 0, data.length, float8Type, null);
    assertEquals(3.14159265358979, result);
  }

  @Test
  void decodeBinary_negativeValue() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.float8(data, 0, -3.14159265358979);
    Object result = codec.decodeBinary(data, 0, data.length, float8Type, null);
    assertEquals(-3.14159265358979, result);
  }

  @Test
  void decodeBinary_zero() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.float8(data, 0, 0.0);
    Object result = codec.decodeBinary(data, 0, data.length, float8Type, null);
    assertEquals(0.0, result);
  }

  @Test
  void decodeText_positiveValue() throws SQLException {
    Object result = codec.decodeText("3.14159265358979", float8Type, null);
    assertEquals(3.14159265358979, result);
  }

  @Test
  void decodeText_nan() throws SQLException {
    Object result = codec.decodeText("NaN", float8Type, null);
    assertEquals(Double.NaN, result);
  }

  @Test
  void decodeText_infinity() throws SQLException {
    Object result = codec.decodeText("Infinity", float8Type, null);
    assertEquals(Double.POSITIVE_INFINITY, result);
  }

  @Test
  void decodeText_negativeInfinity() throws SQLException {
    Object result = codec.decodeText("-Infinity", float8Type, null);
    assertEquals(Double.NEGATIVE_INFINITY, result);
  }

  @Test
  void encodeBinary_positiveValue() throws SQLException {
    byte[] result = codec.encodeBinary(3.14, float8Type, null);
    byte[] expected = new byte[8];
    ByteConverter.float8(expected, 0, 3.14);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeText_positiveValue() throws SQLException {
    String result = codec.encodeText(3.14, float8Type, null);
    assertEquals("3.14", result);
  }

  @Test
  void decodeAsDouble_binary() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.float8(data, 0, 3.14);
    double result = PrimitiveDecoders.asDouble(codec, data, float8Type, null);
    assertEquals(3.14, result);
  }

  @Test
  void decodeAsFloat_binary() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.float8(data, 0, 3.14);
    float result = PrimitiveDecoders.asFloat(codec, data, float8Type, null);
    assertEquals(3.14f, result, 0.001f);
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    double original = 123456.789;
    byte[] encoded = codec.encodeBinary(original, float8Type, null);
    double decoded = PrimitiveDecoders.asDouble(codec, encoded, float8Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip() throws SQLException {
    double original = 123456.789;
    String encoded = codec.encodeText(original, float8Type, null);
    Object decoded = codec.decodeText(encoded, float8Type, null);
    assertEquals(original, decoded);
  }

  @Test
  void decodeAsBigDecimal_text_finite() throws SQLException {
    assertEquals(new BigDecimal("3.14"), codec.decodeAsBigDecimal("3.14", float8Type, null));
  }

  @Test
  void decodeAsBigDecimal_text_nonFinite() {
    // A non-finite float has no BigDecimal form, so the text path refuses with the same state the
    // binary path raises, rather than leaking NumberFormatException from BigDecimal.valueOf.
    for (String literal : new String[]{"Infinity", "-Infinity", "NaN"}) {
      PSQLException e = assertThrows(PSQLException.class,
          () -> codec.decodeAsBigDecimal(literal, float8Type, null),
          () -> "float8 text " + literal + " should refuse readBigDecimal");
      assertEquals(PSQLState.NUMERIC_VALUE_OUT_OF_RANGE.getState(), e.getSQLState(),
          () -> "SQLState for float8 " + literal + " to BigDecimal");
    }
  }

  @Test
  void decodeAsBigDecimal_binary_nonFinite() {
    byte[] data = new byte[8];
    ByteConverter.float8(data, 0, Double.NEGATIVE_INFINITY);
    PSQLException e = assertThrows(PSQLException.class,
        () -> codec.decodeAsBigDecimal(data, 0, data.length, (TypeDescriptor) float8Type, (CodecContext) null));
    assertEquals(PSQLState.NUMERIC_VALUE_OUT_OF_RANGE.getState(), e.getSQLState());
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("float8", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Double.class, codec.getDefaultJavaType());
  }

  // Regression: the char[] int/long accessors must round (Math.rint) like the String/binary forms, not
  // fall to the truncating boxToInt/boxToLong default -- "0.6" rounds to 1, "1.5" to 2, not 0 and 1.
  @Test
  void decodeAsInt_charArray_roundsLikeString() throws SQLException {
    char[] chars = "0.6".toCharArray();
    assertEquals(1, codec.decodeAsInt(new CharArraySequence(chars, 0, chars.length), float8Type, null));
    assertEquals(codec.decodeAsInt("0.6", float8Type, null),
        codec.decodeAsInt(new CharArraySequence(chars, 0, chars.length), float8Type, null));
  }

  @Test
  void decodeAsLong_charArray_roundsLikeString() throws SQLException {
    char[] chars = "1.5".toCharArray();
    assertEquals(2L, codec.decodeAsLong(new CharArraySequence(chars, 0, chars.length), float8Type, null));
    assertEquals(codec.decodeAsLong("1.5", float8Type, null),
        codec.decodeAsLong(new CharArraySequence(chars, 0, chars.length), float8Type, null));
  }
}
