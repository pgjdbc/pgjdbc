/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PGUnknownBinary;
import org.postgresql.util.PGobject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class FallbackCodecTest {

  private FallbackCodec codec;
  private PgType unknownType;

  @BeforeEach
  void setUp() {
    codec = FallbackCodec.INSTANCE;
    unknownType = new PgType(
        new ObjectName("pg_catalog", "unknown_type"),
        "unknown_type",
        99999,
        'b', 'X', -1, 0, 0, 0
    );
  }

  @Test
  void decodeBinary_returnsPGUnknownBinary() throws SQLException {
    byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04};
    Object result = codec.decodeBinary(data, unknownType, null);

    assertInstanceOf(PGUnknownBinary.class, result);
    PGUnknownBinary unknown = (PGUnknownBinary) result;
    assertEquals("unknown_type", unknown.getType());
    assertArrayEquals(data, unknown.getBytes());
  }

  @Test
  void decodeText_returnsPGobject() throws SQLException {
    String data = "some text value";
    Object result = codec.decodeText(data, unknownType, null);

    assertInstanceOf(PGobject.class, result);
    PGobject pgobj = (PGobject) result;
    assertEquals("unknown_type", pgobj.getType());
    assertEquals(data, pgobj.getValue());
  }

  @Test
  void decodeBinary_emptyData() throws SQLException {
    byte[] data = new byte[0];
    Object result = codec.decodeBinary(data, unknownType, null);

    assertInstanceOf(PGUnknownBinary.class, result);
    PGUnknownBinary unknown = (PGUnknownBinary) result;
    assertArrayEquals(new byte[0], unknown.getBytes());
  }

  @Test
  void decodeText_emptyString() throws SQLException {
    Object result = codec.decodeText("", unknownType, null);

    assertInstanceOf(PGobject.class, result);
    PGobject pgobj = (PGobject) result;
    assertEquals("", pgobj.getValue());
  }

  @Test
  void getTypeName() {
    assertEquals("unknown", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(PGobject.class, codec.getDefaultJavaType());
  }
}
