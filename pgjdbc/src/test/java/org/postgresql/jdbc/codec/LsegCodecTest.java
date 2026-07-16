/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.geometric.PGlseg;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class LsegCodecTest {

  private LsegCodec codec;
  private PgType lsegType;

  @BeforeEach
  void setUp() {
    codec = LsegCodec.INSTANCE;
    lsegType = new PgType(
        new ObjectName("pg_catalog", "lseg"),
        "lseg",
        Oid.LSEG,
        'b', 'G', -1, 0, 0, 0
    );
  }

  @Test
  void decodeBinary() throws SQLException {
    byte[] data = new byte[32];
    ByteConverter.float8(data, 0, 1);
    ByteConverter.float8(data, 8, 2);
    ByteConverter.float8(data, 16, 3);
    ByteConverter.float8(data, 24, 4);

    Object result = codec.decodeBinary(data, 0, data.length, lsegType, null);
    assertEquals(new PGlseg(1, 2, 3, 4), result);
  }

  @Test
  void decodeBinary_wrongLength() {
    assertThrows(PSQLException.class,
        () -> codec.decodeBinary(new byte[16], 0, 16, lsegType, null));
  }

  @Test
  void decodeText() throws SQLException {
    Object result = codec.decodeText("[(1,2),(3,4)]", lsegType, null);
    assertEquals(new PGlseg(1, 2, 3, 4), result);
  }

  @Test
  void encodeText() throws SQLException {
    String result = codec.encodeText(new PGlseg(1, 2, 3, 4), lsegType, null);
    assertEquals("[(1.0,2.0),(3.0,4.0)]", result);
  }

  @Test
  void encodeBinary() throws SQLException {
    PGlseg lseg = new PGlseg(1, 2, 3, 4);
    byte[] result = codec.encodeBinary(lseg, lsegType, null);
    assertEquals(32, result.length);
    Object decoded = codec.decodeBinary(result, 0, result.length, lsegType, null);
    assertEquals(lseg, decoded);
  }

  @Test
  void encodeBinary_wrongType() {
    assertThrows(PSQLException.class,
        () -> codec.encodeBinary("not a lseg", lsegType, null));
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    PGlseg original = new PGlseg(1, 2, 3, 4);
    byte[] encoded = codec.encodeBinary(original, lsegType, null);
    Object decoded = codec.decodeBinary(encoded, 0, encoded.length, lsegType, null);
    assertEquals(original, decoded);
  }

  @Test
  void textRoundtrip() throws SQLException {
    PGlseg original = new PGlseg(1, 2, 3, 4);
    String encoded = codec.encodeText(original, lsegType, null);
    Object decoded = codec.decodeText(encoded, lsegType, null);
    assertEquals(original, decoded);
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("lseg", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(PGlseg.class, codec.getDefaultJavaType());
  }
}
