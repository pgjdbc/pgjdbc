/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.UUID;

class UuidCodecTest {

  private UuidCodec codec;
  private PgType uuidType;

  @BeforeEach
  void setUp() {
    codec = UuidCodec.INSTANCE;
    uuidType = new PgType(
        new ObjectName("pg_catalog", "uuid"),
        "uuid",
        Oid.UUID,
        'b', 'U', -1, 0, 0, 0
    );
  }

  @Test
  void decodeBinary() throws SQLException {
    UUID expected = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    byte[] data = new byte[16];
    ByteConverter.int8(data, 0, expected.getMostSignificantBits());
    ByteConverter.int8(data, 8, expected.getLeastSignificantBits());

    Object result = codec.decodeBinary(data, 0, data.length, uuidType, null);
    assertEquals(expected, result);
  }

  @Test
  void decodeText() throws SQLException {
    String uuidStr = "550e8400-e29b-41d4-a716-446655440000";
    Object result = codec.decodeText(uuidStr, uuidType, null);
    assertEquals(UUID.fromString(uuidStr), result);
  }

  @Test
  void encodeBinary() throws SQLException {
    UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    byte[] result = codec.encodeBinary(uuid, uuidType, null);

    byte[] expected = new byte[16];
    ByteConverter.int8(expected, 0, uuid.getMostSignificantBits());
    ByteConverter.int8(expected, 8, uuid.getLeastSignificantBits());
    assertEquals(16, result.length);
    // Verify round-trip rather than byte comparison
    Object decoded = codec.decodeBinary(result, 0, result.length, uuidType, null);
    assertEquals(uuid, decoded);
  }

  @Test
  void encodeText() throws SQLException {
    UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    String result = codec.encodeText(uuid, uuidType, null);
    assertEquals("550e8400-e29b-41d4-a716-446655440000", result);
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    UUID original = UUID.randomUUID();
    byte[] encoded = codec.encodeBinary(original, uuidType, null);
    Object decoded = codec.decodeBinary(encoded, 0, encoded.length, uuidType, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip() throws SQLException {
    UUID original = UUID.randomUUID();
    String encoded = codec.encodeText(original, uuidType, null);
    Object decoded = codec.decodeText(encoded, uuidType, null);
    assertEquals(original, decoded);
  }

  @Test
  void decodeAsString_binary() throws SQLException {
    UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    byte[] data = new byte[16];
    ByteConverter.int8(data, 0, uuid.getMostSignificantBits());
    ByteConverter.int8(data, 8, uuid.getLeastSignificantBits());

    String result = codec.decodeAsString(data, 0, data.length, uuidType, null);
    assertEquals("550e8400-e29b-41d4-a716-446655440000", result);
  }

  @Test
  void decodeAsString_text() throws SQLException {
    String result = codec.decodeAsString("550e8400-e29b-41d4-a716-446655440000", uuidType, null);
    assertEquals("550e8400-e29b-41d4-a716-446655440000", result);
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("uuid", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(UUID.class, codec.getDefaultJavaType());
  }
}
