/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
    Object result = codec.decodeBinary(data, int2Type, null);
    assertEquals(42, result);
  }

  @Test
  void decodeBinary_negativeValue() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, -42);
    Object result = codec.decodeBinary(data, int2Type, null);
    assertEquals(-42, result);
  }

  @Test
  void decodeBinary_zero() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, 0);
    Object result = codec.decodeBinary(data, int2Type, null);
    assertEquals(0, result);
  }

  @Test
  void decodeBinary_maxValue() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, Short.MAX_VALUE);
    Object result = codec.decodeBinary(data, int2Type, null);
    assertEquals((int) Short.MAX_VALUE, result);
  }

  @Test
  void decodeBinary_minValue() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, Short.MIN_VALUE);
    Object result = codec.decodeBinary(data, int2Type, null);
    assertEquals((int) Short.MIN_VALUE, result);
  }

  @Test
  void decodeText_positiveValue() throws SQLException {
    Object result = codec.decodeText("42", int2Type, null);
    assertEquals(42, result);
  }

  @Test
  void decodeText_negativeValue() throws SQLException {
    Object result = codec.decodeText("-42", int2Type, null);
    assertEquals(-42, result);
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
    int result = codec.decodeAsInt(data, int2Type, null);
    assertEquals(42, result);
  }

  @Test
  void decodeAsLong_binary() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, 42);
    long result = codec.decodeAsLong(data, int2Type, null);
    assertEquals(42L, result);
  }

  @Test
  void decodeAsString_binary() throws SQLException {
    byte[] data = new byte[2];
    ByteConverter.int2(data, 0, 42);
    String result = codec.decodeAsString(data, int2Type, null);
    assertEquals("42", result);
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    short original = 12345;
    byte[] encoded = codec.encodeBinary((int) original, int2Type, null);
    int decoded = codec.decodeAsInt(encoded, int2Type, null);
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
  void getTypeName() {
    assertEquals("int2", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Integer.class, codec.getDefaultJavaType());
  }
}
