/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;

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
    Object result = codec.decodeBinary(data, float8Type, null);
    assertEquals(3.14159265358979, result);
  }

  @Test
  void decodeBinary_negativeValue() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.float8(data, 0, -3.14159265358979);
    Object result = codec.decodeBinary(data, float8Type, null);
    assertEquals(-3.14159265358979, result);
  }

  @Test
  void decodeBinary_zero() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.float8(data, 0, 0.0);
    Object result = codec.decodeBinary(data, float8Type, null);
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
    double result = codec.decodeAsDouble(data, float8Type, null);
    assertEquals(3.14, result);
  }

  @Test
  void decodeAsFloat_binary() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.float8(data, 0, 3.14);
    float result = codec.decodeAsFloat(data, float8Type, null);
    assertEquals(3.14f, result, 0.001f);
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    double original = 123456.789;
    byte[] encoded = codec.encodeBinary(original, float8Type, null);
    double decoded = codec.decodeAsDouble(encoded, float8Type, null);
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
  void getTypeName() {
    assertEquals("float8", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Double.class, codec.getDefaultJavaType());
  }
}
