/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class XmlCodecTest {

  private XmlCodec codec;
  private PgType xmlType;
  private CodecContext ctx;

  @BeforeEach
  void setUp() {
    codec = XmlCodec.INSTANCE;
    xmlType = new PgType(
        new ObjectName("pg_catalog", "xml"),
        "xml",
        Oid.XML,
        'b', 'U', -1, 0, 0, 0
    );
    ctx = TestCodecContext.create();
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("xml", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    // PgResultSet.getObject returns SQLXML for xml columns (legacy contract),
    // even though the codec itself decodes to String.
    assertEquals(java.sql.SQLXML.class, codec.getDefaultJavaType());
  }

  @Test
  void decodeText_returnsString() throws SQLException {
    String xml = "<root><child>value</child></root>";
    assertEquals(xml, codec.decodeText(xml, xmlType, ctx));
  }

  @Test
  void encodeText_fromString() throws SQLException {
    String xml = "<root><child>value</child></root>";
    assertEquals(xml, codec.encodeText(xml, xmlType, ctx));
  }

  @Test
  void decodeBinary_utf8() throws SQLException {
    String xml = "<root><child>value</child></root>";
    byte[] data = xml.getBytes(ctx.getCharset());
    assertEquals(xml, codec.decodeBinary(data, 0, data.length, xmlType, ctx));
  }

  @Test
  void encodeBinary_toUtf8() throws SQLException {
    String xml = "<root/>";
    byte[] result = codec.encodeBinary(xml, xmlType, ctx);
    assertArrayEquals(xml.getBytes(ctx.getCharset()), result);
  }

  @Test
  void decodeAsString_binary() throws SQLException {
    String xml = "<root/>";
    byte[] data = xml.getBytes(ctx.getCharset());
    assertEquals(xml, codec.decodeAsString(data, 0, data.length, xmlType, ctx));
  }

  @Test
  void decodeAsString_text() throws SQLException {
    assertEquals("<root/>", codec.decodeAsString("<root/>", xmlType, ctx));
  }

  /**
   * An empty XML value ({@code xmlparse(content '')}) is a valid, non-null value whose wire form is
   * zero bytes; SQL NULL is filtered before the codec runs, so a zero-length slice must decode to
   * {@code ""}, matching the text path and the server, not {@code null}.
   */
  @Test
  void decodeEmpty_returnsEmptyString() throws SQLException {
    byte[] empty = new byte[0];
    assertEquals("", codec.decodeBinary(empty, 0, 0, xmlType, ctx));
    assertEquals("", codec.decodeAsString(empty, 0, 0, xmlType, ctx));
    assertEquals("", codec.decodeBinaryAs(empty, 0, 0, xmlType, String.class, ctx));
    assertEquals("", codec.decodeText("", xmlType, ctx));
    assertEquals("", codec.decodeAsString("", xmlType, ctx));
    assertEquals("", codec.decodeTextAs("", xmlType, String.class, ctx));
  }

  @Test
  void decodeAsInt_throws() {
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asInt(codec, "<root/>", xmlType, ctx));
  }

  @Test
  void decodeAsLong_throws() {
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asLong(codec, "<root/>", xmlType, ctx));
  }

  @Test
  void decodeAsDouble_throws() {
    assertThrows(PSQLException.class, () -> PrimitiveDecoders.asDouble(codec, "<root/>", xmlType, ctx));
  }

  @Test
  void decodeBinaryAs_String() throws SQLException {
    byte[] data = "<root/>".getBytes(ctx.getCharset());
    assertEquals("<root/>", codec.decodeBinaryAs(data, 0, data.length, xmlType, String.class, ctx));
  }

  @Test
  void decodeBinaryAs_unsupported() {
    byte[] data = "<root/>".getBytes(ctx.getCharset());
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) xmlType, Integer.class, ctx));
  }

  @Test
  void decodeTextAs_String() throws SQLException {
    assertEquals("<root/>", codec.decodeTextAs("<root/>", xmlType, String.class, ctx));
  }

  @Test
  void decodeTextAs_unsupported() {
    assertThrows(PSQLException.class,
        () -> codec.decodeTextAs("<root/>", xmlType, Integer.class, ctx));
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    String xml = "<root><child attr=\"val\">text</child></root>";
    byte[] encoded = codec.encodeBinary(xml, xmlType, ctx);
    assertEquals(xml, codec.decodeBinary(encoded, 0, encoded.length, xmlType, ctx));
  }
}
