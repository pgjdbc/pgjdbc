/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

  // ==================== decodeTextAs / decodeBinaryAs parity ====================

  @Test
  void decodeTextAs_matchesBinaryAs() throws SQLException {
    // decodeTextAs decodes straight from the text form; it must still agree with the binary path,
    // which the earlier text->bytes->decodeBinaryAs round-trip guaranteed by construction.
    BigDecimal value = new BigDecimal("12.5");
    byte[] binary = ByteConverter.numeric(value);
    String text = value.toPlainString();
    Class<?>[] targets = {
        BigDecimal.class, Object.class, Double.class, Float.class, Long.class,
        Integer.class, Short.class, Byte.class, String.class, Boolean.class,
    };
    for (Class<?> target : targets) {
      assertEquals(
          codec.decodeBinaryAs(binary, numericType, target, null),
          codec.decodeTextAs(text, numericType, target, null),
          "text/binary mismatch for " + target.getSimpleName());
    }
  }

  @Test
  void decodeTextAs_specialValues_double() throws SQLException {
    // NaN / ±Infinity reach Double via the same path the binary decode uses, rather than being
    // rejected by an up-front BigDecimal conversion.
    assertEquals(Double.valueOf(Double.NaN),
        codec.decodeTextAs("NaN", numericType, Double.class, null));
    assertEquals(Double.valueOf(Double.POSITIVE_INFINITY),
        codec.decodeTextAs("Infinity", numericType, Double.class, null));
    assertEquals(Double.valueOf(Double.NEGATIVE_INFINITY),
        codec.decodeTextAs("-Infinity", numericType, Double.class, null));
  }

  @Test
  void decodeTextAs_specialValues_float() throws SQLException {
    assertEquals(Float.valueOf(Float.NaN),
        codec.decodeTextAs("NaN", numericType, Float.class, null));
    assertEquals(Float.valueOf(Float.POSITIVE_INFINITY),
        codec.decodeTextAs("Infinity", numericType, Float.class, null));
  }

  @Test
  void decodeTextAs_nanToBigDecimal_throws() {
    // BigDecimal cannot hold NaN, so this stays an error on the text path too.
    assertThrows(PSQLException.class,
        () -> codec.decodeTextAs("NaN", numericType, BigDecimal.class, null));
  }

  // ==================== encode NaN / ±Infinity (text + binary) ====================

  @Test
  void encodeBinary_nan_roundTrips() throws SQLException {
    // Regression: encodeBinary used to route NaN through BigDecimal.valueOf, which throws
    // NumberFormatException. It must instead emit the numeric special: len=0, weight=0,
    // sign=0xC000 (NUMERIC_NAN), dscale=0 — the exact bytes numeric_send produces.
    byte[] encoded = codec.encodeBinary(Double.NaN, numericType, null);
    assertArrayEquals(new byte[]{0, 0, 0, 0, (byte) 0xC0, 0, 0, 0}, encoded);
    assertEquals(Double.valueOf(Double.NaN), codec.decodeBinary(encoded, numericType, null));
  }

  @Test
  void encodeBinary_positiveInfinity_roundTrips() throws SQLException {
    byte[] encoded = codec.encodeBinary(Double.POSITIVE_INFINITY, numericType, null);
    // sign=0xD000 (NUMERIC_PINF); dscale is discarded by the server on recv, so 0 is fine.
    assertArrayEquals(new byte[]{0, 0, 0, 0, (byte) 0xD0, 0, 0, 0}, encoded);
    assertEquals(Double.valueOf(Double.POSITIVE_INFINITY),
        codec.decodeBinary(encoded, numericType, null));
  }

  @Test
  void encodeBinary_negativeInfinity_roundTrips() throws SQLException {
    byte[] encoded = codec.encodeBinary(Double.NEGATIVE_INFINITY, numericType, null);
    // sign=0xF000 (NUMERIC_NINF)
    assertArrayEquals(new byte[]{0, 0, 0, 0, (byte) 0xF0, 0, 0, 0}, encoded);
    assertEquals(Double.valueOf(Double.NEGATIVE_INFINITY),
        codec.decodeBinary(encoded, numericType, null));
  }

  @Test
  void encodeBinary_floatSpecials_widenToDoubleSentinels() throws SQLException {
    assertEquals(Double.valueOf(Double.NaN),
        codec.decodeBinary(codec.encodeBinary(Float.NaN, numericType, null), numericType, null));
    assertEquals(Double.valueOf(Double.POSITIVE_INFINITY),
        codec.decodeBinary(codec.encodeBinary(Float.POSITIVE_INFINITY, numericType, null),
            numericType, null));
    assertEquals(Double.valueOf(Double.NEGATIVE_INFINITY),
        codec.decodeBinary(codec.encodeBinary(Float.NEGATIVE_INFINITY, numericType, null),
            numericType, null));
  }

  @Test
  void encodeText_specialValues_roundTrip() throws SQLException {
    assertEquals("NaN", codec.encodeText(Double.NaN, numericType, null));
    assertEquals("Infinity", codec.encodeText(Double.POSITIVE_INFINITY, numericType, null));
    assertEquals("-Infinity", codec.encodeText(Double.NEGATIVE_INFINITY, numericType, null));
    // Float too — the sentinels the fuzzer feeds through writeFloat.
    assertEquals("NaN", codec.encodeText(Float.NaN, numericType, null));

    assertEquals(Double.valueOf(Double.NaN), codec.decodeText("NaN", numericType, null));
    assertEquals(Double.valueOf(Double.POSITIVE_INFINITY),
        codec.decodeText("Infinity", numericType, null));
    assertEquals(Double.valueOf(Double.NEGATIVE_INFINITY),
        codec.decodeText("-Infinity", numericType, null));
  }

  @Test
  void encodeBinary_hugeFiniteBigDecimal_notMistakenForInfinity() throws SQLException {
    // BigDecimal.doubleValue() overflows to Infinity for very large finite values, so
    // specialValue must inspect only Float/Double, never BigDecimal.
    BigDecimal huge = new BigDecimal("1E400");
    Object decoded = codec.decodeBinary(codec.encodeBinary(huge, numericType, null),
        numericType, null);
    BigDecimal result = assertInstanceOf(BigDecimal.class, decoded);
    assertEquals(0, huge.compareTo(result));
  }

  @Test
  void numericNonFinite_rejectsFinite() {
    // The helper is only for the sentinels; a finite value is a caller bug, not silent garbage.
    assertThrows(IllegalArgumentException.class, () -> ByteConverter.numericNonFinite(1.0));
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
