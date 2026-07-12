/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;

/**
 * Unit tests for {@link Oid8Codec}, the PostgreSQL 18+ {@code oid8} (unsigned 64-bit object
 * identifier) codec. Unlike {@link OidCodecTest}'s {@code oid} (which safely widens its unsigned
 * 32-bit range into a signed {@code long}), {@code oid8}'s unsigned 64-bit range does not fit in
 * a signed {@code long}: values at or above 2<sup>63</sup> round-trip as the same bit pattern but
 * read as negative via {@link Oid8Codec#decodeAsLong}, and only decode to their true decimal
 * value through the unsigned-aware paths ({@code encodeText}, {@code BigInteger}/{@code String}
 * target classes).
 */
class Oid8CodecTest {

  private static final long MAX_UNSIGNED = -1L; // bit pattern of 2^64 - 1
  private static final BigInteger MAX_UNSIGNED_BIG_INTEGER =
      new BigInteger("18446744073709551615");

  private Oid8Codec codec;
  private PgType oid8Type;

  @BeforeEach
  void setUp() {
    codec = Oid8Codec.INSTANCE;
    oid8Type = new PgType(
        new ObjectName("pg_catalog", "oid8"),
        "oid8",
        Oid.OID8,
        'b', 'N', -1, 0, 0, 0
    );
  }

  @Test
  void getTypeName() {
    assertEquals("oid8", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Long.class, codec.getDefaultJavaType());
  }

  // ==================== Text Decoding ====================

  @Test
  void decodeText_positiveValue() throws SQLException {
    assertEquals(42L, codec.decodeText("42", oid8Type, null));
  }

  @Test
  void decodeText_maxUnsigned() throws SQLException {
    // 2^64 - 1 does not fit a signed long; its bit pattern is -1.
    assertEquals(MAX_UNSIGNED, codec.decodeText("18446744073709551615", oid8Type, null));
  }

  @Test
  void decodeText_zero() throws SQLException {
    assertEquals(0L, codec.decodeText("0", oid8Type, null));
  }

  @Test
  void decodeText_negativeIsRejected() {
    // oid8's own text form never carries a sign; unlike oid8in (strtoul-based), the driver does
    // not accept a leading '-'.
    assertThrows(PSQLException.class, () -> codec.decodeText("-1", oid8Type, null));
  }

  // ==================== Binary Decoding ====================

