/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.Oid;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class PathCodecTest {

  private PathCodec codec;
  private PgType pathType;

  @BeforeEach
  void setUp() {
    codec = PathCodec.INSTANCE;
    pathType = new PgType(
        new ObjectName("pg_catalog", "path"),
        "path",
        Oid.PATH,
        'b', 'G', -1, 0, 0, 0
    );
  }

  private static PGpath samplePath(boolean open) {
    return new PGpath(new PGpoint[] {new PGpoint(1, 2), new PGpoint(3, 4), new PGpoint(5, 6)}, open);
  }

  @Test
  void decodeText_open() throws SQLException {
    Object result = codec.decodeText("[(1,2),(3,4),(5,6)]", pathType, null);
    assertEquals(samplePath(true), result);
    assertTrue(((PGpath) result).isOpen());
  }

  @Test
  void decodeText_closed() throws SQLException {
    Object result = codec.decodeText("((1,2),(3,4),(5,6))", pathType, null);
    assertEquals(samplePath(false), result);
    assertTrue(((PGpath) result).isClosed());
  }

  @Test
  void decodeText_malformed() {
    assertThrows(PSQLException.class,
        () -> codec.decodeText("{1,2,3}", pathType, null));
  }

  @Test
  void encodeText_open() throws SQLException {
    assertEquals("[(1.0,2.0),(3.0,4.0),(5.0,6.0)]", codec.encodeText(samplePath(true), pathType, null));
  }

  @Test
  void encodeText_closed() throws SQLException {
    assertEquals("((1.0,2.0),(3.0,4.0),(5.0,6.0))", codec.encodeText(samplePath(false), pathType, null));
  }

  @Test
  void decodeBinary_open() throws SQLException {
    byte[] data = binaryOf(samplePath(true));
    Object result = codec.decodeBinary(data, 0, data.length, pathType, null);
    assertEquals(samplePath(true), result);
  }

  @Test
  void decodeBinary_closed() throws SQLException {
    byte[] data = binaryOf(samplePath(false));
    Object result = codec.decodeBinary(data, 0, data.length, pathType, null);
    assertEquals(samplePath(false), result);
  }

  @Test
  void decodeBinary_wrongLength() {
    assertThrows(PSQLException.class,
        () -> codec.decodeBinary(new byte[3], 0, 3, pathType, null));
  }

  @Test
  void encodeBinary_wrongType() {
    assertThrows(PSQLException.class,
        () -> codec.encodeBinary("not a path", pathType, null));
  }

  @Test
  void binaryRoundtrip_open() throws SQLException {
    PGpath original = samplePath(true);
    byte[] encoded = codec.encodeBinary(original, pathType, null);
    Object decoded = codec.decodeBinary(encoded, 0, encoded.length, pathType, null);
    assertEquals(original, decoded);
  }

  @Test
  void binaryRoundtrip_closed() throws SQLException {
    PGpath original = samplePath(false);
    byte[] encoded = codec.encodeBinary(original, pathType, null);
    Object decoded = codec.decodeBinary(encoded, 0, encoded.length, pathType, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip() throws SQLException {
    PGpath original = samplePath(true);
    String encoded = codec.encodeText(original, pathType, null);
    Object decoded = codec.decodeText(encoded, pathType, null);
    assertEquals(original, decoded);
  }

  @Test
  void getTypeName() {
    assertEquals("path", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(PGpath.class, codec.getDefaultJavaType());
  }

  private static byte[] binaryOf(PGpath path) {
    PGpoint[] points = path.points;
    byte[] data = new byte[5 + points.length * 16];
    data[0] = (byte) (path.open ? 0 : 1);
    ByteConverter.int4(data, 1, points.length);
    int pos = 5;
    for (PGpoint point : points) {
      ByteConverter.float8(data, pos, point.x);
      ByteConverter.float8(data, pos + 8, point.y);
      pos += 16;
    }
    return data;
  }
}
