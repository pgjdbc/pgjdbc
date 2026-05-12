/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.Oid;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGline;
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class GeometricCodecTest {

  // ==================== Point (Binary-capable) ====================

  @Test
  void point_metadata() {
    assertEquals("point", GeometricCodec.POINT.getTypeName());
    assertEquals(PGpoint.class, GeometricCodec.POINT.getDefaultJavaType());
  }

  @Test
  void point_decodeText() throws SQLException {
    PgType pointType = makeType("point", Oid.POINT);
    PGpoint result = (PGpoint) GeometricCodec.POINT.decodeText("(1.5,2.5)", pointType, null);
    assertNotNull(result);
    assertEquals(1.5, result.x, 0.001);
    assertEquals(2.5, result.y, 0.001);
  }

  @Test
  void point_encodeText() throws SQLException {
    PgType pointType = makeType("point", Oid.POINT);
    PGpoint point = new PGpoint(1.5, 2.5);
    String result = GeometricCodec.POINT.encodeText(point, pointType, null);
    assertNotNull(result);
    assertTrue(result.contains("1.5"));
    assertTrue(result.contains("2.5"));
  }

  @Test
  void point_binaryRoundtrip() throws SQLException {
    PgType pointType = makeType("point", Oid.POINT);
    PGpoint original = new PGpoint(3.14, 2.72);
    byte[] encoded = GeometricCodec.POINT.encodeBinary(original, pointType, null);
    PGpoint decoded = (PGpoint) GeometricCodec.POINT.decodeBinary(encoded, pointType, null);
    assertNotNull(decoded);
    assertEquals(original.x, decoded.x, 0.001);
    assertEquals(original.y, decoded.y, 0.001);
  }

  @Test
  void point_encodeBinary_wrongType() {
    PgType pointType = makeType("point", Oid.POINT);
    assertThrows(PSQLException.class,
        () -> GeometricCodec.POINT.encodeBinary("not a point", pointType, null));
  }

  @Test
  void point_decodeAsInt_throws() {
    PgType pointType = makeType("point", Oid.POINT);
    assertThrows(PSQLException.class,
        () -> GeometricCodec.POINT.decodeAsInt("(1,2)", pointType, null));
  }

  // ==================== Box (Binary-capable) ====================

  @Test
  void box_metadata() {
    assertEquals("box", GeometricCodec.BOX.getTypeName());
    assertEquals(PGbox.class, GeometricCodec.BOX.getDefaultJavaType());
  }

  @Test
  void box_decodeText() throws SQLException {
    PgType boxType = makeType("box", Oid.BOX);
    PGbox result = (PGbox) GeometricCodec.BOX.decodeText("(3,4),(1,2)", boxType, null);
    assertNotNull(result);
  }

  @Test
  void box_binaryRoundtrip() throws SQLException {
    PgType boxType = makeType("box", Oid.BOX);
    PGbox original = new PGbox(1, 2, 3, 4);
    byte[] encoded = GeometricCodec.BOX.encodeBinary(original, boxType, null);
    PGbox decoded = (PGbox) GeometricCodec.BOX.decodeBinary(encoded, boxType, null);
    assertNotNull(decoded);
  }

  // ==================== Circle (Text-only) ====================

  @Test
  void circle_metadata() {
    assertEquals("circle", GeometricCodec.CIRCLE.getTypeName());
    assertEquals(PGcircle.class, GeometricCodec.CIRCLE.getDefaultJavaType());
  }

  @Test
  void circle_decodeText() throws SQLException {
    PgType circleType = makeType("circle", Oid.CIRCLE);
    PGcircle result = (PGcircle) GeometricCodec.CIRCLE.decodeText("<(1,2),3>", circleType, null);
    assertNotNull(result);
    assertEquals(1.0, result.center.x, 0.001);
    assertEquals(2.0, result.center.y, 0.001);
    assertEquals(3.0, result.radius, 0.001);
  }

  @Test
  void circle_encodeText() throws SQLException {
    PgType circleType = makeType("circle", Oid.CIRCLE);
    PGcircle circle = new PGcircle(1, 2, 3);
    String result = GeometricCodec.CIRCLE.encodeText(circle, circleType, null);
    assertNotNull(result);
  }

  @Test
  void circle_decodeAsInt_throws() {
    PgType circleType = makeType("circle", Oid.CIRCLE);
    assertThrows(PSQLException.class,
        () -> GeometricCodec.CIRCLE.decodeAsInt("<(1,2),3>", circleType, null));
  }

  // ==================== Other geometric types metadata ====================

  @Test
  void line_metadata() {
    assertEquals("line", GeometricCodec.LINE.getTypeName());
    assertEquals(PGline.class, GeometricCodec.LINE.getDefaultJavaType());
  }

  @Test
  void lseg_metadata() {
    assertEquals("lseg", GeometricCodec.LSEG.getTypeName());
    assertEquals(PGlseg.class, GeometricCodec.LSEG.getDefaultJavaType());
  }

  @Test
  void path_metadata() {
    assertEquals("path", GeometricCodec.PATH.getTypeName());
    assertEquals(PGpath.class, GeometricCodec.PATH.getDefaultJavaType());
  }

  @Test
  void polygon_metadata() {
    assertEquals("polygon", GeometricCodec.POLYGON.getTypeName());
    assertEquals(PGpolygon.class, GeometricCodec.POLYGON.getDefaultJavaType());
  }

  // ==================== decodeTextAs ====================

  @Test
  void point_decodeTextAs_PGpoint() throws SQLException {
    PgType pointType = makeType("point", Oid.POINT);
    PGpoint result = GeometricCodec.POINT.decodeTextAs("(1,2)", pointType, PGpoint.class, null);
    assertNotNull(result);
  }

  @Test
  void point_decodeTextAs_unsupported() {
    PgType pointType = makeType("point", Oid.POINT);
    assertThrows(PSQLException.class,
        () -> GeometricCodec.POINT.decodeTextAs("(1,2)", pointType, String.class, null));
  }

  @Test
  void circle_decodeTextAs_PGcircle() throws SQLException {
    PgType circleType = makeType("circle", Oid.CIRCLE);
    PGcircle result = GeometricCodec.CIRCLE.decodeTextAs("<(1,2),3>", circleType, PGcircle.class, null);
    assertNotNull(result);
  }

  private static PgType makeType(String name, int oid) {
    return new PgType(
        new ObjectName("pg_catalog", name),
        name,
        oid,
        'b', 'G', -1, 0, 0, 0
    );
  }
}
