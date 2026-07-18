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
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

class JsonCodecTest {

  private JsonCodec codec;
  private PgType jsonType;

  @BeforeEach
  void setUp() {
    codec = JsonCodec.INSTANCE;
    jsonType = new PgType(
        new ObjectName("pg_catalog", "json"),
        "json",
        Oid.JSON,
        'b', 'U', -1, 0, 0, 0
    );
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("json", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(PGobject.class, codec.getDefaultJavaType());
  }

  @Test
  void decodeText_returnsPGobject() throws SQLException {
    String json = "{\"key\":\"value\"}";
    PGobject decoded = (PGobject) codec.decodeText(json, jsonType, null);
    assertEquals("json", decoded.getType());
    assertEquals(json, decoded.getValue());
  }

  @Test
  void encodeText_fromString() throws SQLException {
    String json = "{\"key\":\"value\"}";
    assertEquals(json, codec.encodeText(json, jsonType, null));
  }

  @Test
  void decodeBinary_utf8() throws SQLException {
    String json = "{\"key\":\"value\"}";
    byte[] data = json.getBytes(StandardCharsets.UTF_8);
    PGobject decoded = (PGobject) codec.decodeBinary(data, 0, data.length, jsonType, null);
    assertEquals("json", decoded.getType());
    assertEquals(json, decoded.getValue());
  }

  @Test
  void encodeBinary_toUtf8() throws SQLException {
    String json = "{\"key\":\"value\"}";
    byte[] result = codec.encodeBinary(json, jsonType, null);
    assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), result);
  }

  @Test
  void decodeBinary_unicode() throws SQLException {
    String json = "{\"name\":\"éè\"}";
    byte[] data = json.getBytes(StandardCharsets.UTF_8);
    PGobject decoded = (PGobject) codec.decodeBinary(data, 0, data.length, jsonType, null);
    assertEquals(json, decoded.getValue());
  }

  @Test
  void decodeAsString_text() throws SQLException {
    String json = "[1,2,3]";
    assertEquals(json, codec.decodeAsString(json, jsonType, null));
  }

  @Test
  void decodeAsString_binary() throws SQLException {
    String json = "[1,2,3]";
    byte[] data = json.getBytes(StandardCharsets.UTF_8);
    assertEquals(json, codec.decodeAsString(data, 0, data.length, jsonType, null));
  }

  @Test
  void decodeAsInt_throws() {
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asInt(codec, "42", jsonType, null));
  }

  @Test
  void decodeAsLong_throws() {
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asLong(codec, "42", jsonType, null));
  }

  @Test
  void decodeAsDouble_throws() {
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asDouble(codec, "3.14", jsonType, null));
  }

  @Test
  void decodeBinaryAs_String() throws SQLException {
    byte[] data = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    assertEquals("{\"a\":1}", codec.decodeBinaryAs(data, 0, data.length, jsonType, String.class, null));
  }

  @Test
  void decodeBinaryAs_Object_returnsPGobject() throws SQLException {
    byte[] data = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    PGobject decoded = (PGobject) codec.decodeBinaryAs(data, 0, data.length, jsonType, Object.class, null);
    assertEquals("{\"a\":1}", decoded.getValue());
  }

  @Test
  void decodeBinaryAs_unsupported() {
    byte[] data = "42".getBytes(StandardCharsets.UTF_8);
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) jsonType, Integer.class, (CodecContext) null));
  }

  @Test
  void decodeTextAs_String() throws SQLException {
    assertEquals("{\"a\":1}", codec.decodeTextAs("{\"a\":1}", jsonType, String.class, null));
  }

  @Test
  void decodeTextAs_unsupported() {
    assertThrows(PSQLException.class,
        () -> codec.decodeTextAs("{\"a\":1}", jsonType, Integer.class, null));
  }

  @Test
  void textRoundtrip() throws SQLException {
    String json = "{\"nested\":{\"array\":[1,2,3]}}";
    String encoded = codec.encodeText(json, jsonType, null);
    PGobject decoded = (PGobject) codec.decodeText(encoded, jsonType, null);
    assertEquals(json, decoded.getValue());
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    String json = "{\"nested\":{\"array\":[1,2,3]}}";
    byte[] encoded = codec.encodeBinary(json, jsonType, null);
    PGobject decoded = (PGobject) codec.decodeBinary(encoded, 0, encoded.length, jsonType, null);
    assertEquals(json, decoded.getValue());
  }
}
