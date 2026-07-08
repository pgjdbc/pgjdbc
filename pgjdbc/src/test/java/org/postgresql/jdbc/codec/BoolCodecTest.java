/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;

class BoolCodecTest {

  private BoolCodec codec;
  private PgType boolType;
  private CodecContext ctxConvert;
  private CodecContext ctxNoConvert;

  @BeforeEach
  void setUp() {
    codec = BoolCodec.INSTANCE;
    boolType = new PgType(
        new ObjectName("pg_catalog", "bool"),
        "boolean",
        Oid.BOOL,
        'b', 'B', -1, 0, 0, 0
    );
    ctxConvert = TestCodecContext.withConvertBooleanToNumeric(true);
    ctxNoConvert = TestCodecContext.withConvertBooleanToNumeric(false);
  }

  @Test
  void decodeBinary_true() throws SQLException {
    byte[] data = new byte[]{1};
    Object result = codec.decodeBinary(data, 0, data.length, boolType, null);
    assertEquals(true, result);
  }

  @Test
  void decodeBinary_false() throws SQLException {
    byte[] data = new byte[]{0};
    Object result = codec.decodeBinary(data, 0, data.length, boolType, null);
    assertEquals(false, result);
  }

  @Test
  void decodeText_true_t() throws SQLException {
    Object result = codec.decodeText("t", boolType, null);
    assertEquals(true, result);
  }

  @Test
  void decodeText_false_f() throws SQLException {
    Object result = codec.decodeText("f", boolType, null);
    assertEquals(false, result);
  }

  @Test
  void decodeText_true_variants() throws SQLException {
    for (String val : new String[]{"true", "TRUE", "True", "yes", "YES", "on", "ON", "1"}) {
      Object result = codec.decodeText(val, boolType, null);
      assertEquals(true, result, "Expected true for: " + val);
    }
  }

  @Test
  void decodeText_false_variants() throws SQLException {
    for (String val : new String[]{"false", "FALSE", "False", "no", "NO", "off", "OFF", "0"}) {
      Object result = codec.decodeText(val, boolType, null);
      assertEquals(false, result, "Expected false for: " + val);
    }
  }

  @Test
  void encodeBinary_true() throws SQLException {
    byte[] result = codec.encodeBinary(true, boolType, null);
    assertArrayEquals(new byte[]{1}, result);
  }

  @Test
  void encodeBinary_false() throws SQLException {
    byte[] result = codec.encodeBinary(false, boolType, null);
    assertArrayEquals(new byte[]{0}, result);
  }

  @Test
  void encodeText_true() throws SQLException {
    String result = codec.encodeText(true, boolType, null);
    assertEquals("t", result);
  }

  @Test
  void encodeText_false() throws SQLException {
    String result = codec.encodeText(false, boolType, null);
    assertEquals("f", result);
  }

  @Test
  void decodeAsBoolean_binary_true() throws SQLException {
    byte[] data = new byte[]{1};
    assertTrue(PrimitiveDecoders.asBoolean(codec, data, boolType, null));
  }

  @Test
  void decodeAsBoolean_binary_false() throws SQLException {
    byte[] data = new byte[]{0};
    assertFalse(PrimitiveDecoders.asBoolean(codec, data, boolType, null));
  }

  @Test
  void decodeAsBoolean_text_true() throws SQLException {
    assertTrue(codec.decodeAsBoolean("t", boolType, null));
  }

  @Test
  void decodeAsBoolean_text_false() throws SQLException {
    assertFalse(codec.decodeAsBoolean("f", boolType, null));
  }

  @Test
  void decodeAsInt_binary_true() throws SQLException {
    byte[] data = new byte[]{1};
    assertEquals(1, PrimitiveDecoders.asInt(codec, data, boolType, ctxConvert));
  }

  @Test
  void decodeAsInt_binary_false() throws SQLException {
    byte[] data = new byte[]{0};
    assertEquals(0, PrimitiveDecoders.asInt(codec, data, boolType, ctxConvert));
  }

  @Test
  void decodeAsInt_binary_throwsWhenConvertDisabled() {
    // convertBooleanToNumeric=false → BOOL→int is unsupported
    assertThrows(PSQLException.class,
        () -> PrimitiveDecoders.asInt(codec, new byte[]{1}, boolType, ctxNoConvert));
  }

  @Test
  void decodeAsInt_text_throwsWhenConvertDisabled() {
    assertThrows(PSQLException.class,
        () -> codec.decodeAsInt("t", boolType, ctxNoConvert));
  }

  @Test
  void decodeAsInt_text_true() throws SQLException {
    assertEquals(1, codec.decodeAsInt("t", boolType, ctxConvert));
  }

  @Test
  void decodeAsInt_text_false() throws SQLException {
    assertEquals(0, codec.decodeAsInt("f", boolType, ctxConvert));
  }

  @Test
  void decodeAsLong_throwsWhenConvertDisabled() {
    assertThrows(PSQLException.class,
        () -> PrimitiveDecoders.asLong(codec, new byte[]{1}, boolType, ctxNoConvert));
    assertThrows(PSQLException.class,
        () -> codec.decodeAsLong("t", boolType, ctxNoConvert));
  }

  @Test
  void decodeAsFloat_throwsWhenConvertDisabled() {
    assertThrows(PSQLException.class,
        () -> PrimitiveDecoders.asFloat(codec, new byte[]{1}, boolType, ctxNoConvert));
    assertThrows(PSQLException.class,
        () -> codec.decodeAsFloat("t", boolType, ctxNoConvert));
  }

  @Test
  void decodeAsDouble_throwsWhenConvertDisabled() {
    assertThrows(PSQLException.class,
        () -> PrimitiveDecoders.asDouble(codec, new byte[]{1}, boolType, ctxNoConvert));
    assertThrows(PSQLException.class,
        () -> codec.decodeAsDouble("t", boolType, ctxNoConvert));
  }

  @Test
  void decodeAsBigDecimal_text_true() throws SQLException {
    assertEquals(BigDecimal.ONE, codec.decodeAsBigDecimal("t", boolType, ctxConvert));
  }

  @Test
  void decodeAsBigDecimal_throwsWhenConvertDisabled() {
    assertThrows(PSQLException.class,
        () -> {
          byte[] data = new byte[]{1};
          codec.decodeAsBigDecimal(data, 0, data.length, (TypeDescriptor) boolType, ctxNoConvert);
        });
  }

  @Test
  void decodeAsString_binary_true() throws SQLException {
    byte[] data = new byte[]{1};
    assertEquals("true", codec.decodeAsString(data, 0, data.length, boolType, null));
  }

  @Test
  void decodeAsString_binary_false() throws SQLException {
    byte[] data = new byte[]{0};
    assertEquals("false", codec.decodeAsString(data, 0, data.length, boolType, null));
  }

  @Test
  void binaryRoundtrip_true() throws SQLException {
    byte[] encoded = codec.encodeBinary(true, boolType, null);
    boolean decoded = PrimitiveDecoders.asBoolean(codec, encoded, boolType, null);
    assertTrue(decoded);
  }

  @Test
  void binaryRoundtrip_false() throws SQLException {
    byte[] encoded = codec.encodeBinary(false, boolType, null);
    boolean decoded = PrimitiveDecoders.asBoolean(codec, encoded, boolType, null);
    assertFalse(decoded);
  }

  @Test
  void getTypeName() {
    assertEquals("bool", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(Boolean.class, codec.getDefaultJavaType());
  }
}
