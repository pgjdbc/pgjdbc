/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;

class NumericCodecTest {

  private NumericCodec codec;
  private PgType numericType;

  @BeforeEach
  void setUp() {
    codec = NumericCodec.INSTANCE;
    numericType = new PgType(
        new ObjectName("pg_catalog", "numeric"),
        "numeric",
        Oid.NUMERIC,
        'b', 'N', -1, 0, 0, 0
    );
  }

  @Test
  void decodeText_integer() throws SQLException {
    Object result = codec.decodeText("42", numericType, null);
    assertEquals(new BigDecimal("42"), result);
  }

  @Test
  void decodeText_decimal() throws SQLException {
    Object result = codec.decodeText("3.14159", numericType, null);
    assertEquals(new BigDecimal("3.14159"), result);
  }

  @Test
  void decodeText_negative() throws SQLException {
    Object result = codec.decodeText("-123.456", numericType, null);
    assertEquals(new BigDecimal("-123.456"), result);
  }

  @Test
  void decodeText_zero() throws SQLException {
    Object result = codec.decodeText("0", numericType, null);
    assertEquals(new BigDecimal("0"), result);
  }

  @Test
  void decodeText_largeValue() throws SQLException {
    String largeNum = "99999999999999999999999999999999.999999";
    Object result = codec.decodeText(largeNum, numericType, null);
    assertEquals(new BigDecimal(largeNum), result);
  }

  @Test
  void encodeText_integer() throws SQLException {
    String result = codec.encodeText(new BigDecimal("42"), numericType, null);
    assertEquals("42", result);
  }

  @Test
  void encodeText_decimal() throws SQLException {
    String result = codec.encodeText(new BigDecimal("3.14159"), numericType, null);
    assertEquals("3.14159", result);
  }

  @Test
  void decodeAsBigDecimal_text() throws SQLException {
    BigDecimal result = codec.decodeAsBigDecimal("123.456", numericType, null);
    assertEquals(new BigDecimal("123.456"), result);
  }

  @Test
  void decodeAsInt_text() throws SQLException {
    int result = codec.decodeAsInt("42", numericType, null);
    assertEquals(42, result);
  }

  @Test
  void decodeAsLong_text() throws SQLException {
    long result = codec.decodeAsLong("9999999999", numericType, null);
    assertEquals(9999999999L, result);
  }

  @Test
  void decodeAsDouble_text() throws SQLException {
    double result = codec.decodeAsDouble("3.14", numericType, null);
    assertEquals(3.14, result, 0.001);
  }

  @Test
  void decodeAsFloat_text() throws SQLException {
    float result = codec.decodeAsFloat("3.14", numericType, null);
    assertEquals(3.14f, result, 0.001f);
  }

  @Test
  void decodeAsString_text() throws SQLException {
    String result = codec.decodeAsString("123.456", numericType, null);
    assertEquals("123.456", result);
  }

  @Test
  void textRoundtrip_decimal() throws SQLException {
    BigDecimal original = new BigDecimal("12345.67890");
    String encoded = codec.encodeText(original, numericType, null);
    Object decoded = codec.decodeText(encoded, numericType, null);
    assertEquals(original, decoded);
  }

  @Test
  void getTypeName() {
    assertEquals("numeric", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(BigDecimal.class, codec.getDefaultJavaType());
  }
}
