/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.geometric.PGline;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class LineCodecTest {

  private LineCodec codec;
  private PgType lineType;

  @BeforeEach
  void setUp() {
    codec = LineCodec.INSTANCE;
    lineType = new PgType(
        new ObjectName("pg_catalog", "line"),
        "line",
        Oid.LINE,
        'b', 'G', -1, 0, 0, 0
    );
  }

  @Test
  void decodeBinary() throws SQLException {
    byte[] data = new byte[24];
    ByteConverter.float8(data, 0, 1);
    ByteConverter.float8(data, 8, 2);
    ByteConverter.float8(data, 16, 3);

    Object result = codec.decodeBinary(data, 0, data.length, lineType, null);
    assertEquals(new PGline(1, 2, 3), result);
  }

  @Test
  void decodeBinary_wrongLength() {
    assertThrows(PSQLException.class,
        () -> codec.decodeBinary(new byte[16], 0, 16, lineType, null));
  }

  @Test
  void decodeText() throws SQLException {
    Object result = codec.decodeText("{1,2,3}", lineType, null);
    assertEquals(new PGline(1, 2, 3), result);
  }

  @Test
  void encodeText() throws SQLException {
    String result = codec.encodeText(new PGline(1, 2, 3), lineType, null);
    assertEquals("{1.0,2.0,3.0}", result);
  }

  @Test
  void encodeBinary() throws SQLException {
    PGline line = new PGline(1, 2, 3);
    byte[] result = codec.encodeBinary(line, lineType, null);
    assertEquals(24, result.length);
    Object decoded = codec.decodeBinary(result, 0, result.length, lineType, null);
    assertEquals(line, decoded);
  }

  @Test
  void encodeBinary_wrongType() {
    assertThrows(PSQLException.class,
        () -> codec.encodeBinary("not a line", lineType, null));
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    PGline original = new PGline(1, 2, 3);
    byte[] encoded = codec.encodeBinary(original, lineType, null);
    Object decoded = codec.decodeBinary(encoded, 0, encoded.length, lineType, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip() throws SQLException {
    PGline original = new PGline(1, 2, 3);
    String encoded = codec.encodeText(original, lineType, null);
    Object decoded = codec.decodeText(encoded, lineType, null);
    assertEquals(original, decoded);
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("line", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(PGline.class, codec.getDefaultJavaType());
  }
}
