/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.geometric.PGpoint;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class PointCodecTest {

  private PointCodec codec;
  private PgType pointType;

  @BeforeEach
  void setUp() {
    codec = PointCodec.INSTANCE;
    pointType = new PgType(
        new ObjectName("pg_catalog", "point"),
        "point",
        Oid.POINT,
        'b', 'G', -1, 0, 0, 0
    );
  }

  @Test
  void decodeBinary() throws SQLException {
    byte[] data = new byte[16];
    ByteConverter.float8(data, 0, 1.5);
    ByteConverter.float8(data, 8, 2.5);

    Object result = codec.decodeBinary(data, 0, data.length, pointType, null);
    assertEquals(new PGpoint(1.5, 2.5), result);
  }

  @Test
  void decodeBinary_wrongLength() {
    assertThrows(PSQLException.class,
        () -> codec.decodeBinary(new byte[8], 0, 8, pointType, null));
  }

  @Test
  void decodeText() throws SQLException {
    Object result = codec.decodeText("(1.5,2.5)", pointType, null);
    assertEquals(new PGpoint(1.5, 2.5), result);
  }

  @Test
  void encodeBinary() throws SQLException {
    PGpoint point = new PGpoint(3.14, 2.72);
    byte[] result = codec.encodeBinary(point, pointType, null);
    assertEquals(16, result.length);
    Object decoded = codec.decodeBinary(result, 0, result.length, pointType, null);
    assertEquals(point, decoded);
  }

  @Test
  void encodeBinary_wrongType() {
    assertThrows(PSQLException.class,
        () -> codec.encodeBinary("not a point", pointType, null));
  }

  @Test
  void encodeText() throws SQLException {
    String result = codec.encodeText(new PGpoint(1.5, 2.5), pointType, null);
    assertEquals("(1.5,2.5)", result);
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    PGpoint original = new PGpoint(3.14, 2.72);
    byte[] encoded = codec.encodeBinary(original, pointType, null);
    Object decoded = codec.decodeBinary(encoded, 0, encoded.length, pointType, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip() throws SQLException {
    PGpoint original = new PGpoint(3.14, 2.72);
    String encoded = codec.encodeText(original, pointType, null);
    Object decoded = codec.decodeText(encoded, pointType, null);
    assertEquals(original, decoded);
  }

  @Test
  void decodeTextAs_PGpoint() throws SQLException {
    PGpoint result = codec.decodeTextAs("(1,2)", pointType, PGpoint.class, null);
    assertEquals(new PGpoint(1, 2), result);
  }

  @Test
  void decodeTextAs_unsupported() {
    assertThrows(PSQLException.class,
        () -> codec.decodeTextAs("(1,2)", pointType, String.class, null));
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("point", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(PGpoint.class, codec.getDefaultJavaType());
  }
}
