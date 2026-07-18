/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class PolygonCodecTest {

  private PolygonCodec codec;
  private PgType polygonType;

  @BeforeEach
  void setUp() {
    codec = PolygonCodec.INSTANCE;
    polygonType = new PgType(
        new ObjectName("pg_catalog", "polygon"),
        "polygon",
        Oid.POLYGON,
        'b', 'G', -1, 0, 0, 0
    );
  }

  private static PGpolygon samplePolygon() {
    return new PGpolygon(new PGpoint[] {new PGpoint(1, 2), new PGpoint(3, 4), new PGpoint(5, 6)});
  }

  @Test
  void decodeText() throws SQLException {
    Object result = codec.decodeText("((1,2),(3,4),(5,6))", polygonType, null);
    assertEquals(samplePolygon(), result);
  }

  @Test
  void encodeText() throws SQLException {
    assertEquals("((1.0,2.0),(3.0,4.0),(5.0,6.0))", codec.encodeText(samplePolygon(), polygonType, null));
  }

  @Test
  void decodeBinary() throws SQLException {
    PGpolygon expected = samplePolygon();
    byte[] data = new byte[4 + expected.points.length * 16];
    ByteConverter.int4(data, 0, expected.points.length);
    int pos = 4;
    for (PGpoint point : expected.points) {
      ByteConverter.float8(data, pos, point.x);
      ByteConverter.float8(data, pos + 8, point.y);
      pos += 16;
    }

    Object result = codec.decodeBinary(data, 0, data.length, polygonType, null);
    assertEquals(expected, result);
  }

  @Test
  void decodeBinary_wrongLength() {
    assertThrows(PSQLException.class,
        () -> codec.decodeBinary(new byte[2], 0, 2, polygonType, null));
  }

  @Test
  void encodeBinary_wrongType() {
    assertThrows(PSQLException.class,
        () -> codec.encodeBinary("not a polygon", polygonType, null));
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    PGpolygon original = samplePolygon();
    byte[] encoded = codec.encodeBinary(original, polygonType, null);
    Object decoded = codec.decodeBinary(encoded, 0, encoded.length, polygonType, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip() throws SQLException {
    PGpolygon original = samplePolygon();
    String encoded = codec.encodeText(original, polygonType, null);
    Object decoded = codec.decodeText(encoded, polygonType, null);
    assertEquals(original, decoded);
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("polygon", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(PGpolygon.class, codec.getDefaultJavaType());
  }
}
