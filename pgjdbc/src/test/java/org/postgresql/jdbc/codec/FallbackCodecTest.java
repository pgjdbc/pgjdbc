/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.TypeDescriptor;
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
import java.sql.Date;
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
    Object result = codec.decodeBinary(data, 0, data.length, unknownType, null);

    assertInstanceOf(PGUnknownBinary.class, result);
    PGUnknownBinary unknown = (PGUnknownBinary) result;
    assertEquals("unknown_type", unknown.getType());
    assertArrayEquals(data, unknown.getBytes());
  }

  @Test
  void reportsTextOnlyRead() {
    // The fallback does not interpret the real binary wire, so the receive-format choice must
    // request an unmapped type in text (PGobject) rather than binary (PGUnknownBinary). It still
    // decodes binary when the server sends it anyway, e.g. an unmapped field inside a binary record.
    assertFalse(codec.decodesBinary());
    assertTrue(codec.decodesText());
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
    Object result = codec.decodeBinary(data, 0, data.length, unknownType, null);

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
  void getPrimaryTypeName() {
    assertEquals("unknown", codec.getPrimaryTypeName());
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

  // A String has no known binary wire form for an arbitrary unmapped type: its charset bytes are the
  // text wire, which differs from the type's binary recv format (e.g. ltree prefixes a version byte).
  // So the fallback must refuse to binary-encode a String and must not label charset bytes as binary.
  @Test
  void canEncodeBinary_refusesString() throws SQLException {
    assertFalse(codec.canEncodeBinary("a.b.c", unknownType, ctx));
  }

  // PGUnknownBinary carries a genuine binary payload read from the server's binary output for this
  // OID, so re-sending it as binary is a faithful round-trip.
  @Test
  void canEncodeBinary_acceptsPGUnknownBinary() throws SQLException {
    PGUnknownBinary value = new PGUnknownBinary("unknown_type", new byte[]{0x01, 0x02});
    assertTrue(codec.canEncodeBinary(value, unknownType, ctx));
    assertArrayEquals(new byte[]{0x01, 0x02}, codec.encodeBinary(value, unknownType, ctx));
  }

  @Test
  void encodeBinary_refusesString() {
    assertThrows(SQLException.class, () -> codec.encodeBinary("a.b.c", unknownType, ctx));
  }

  // Even when the backend accepts binary for this OID (binaryTransferEnable), a String parameter must
  // negotiate to text, since the fallback cannot produce a correct binary wire for the unmapped type.
  @Test
  void chooseBindFormat_stringGoesTextEvenWhenBinaryAllowed() throws SQLException {
    assertEquals(Format.TEXT,
        CodecFormatPolicy.chooseBindFormat(codec, "a.b.c", unknownType, ctx, true));
  }

  @Test
  void chooseBindFormat_unknownBinaryGoesBinaryWhenAllowed() throws SQLException {
    PGUnknownBinary value = new PGUnknownBinary("unknown_type", new byte[]{0x01, 0x02});
    assertEquals(Format.BINARY,
        CodecFormatPolicy.chooseBindFormat(codec, value, unknownType, ctx, true));
  }

  @Test
  void decodeBinaryAs_Date_throws() {
    byte[] data = "2024-01-02".getBytes(StandardCharsets.UTF_8);
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) unknownType, Date.class, ctx));
  }

  // Regression: getFloat must read a numeric-text value that getDouble reads, and refuse a non-numeric
  // one alike. decodeAsDouble is overridden to parse the text, so decodeAsFloat must too rather than
  // fall to the default that boxes the non-Number PGUnknownBinary and refuses.
  @Test
  void decodeAsFloat_parsesTextLikeDouble() throws SQLException {
    byte[] wire = "1.5".getBytes(StandardCharsets.UTF_8);
    assertEquals(1.5f, codec.decodeAsFloat(wire, 0, wire.length, unknownType, ctx));
    assertEquals(1.5f, codec.decodeAsFloat("1.5", unknownType, ctx));
    assertEquals((float) codec.decodeAsDouble(wire, 0, wire.length, unknownType, ctx),
        codec.decodeAsFloat(wire, 0, wire.length, unknownType, ctx));
  }

  @Test
  void decodeAsFloatAndDouble_refuseNonNumericAlike() {
    byte[] wire = "not-a-number".getBytes(StandardCharsets.UTF_8);
    assertThrows(SQLException.class, () -> codec.decodeAsDouble(wire, 0, wire.length, unknownType, ctx));
    assertThrows(SQLException.class, () -> codec.decodeAsFloat(wire, 0, wire.length, unknownType, ctx));
  }
}
