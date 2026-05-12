/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

class VarcharCodecTest {

  private VarcharCodec codec;
  private PgType varcharType;
  private CodecContext ctx;

  @BeforeEach
  void setUp() {
    codec = VarcharCodec.INSTANCE;
    varcharType = new PgType(
        new ObjectName("pg_catalog", "varchar"),
        "character varying",
        Oid.VARCHAR,
        'b', 'S', -1, 0, 0, 0
    );
    ctx = TestCodecContext.create();
  }

  @Test
  void getTypeName() {
    assertEquals("varchar", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(String.class, codec.getDefaultJavaType());
  }

  @Test
  void decodeText() throws SQLException {
    assertEquals("hello", codec.decodeText("hello", varcharType, ctx));
  }

  @Test
  void encodeText() throws SQLException {
    assertEquals("hello", codec.encodeText("hello", varcharType, ctx));
  }

  @Test
  void decodeBinary() throws SQLException {
    byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
    assertEquals("hello", codec.decodeBinary(data, varcharType, ctx));
  }

  @Test
  void encodeBinary() throws SQLException {
    byte[] result = codec.encodeBinary("hello", varcharType, ctx);
    assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), result);
  }

  @Test
  void decodeAsInt() throws SQLException {
    assertEquals(42, codec.decodeAsInt("42", varcharType, ctx));
  }

  @Test
  void decodeAsBoolean() throws SQLException {
    assertEquals(true, codec.decodeAsBoolean("true", varcharType, ctx));
  }
}
