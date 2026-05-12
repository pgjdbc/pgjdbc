/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

class JsonbCodecTest {

  private JsonbCodec codec;
  private PgType jsonbType;

  @BeforeEach
  void setUp() {
    codec = JsonbCodec.INSTANCE;
    jsonbType = new PgType(
        new ObjectName("pg_catalog", "jsonb"),
        "jsonb",
        Oid.JSONB,
        'b', 'U', -1, 0, 0, 0
    );
  }

  @Test
  void getTypeName() {
    assertEquals("jsonb", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(String.class, codec.getDefaultJavaType());
  }

  @Test
  void decodeText_returnsString() throws SQLException {
    String json = "{\"key\":\"value\"}";
    assertEquals(json, codec.decodeText(json, jsonbType, null));
  }

  @Test
  void encodeText_fromString() throws SQLException {
    String json = "{\"key\":\"value\"}";
    assertEquals(json, codec.encodeText(json, jsonbType, null));
  }

  @Test
  void decodeBinary_skipsVersionByte() throws SQLException {
    String json = "{\"key\":\"value\"}";
    byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
    // Binary format: version byte (0x01) + JSON UTF-8 bytes
    byte[] data = new byte[jsonBytes.length + 1];
    data[0] = 1; // version byte
    System.arraycopy(jsonBytes, 0, data, 1, jsonBytes.length);

    assertEquals(json, codec.decodeBinary(data, jsonbType, null));
  }

  @Test
  void encodeBinary_addsVersionByte() throws SQLException {
    String json = "{\"key\":\"value\"}";
    byte[] result = codec.encodeBinary(json, jsonbType, null);

    assertEquals(1, result[0]); // version byte
    byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
    assertEquals(jsonBytes.length + 1, result.length);
    for (int i = 0; i < jsonBytes.length; i++) {
      assertEquals(jsonBytes[i], result[i + 1]);
    }
  }

  @Test
  void decodeAsString_binary() throws SQLException {
    String json = "{\"a\":1}";
    byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
    byte[] data = new byte[jsonBytes.length + 1];
    data[0] = 1;
    System.arraycopy(jsonBytes, 0, data, 1, jsonBytes.length);

    assertEquals(json, codec.decodeAsString(data, jsonbType, null));
  }

  @Test
  void decodeAsString_text() throws SQLException {
    assertEquals("{\"a\":1}", codec.decodeAsString("{\"a\":1}", jsonbType, null));
  }

  @Test
  void decodeAsInt_throws() {
    assertThrows(PSQLException.class, () -> codec.decodeAsInt("42", jsonbType, null));
  }

  @Test
  void decodeAsLong_throws() {
    assertThrows(PSQLException.class, () -> codec.decodeAsLong("42", jsonbType, null));
  }

  @Test
  void decodeAsDouble_throws() {
    assertThrows(PSQLException.class, () -> codec.decodeAsDouble("3.14", jsonbType, null));
  }

  @Test
  void decodeBinaryAs_String() throws SQLException {
    String json = "{\"a\":1}";
    byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
    byte[] data = new byte[jsonBytes.length + 1];
    data[0] = 1;
    System.arraycopy(jsonBytes, 0, data, 1, jsonBytes.length);

    assertEquals(json, codec.decodeBinaryAs(data, jsonbType, String.class, null));
  }

  @Test
  void decodeBinaryAs_unsupported() {
    byte[] data = {1, '4', '2'};
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, jsonbType, Integer.class, null));
  }

  @Test
  void decodeTextAs_String() throws SQLException {
    assertEquals("{\"a\":1}", codec.decodeTextAs("{\"a\":1}", jsonbType, String.class, null));
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    String json = "{\"nested\":{\"array\":[1,2,3]}}";
    byte[] encoded = codec.encodeBinary(json, jsonbType, null);
    assertEquals(json, codec.decodeBinary(encoded, jsonbType, null));
  }
}
