/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;

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
  void getPrimaryTypeName() {
    assertEquals("varchar", codec.getPrimaryTypeName());
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
    assertEquals("hello", codec.decodeBinary(data, 0, data.length, varcharType, ctx));
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

  @Test
  void decodeTextAs_Date() throws SQLException {
    assertEquals(
        org.postgresql.jdbc.TemporalCodecs.decodeDateText("2024-01-02", ctx),
        codec.decodeTextAs("2024-01-02", varcharType, java.sql.Date.class, ctx));
  }

  @Test
  void decodeBinaryAs_Timestamp() throws SQLException {
    byte[] data = "2024-01-02 12:34:56".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        org.postgresql.jdbc.TemporalCodecs.decodeTimestampText("2024-01-02 12:34:56", ctx),
        codec.decodeBinaryAs(data, 0, data.length, varcharType, Timestamp.class, ctx));
  }
}