  @Test
  void decodeBinary_positiveValue() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 42L);
    assertEquals(42L, codec.decodeBinary(data, 0, data.length, oid8Type, null));
  }

  @Test
  void decodeBinary_unsignedHighBit() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, MAX_UNSIGNED);
    long result = PrimitiveDecoders.asLong(codec, data, oid8Type, null);
    assertEquals(MAX_UNSIGNED, result);
  }

  @Test
  void decodeBinary_invalidLength() {
    byte[] data = new byte[7]; // wrong length
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asLong(codec, data, oid8Type, null));
  }

  // ==================== Encoding ====================

  @Test
  void encodeText_value() throws SQLException {
    assertEquals("42", codec.encodeText(42L, oid8Type, null));
  }

  @Test
  void encodeText_unsignedHighBit() throws SQLException {
    // A negative-bit-pattern long is the wire form of a value >= 2^63; encodeText prints its
    // true unsigned decimal value, not "-1".
    assertEquals("18446744073709551615", codec.encodeText(MAX_UNSIGNED, oid8Type, null));
  }

  @Test
  void encodeBinary_value() throws SQLException {
    byte[] result = codec.encodeBinary(42L, oid8Type, null);
    byte[] expected = new byte[8];
    ByteConverter.int8(expected, 0, 42L);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeBinary_fromUnsignedDecimalString() throws SQLException {
    byte[] result = codec.encodeBinary("18446744073709551615", oid8Type, null);
    byte[] expected = new byte[8];
    ByteConverter.int8(expected, 0, MAX_UNSIGNED);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeBinary_fromBigInteger() throws SQLException {
    // BigInteger#longValue() truncates to the low 64 bits, exactly the wire bit pattern.
    byte[] result = codec.encodeBinary(MAX_UNSIGNED_BIG_INTEGER, oid8Type, null);
    byte[] expected = new byte[8];
    ByteConverter.int8(expected, 0, MAX_UNSIGNED);
    assertArrayEquals(expected, result);
  }

  // ==================== Type Conversions ====================

  @Test
  void decodeAsDouble_unsignedHighBit() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, MAX_UNSIGNED);
    double result = PrimitiveDecoders.asDouble(codec, data, oid8Type, null);
    // 2^64 - 1 rounds to 2^64 in a double (53-bit mantissa).
    assertEquals(Math.pow(2, 64), result);
  }

  // Regression: decodeAsFloat must mirror the unsigned decodeAsDouble, not the signed boxing default.
  // For an oid8 at or above 2^63 the raw long is negative, so the default would read a large negative
  // float while decodeAsDouble reads the unsigned magnitude.
  @Test
  void decodeAsFloat_unsignedHighBit_matchesUnsignedDouble() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, MAX_UNSIGNED);
    double asDouble = PrimitiveDecoders.asDouble(codec, data, oid8Type, null);
    float asFloat = PrimitiveDecoders.asFloat(codec, data, oid8Type, null);
    assertEquals(Float.floatToRawIntBits((float) asDouble), Float.floatToRawIntBits(asFloat));
    assertEquals((float) Math.pow(2, 64), asFloat);
  }

  // Regression: the char[] double accessor must match the (unsigned) String form, not the signed long
  // the default char[] path would widen.
  @Test
  void decodeAsDouble_charArrayMatchesString_unsignedHighBit() throws SQLException {
    char[] chars = MAX_UNSIGNED_BIG_INTEGER.toString().toCharArray();
    assertEquals(
        Double.doubleToRawLongBits(codec.decodeAsDouble(MAX_UNSIGNED_BIG_INTEGER.toString(), oid8Type, null)),
        Double.doubleToRawLongBits(codec.decodeAsDouble(new CharArraySequence(chars, 0, chars.length), oid8Type, null)));
  }

  @Test
  void decodeAsBigDecimal_unsignedHighBit() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, MAX_UNSIGNED);
    assertEquals(new BigDecimal(MAX_UNSIGNED_BIG_INTEGER),
        codec.decodeAsBigDecimal(data, 0, data.length, oid8Type, null));
  }

  // ==================== decodeBinaryAs ====================

  @Test
  void decodeBinaryAs_Long() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 42L);
    assertEquals(Long.valueOf(42), codec.decodeBinaryAs(data, 0, data.length, oid8Type, Long.class, null));
  }

  @Test
  void decodeBinaryAs_String_unsignedHighBit() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, MAX_UNSIGNED);
    assertEquals("18446744073709551615",
        codec.decodeBinaryAs(data, 0, data.length, oid8Type, String.class, null));
  }

  @Test
  void decodeBinaryAs_BigInteger_unsignedHighBit() throws SQLException {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, MAX_UNSIGNED);
    assertEquals(MAX_UNSIGNED_BIG_INTEGER,
        codec.decodeBinaryAs(data, 0, data.length, oid8Type, BigInteger.class, null));
  }

  @Test
  void decodeBinaryAs_unsupported() {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 42L);
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) oid8Type, Boolean.class, (CodecContext) null));
  }

  // ==================== Narrowing range checks (int, short) ====================
  //
  // oid8's unsigned 64-bit domain does not fit an int or a short: unlike OidCodec (which never
  // needs a bounds check narrowing its already 32-bit-wide value), decodeAsInt/decodeBinaryAs
  // (Short.class) must refuse a value that does not fit the *unsigned* target range instead of
  // silently truncating it.

  @Test
  void decodeAsInt_topOfUnsignedRange() throws SQLException {
    // 2^32 - 1 fits the unsigned int range; its int bit pattern is -1.
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 0xFFFFFFFFL);
    assertEquals(-1, PrimitiveDecoders.asInt(codec, data, oid8Type, null));
  }

  @Test
  void decodeAsInt_beyondUnsignedRange_refuses() {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 0x1_0000_0000L); // 2^32, one past the unsigned int max
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asInt(codec, data, oid8Type, null));
  }

  @Test
  void decodeAsInt_text_beyondUnsignedRange_refuses() {
    assertThrows(PSQLException.class, () -> codec.decodeAsInt("4294967296", oid8Type, null));
  }

  @Test
  void decodeBinaryAs_Short_topOfUnsignedRange() throws SQLException {
    // 2^16 - 1 fits the unsigned short range; its short bit pattern is -1.
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 0xFFFFL);
    assertEquals(Short.valueOf((short) -1),
        codec.decodeBinaryAs(data, 0, data.length, oid8Type, Short.class, null));
  }

  @Test
  void decodeBinaryAs_Short_beyondUnsignedRange_refuses() {
    byte[] data = new byte[8];
    ByteConverter.int8(data, 0, 0x1_0000L); // 2^16, one past the unsigned short max
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) oid8Type, Short.class, (CodecContext) null));
  }

  // ==================== Roundtrip ====================

  @Test
  void binaryRoundtrip_unsignedHighBit() throws SQLException {
    byte[] encoded = codec.encodeBinary(MAX_UNSIGNED, oid8Type, null);
    long decoded = PrimitiveDecoders.asLong(codec, encoded, oid8Type, null);
    assertEquals(MAX_UNSIGNED, decoded);
  }

  @Test
  void textRoundtrip_unsignedHighBit() throws SQLException {
    String encoded = codec.encodeText(MAX_UNSIGNED, oid8Type, null);
    long decoded = codec.decodeAsLong(encoded, oid8Type, null);
    assertEquals(MAX_UNSIGNED, decoded);
  }
}
