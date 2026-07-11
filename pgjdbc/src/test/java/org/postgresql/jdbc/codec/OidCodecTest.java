/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.sql.SQLException;

class OidCodecTest {

  private OidCodec codec;
  private PgType oidType;

  @BeforeEach
  void setUp() {
    codec = OidCodec.INSTANCE;
    oidType = new PgType(
        new ObjectName("pg_catalog", "oid"),
        "oid",
        Oid.OID,
        'b', 'N', -1, 0, 0, 0
    );
  }

  @Test
  void getTypeName() {
    assertEquals("oid", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Long.class, codec.getDefaultJavaType());
  }

  // ==================== Text Decoding ====================

  @Test
  void decodeText_positiveValue() throws SQLException {
    assertEquals(42L, codec.decodeText("42", oidType, null));
  }

  @Test
  void decodeText_maxUnsigned() throws SQLException {
    // OID max is 4294967295 (unsigned 32-bit)
    assertEquals(4294967295L, codec.decodeText("4294967295", oidType, null));
  }

  @Test
  void decodeAsLong_charSlice() throws SQLException {
    // The fast path reads the digits off the slice with no String and no box.
    char[] buf = "x4294967295y".toCharArray();
    assertEquals(4294967295L, codec.decodeAsLong(buf, 1, 10, oidType, null));
    // A leading '+' is rejected by the fast path and handled by the String fallback.
    char[] plus = "+7".toCharArray();
    assertEquals(7L, codec.decodeAsLong(plus, 0, plus.length, oidType, null));
  }

  @Test
  void decodeText_zero() throws SQLException {
    assertEquals(0L, codec.decodeText("0", oidType, null));
  }

  // ==================== Binary Decoding ====================

  @Test
  void decodeBinary_positiveValue() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);
    assertEquals(42L, codec.decodeBinary(data, 0, data.length, oidType, null));
  }

  @Test
  void decodeBinary_unsignedHighBit() throws SQLException {
    // 0xFFFFFFFF should be 4294967295L (unsigned)
    byte[] data = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    long result = PrimitiveDecoders.asLong(codec, data, oidType, null);
    assertEquals(4294967295L, result);
  }

  @Test
  void decodeBinary_invalidLength() {
    byte[] data = new byte[3]; // wrong length
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asLong(codec, data, oidType, null));
  }

  // ==================== Encoding ====================

  @Test
  void encodeText_value() throws SQLException {
    assertEquals("42", codec.encodeText(42L, oidType, null));
  }

  @Test
  void encodeBinary_value() throws SQLException {
    byte[] result = codec.encodeBinary(42L, oidType, null);
    byte[] expected = new byte[4];
    ByteConverter.int4(expected, 0, 42);
    assertArrayEquals(expected, result);
  }

  @Test
  void encodeBinary_fromString() throws SQLException {
    byte[] result = codec.encodeBinary("42", oidType, null);
    byte[] expected = new byte[4];
    ByteConverter.int4(expected, 0, 42);
    assertArrayEquals(expected, result);
  }

  // ==================== Type Conversions ====================

  @Test
  void decodeAsInt_binary() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);
    assertEquals(42, PrimitiveDecoders.asInt(codec, data, oidType, null));
  }

  @Test
  void decodeAsInt_text() throws SQLException {
    assertEquals(42, codec.decodeAsInt("42", oidType, null));
  }

  @Test
  void decodeAsInt_largeOidWrapsUnsigned() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 0xFFFFFFFF); // oid 4294967295, above Integer.MAX_VALUE
    // getInt reinterprets the raw 32-bit value as signed (unsigned 32-bit wrap), like the legacy
    // driver; it does not throw. getLong keeps the unsigned value and getString renders it unsigned.
    assertEquals(-1, PrimitiveDecoders.asInt(codec, data, oidType, null));
    assertEquals(Integer.valueOf(-1),
        codec.decodeBinaryAs(data, 0, data.length, oidType, Integer.class, null));
    assertEquals(4294967295L, PrimitiveDecoders.asLong(codec, data, oidType, null));
    assertEquals("4294967295", codec.decodeBinaryAs(data, 0, data.length, oidType, String.class, null));
  }

  @Test
  void decodeAsLong_binary() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);
    assertEquals(42L, PrimitiveDecoders.asLong(codec, data, oidType, null));
  }

  @Test
  void decodeAsLong_text() throws SQLException {
    assertEquals(42L, codec.decodeAsLong("42", oidType, null));
  }

  @Test
  void decodeAsDouble_binary() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);
    assertEquals(42.0, PrimitiveDecoders.asDouble(codec, data, oidType, null));
  }

  @Test
  void decodeAsBigDecimal_binary() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);
    assertEquals(BigDecimal.valueOf(42), codec.decodeAsBigDecimal(data, 0, data.length, oidType, null));
  }

  // ==================== decodeBinaryAs ====================

  @Test
  void decodeBinaryAs_Long() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);
    assertEquals(Long.valueOf(42), codec.decodeBinaryAs(data, 0, data.length, oidType, Long.class, null));
  }

  @Test
  void decodeBinaryAs_Integer() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);
    assertEquals(Integer.valueOf(42), codec.decodeBinaryAs(data, 0, data.length, oidType, Integer.class, null));
  }

  @Test
  void decodeBinaryAs_String() throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);
    assertEquals("42", codec.decodeBinaryAs(data, 0, data.length, oidType, String.class, null));
  }

  @Test
  void decodeBinaryAs_unsupported() {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, 42);
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) oidType, Boolean.class, (CodecContext) null));
  }

  // ==================== Roundtrip ====================

  @Test
  void binaryRoundtrip() throws SQLException {
    long original = 12345L;
    byte[] encoded = codec.encodeBinary(original, oidType, null);
    long decoded = PrimitiveDecoders.asLong(codec, encoded, oidType, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip() throws SQLException {
    long original = 12345L;
    String encoded = codec.encodeText(original, oidType, null);
    long decoded = codec.decodeAsLong(encoded, oidType, null);
    assertEquals(original, decoded);
  }
}
