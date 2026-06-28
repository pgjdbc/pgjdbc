/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Unit tests for ByteaCodec.
 */
class ByteaCodecTest {

  private CodecContext ctx;
  private PgType byteaType;

  @BeforeEach
  void setUp() {
    ctx = TestCodecContext.create();

    byteaType = new PgType(
        new ObjectName("pg_catalog", "bytea"),
        "bytea",
        17,  // Oid.BYTEA
        'b',
        'U',
        -1,
        0,
        1001,  // bytea array oid
        0
    );
  }

  @Test
  void getTypeName_returnsBytea() {
    assertEquals("bytea", ByteaCodec.INSTANCE.getTypeName());
  }

  @Test
  void getDefaultJavaType_returnsByteArray() {
    assertEquals(byte[].class, ByteaCodec.INSTANCE.getDefaultJavaType());
  }

  @Test
  void decodeBinary_returnsCopy() throws Exception {
    byte[] original = {0x01, 0x02, 0x03};
    byte[] result = (byte[]) ByteaCodec.INSTANCE.decodeBinary(original, byteaType, ctx);

    assertArrayEquals(original, result);
    assertNotSame(original, result, "Should return a copy, not the original");
  }

  @Test
  void decodeAsBytes_returnsCopy() throws Exception {
    byte[] original = {0x04, 0x05, 0x06};
    byte[] result = ByteaCodec.INSTANCE.decodeAsBytes(original, byteaType, ctx);

    assertArrayEquals(original, result);
    assertNotSame(original, result, "Should return a copy, not the original");
  }

  @Test
  void encodeBinary_fromByteArray() throws Exception {
    byte[] input = {0x07, 0x08, 0x09};
    byte[] result = ByteaCodec.INSTANCE.encodeBinary(input, byteaType, ctx);
    assertArrayEquals(input, result);
  }

  @Test
  void decodeBinaryAs_toByteArray() throws Exception {
    byte[] data = {0x0A, 0x0B, 0x0C};
    byte[] result = ByteaCodec.INSTANCE.decodeBinaryAs(data, byteaType, byte[].class, ctx);
    assertArrayEquals(data, result);
  }

  @Test
  void decodeBinaryAs_toInputStream() throws Exception {
    byte[] data = {0x0D, 0x0E, 0x0F};
    InputStream result = ByteaCodec.INSTANCE.decodeBinaryAs(data, byteaType, InputStream.class, ctx);

    assertNotNull(result);
    byte[] read = new byte[data.length];
    int bytesRead = result.read(read);

    assertEquals(data.length, bytesRead);
    assertArrayEquals(data, read);
    result.close();
  }

  @Test
  void decodeTextAs_toInputStream() throws Exception {
    // bytea text format is hex: \\x0d0e0f
    String hexData = "\\x0d0e0f";
    InputStream result = ByteaCodec.INSTANCE.decodeTextAs(hexData, byteaType, InputStream.class, ctx);

    assertNotNull(result);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buf = new byte[1024];
    int n;
    while ((n = result.read(buf)) != -1) {
      baos.write(buf, 0, n);
    }
    byte[] read = baos.toByteArray();

    assertArrayEquals(new byte[]{0x0d, 0x0e, 0x0f}, read);
    result.close();
  }

  @Test
  void decodeBinaryAs_toString() throws Exception {
    byte[] data = {0x01, 0x02, 0x03};
    String result = ByteaCodec.INSTANCE.decodeBinaryAs(data, byteaType, String.class, ctx);
    assertNotNull(result);
    // Should return hex-encoded string
  }

  @Test
  void decodeAsInt_throwsException() {
    byte[] data = {0x01, 0x02};
    assertThrows(PSQLException.class, () ->
        ByteaCodec.INSTANCE.decodeAsInt(data, byteaType, ctx));
  }

  @Test
  void decodeAsLong_throwsException() {
    byte[] data = {0x01, 0x02};
    assertThrows(PSQLException.class, () ->
        ByteaCodec.INSTANCE.decodeAsLong(data, byteaType, ctx));
  }

  @Test
  void decodeAsDouble_throwsException() {
    byte[] data = {0x01, 0x02};
    assertThrows(PSQLException.class, () ->
        ByteaCodec.INSTANCE.decodeAsDouble(data, byteaType, ctx));
  }

  @Test
  void decodeBinaryAs_unsupportedType_throwsException() {
    byte[] data = {0x01, 0x02};
    assertThrows(PSQLException.class, () ->
        ByteaCodec.INSTANCE.decodeBinaryAs(data, byteaType, Integer.class, ctx));
  }
}
