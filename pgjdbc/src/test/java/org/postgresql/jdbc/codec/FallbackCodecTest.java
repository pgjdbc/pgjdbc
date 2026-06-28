/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TemporalCodecs;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.PGUnknownBinary;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

class FallbackCodecTest {

  private FallbackCodec codec;
  private PgType unknownType;
  private CodecContext ctx;

  @BeforeEach
  void setUp() {
    codec = FallbackCodec.INSTANCE;
    unknownType = new PgType(
        new ObjectName("pg_catalog", "unknown_type"),
        "unknown_type",
        99999,
        'b', 'X', -1, 0, 0, 0
    );
    ctx = TestCodecContext.create();
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

  // 'unknown' (oid 705, e.g. SELECT '2024-01-01' without a cast) resolves to this codec.
  // Text values are parsed as date/time literals; binary stays unparseable.

  @Test
  void decodeTextAs_Date() throws SQLException {
    assertEquals(
        TemporalCodecs.decodeDateText("2024-01-02", ctx),
        codec.decodeTextAs("2024-01-02", unknownType, java.sql.Date.class, ctx));
  }

  @Test
  void decodeTextAs_Time() throws SQLException {
    assertEquals(
        TemporalCodecs.decodeTimeText("12:34:56", ctx),
        codec.decodeTextAs("12:34:56", unknownType, java.sql.Time.class, ctx));
  }

  @Test
  void decodeTextAs_Timestamp() throws SQLException {
    assertEquals(
        TemporalCodecs.decodeTimestampText("2024-01-02 12:34:56", ctx),
        codec.decodeTextAs("2024-01-02 12:34:56", unknownType, java.sql.Timestamp.class, ctx));
  }

  @Test
  void decodeBinaryAs_Date_throws() {
    byte[] data = "2024-01-02".getBytes(StandardCharsets.UTF_8);
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, unknownType, java.sql.Date.class, ctx));
  }
}
