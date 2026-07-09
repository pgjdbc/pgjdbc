/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGpoint;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class BoxCodecTest {

  private BoxCodec codec;
  private PgType boxType;

  @BeforeEach
  void setUp() {
    codec = BoxCodec.INSTANCE;
    boxType = new PgType(
        new ObjectName("pg_catalog", "box"),
        "box",
        Oid.BOX,
        'b', 'G', -1, 0, 0, 0
    );
  }

  @Test
  void decodeBinary() throws SQLException {
    byte[] data = new byte[32];
    ByteConverter.float8(data, 0, 3);
    ByteConverter.float8(data, 8, 4);
    ByteConverter.float8(data, 16, 1);
    ByteConverter.float8(data, 24, 2);

    Object result = codec.decodeBinary(data, 0, data.length, boxType, null);
    assertEquals(new PGbox(3, 4, 1, 2), result);
  }

  @Test
  void decodeBinary_wrongLength() {
    assertThrows(PSQLException.class,
        () -> codec.decodeBinary(new byte[16], 0, 16, boxType, null));
  }

  @Test
  void decodeText() throws SQLException {
    Object result = codec.decodeText("(3,4),(1,2)", boxType, null);
    assertEquals(new PGbox(3, 4, 1, 2), result);
  }

  // PostgreSQL's box_in/box_recv normalize the two corners to (high.x,high.y),(low.x,low.y)
  // regardless of the order they were given; the codec matches that. PGbox.equals() tolerates
  // any corner order, so this checks the stored point array directly rather than via equals().
  @Test
  void decodeText_normalizesCornerOrder() throws SQLException {
    PGbox result = (PGbox) codec.decodeText("(1,2),(3,4)", boxType, null);
    assertEquals(new PGpoint(3, 4), result.point[0]);
    assertEquals(new PGpoint(1, 2), result.point[1]);
  }

  @Test
  void decodeText_normalizesOppositeCorners() throws SQLException {
    PGbox result = (PGbox) codec.decodeText("(1,4),(3,2)", boxType, null);
    assertEquals(new PGpoint(3, 4), result.point[0]);
    assertEquals(new PGpoint(1, 2), result.point[1]);
  }

  @Test
  void encodeText_normalizesCornerOrder() throws SQLException {
    PGbox lowFirst = new PGbox(1, 2, 3, 4);
    assertEquals("(3.0,4.0),(1.0,2.0)", codec.encodeText(lowFirst, boxType, null));
  }

  @Test
  void encodeBinary() throws SQLException {
    PGbox box = new PGbox(1, 2, 3, 4);
    byte[] result = codec.encodeBinary(box, boxType, null);
    assertEquals(32, result.length);
    Object decoded = codec.decodeBinary(result, 0, result.length, boxType, null);
    assertEquals(new PGbox(3, 4, 1, 2), decoded);
  }

  @Test
  void encodeBinary_wrongType() {
    assertThrows(PSQLException.class,
        () -> codec.encodeBinary("not a box", boxType, null));
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    PGbox original = new PGbox(1, 2, 3, 4);
    byte[] encoded = codec.encodeBinary(original, boxType, null);
    Object decoded = codec.decodeBinary(encoded, 0, encoded.length, boxType, null);
    assertEquals(original, decoded);
  }

  @Test
  void getTypeName() {
    assertEquals("box", codec.getTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(PGbox.class, codec.getDefaultJavaType());
  }
}
