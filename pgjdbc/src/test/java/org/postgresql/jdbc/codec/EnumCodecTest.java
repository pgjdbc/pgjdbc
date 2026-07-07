/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Unit tests for EnumCodec.
 */
class EnumCodecTest {

  private CodecContext ctx;
  private PgType enumType;

  @BeforeEach
  void setUp() {
    ctx = TestCodecContext.create();

    // Create an enum type
    enumType = new PgType(
        new ObjectName("public", "status"),
        "status",
        12345,  // arbitrary OID
        'e',    // typtype = enum
        'E',    // typcategory
        -1,
        0,
        0,
        0
    );
  }

  @Test
  void getTypeName_returnsEnum() {
    assertEquals("enum", EnumCodec.INSTANCE.getTypeName());
  }

  @Test
  void getDefaultJavaType_returnsString() {
    assertEquals(String.class, EnumCodec.INSTANCE.getDefaultJavaType());
  }

  @Test
  void decodeBinary_returnsString() throws Exception {
    byte[] data = "active".getBytes(StandardCharsets.UTF_8);
    Object result = EnumCodec.INSTANCE.decodeBinary(data, enumType, ctx);
    assertEquals("active", result);
  }

  @Test
  void decodeText_returnsString() throws Exception {
    String result = (String) EnumCodec.INSTANCE.decodeText("pending", enumType, ctx);
    assertEquals("pending", result);
  }

  @Test
  void encodeBinary_fromString() throws Exception {
    byte[] result = EnumCodec.INSTANCE.encodeBinary("completed", enumType, ctx);
    assertEquals("completed", new String(result, StandardCharsets.UTF_8));
  }

  @Test
  void encodeText_fromString() throws Exception {
    String result = EnumCodec.INSTANCE.encodeText("error", enumType, ctx);
    assertEquals("error", result);
  }

  @Test
  void encodeText_fromJavaEnum() throws Exception {
    // Java enum should use name()
    String result = EnumCodec.INSTANCE.encodeText(TestStatus.ACTIVE, enumType, ctx);
    assertEquals("ACTIVE", result);
  }

  @Test
  void decodeBinaryAs_toString() throws Exception {
    byte[] data = "value".getBytes(StandardCharsets.UTF_8);
    String result = EnumCodec.INSTANCE.decodeBinaryAs(data, enumType, String.class, ctx);
    assertEquals("value", result);
  }

  @Test
  void decodeTextAs_toString() throws Exception {
    String result = EnumCodec.INSTANCE.decodeTextAs("value", enumType, String.class, ctx);
    assertEquals("value", result);
  }

  @Test
  void decodeAsInt_throwsException() {
    byte[] data = "value".getBytes(StandardCharsets.UTF_8);
    assertThrows(PSQLException.class, () ->
        PrimitiveDecoders.asInt(EnumCodec.INSTANCE, data, enumType, ctx));
  }

  @Test
  void decodeAsLong_throwsException() {
    byte[] data = "value".getBytes(StandardCharsets.UTF_8);
    assertThrows(PSQLException.class, () ->
        PrimitiveDecoders.asLong(EnumCodec.INSTANCE, data, enumType, ctx));
  }

  @Test
  void decodeAsDouble_throwsException() {
    byte[] data = "value".getBytes(StandardCharsets.UTF_8);
    assertThrows(PSQLException.class, () ->
        PrimitiveDecoders.asDouble(EnumCodec.INSTANCE, data, enumType, ctx));
  }

  @Test
  void decodeAsBoolean_throwsException() {
    byte[] data = "value".getBytes(StandardCharsets.UTF_8);
    assertThrows(PSQLException.class, () ->
        PrimitiveDecoders.asBoolean(EnumCodec.INSTANCE, data, enumType, ctx));
  }

  // Test enum for Java enum encoding
  enum TestStatus {
    ACTIVE, PENDING, COMPLETED
  }
}
