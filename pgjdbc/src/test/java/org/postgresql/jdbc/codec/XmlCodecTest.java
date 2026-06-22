/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

class XmlCodecTest {

  private XmlCodec codec;
  private PgType xmlType;

  @BeforeEach
  void setUp() {
    codec = XmlCodec.INSTANCE;
    xmlType = new PgType(
        new ObjectName("pg_catalog", "xml"),
        "xml",
        Oid.XML,
        'b', 'U', -1, 0, 0, 0
    );
  }

  @Test
  void getTypeName() {
    assertEquals("xml", codec.getTypeName());
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
    assertEquals(xml, codec.decodeText(xml, xmlType, null));
  }

  @Test
  void encodeText_fromString() throws SQLException {
    String xml = "<root><child>value</child></root>";
    assertEquals(xml, codec.encodeText(xml, xmlType, null));
  }

  @Test
  void decodeBinary_utf8() throws SQLException {
    String xml = "<root><child>value</child></root>";
    byte[] data = xml.getBytes(StandardCharsets.UTF_8);
    assertEquals(xml, codec.decodeBinary(data, xmlType, null));
  }

  @Test
  void encodeBinary_toUtf8() throws SQLException {
    String xml = "<root/>";
    byte[] result = codec.encodeBinary(xml, xmlType, null);
    assertArrayEquals(xml.getBytes(StandardCharsets.UTF_8), result);
  }

  @Test
  void decodeAsString_binary() throws SQLException {
    String xml = "<root/>";
    byte[] data = xml.getBytes(StandardCharsets.UTF_8);
    assertEquals(xml, codec.decodeAsString(data, xmlType, null));
  }

  @Test
  void decodeAsString_text() throws SQLException {
    assertEquals("<root/>", codec.decodeAsString("<root/>", xmlType, null));
  }

  @Test
  void decodeAsInt_throws() {
    assertThrows(PSQLException.class, () -> codec.decodeAsInt("<root/>", xmlType, null));
  }

  @Test
  void decodeAsLong_throws() {
    assertThrows(PSQLException.class, () -> codec.decodeAsLong("<root/>", xmlType, null));
  }

  @Test
  void decodeAsDouble_throws() {
    assertThrows(PSQLException.class, () -> codec.decodeAsDouble("<root/>", xmlType, null));
  }

  @Test
  void decodeBinaryAs_String() throws SQLException {
    byte[] data = "<root/>".getBytes(StandardCharsets.UTF_8);
    assertEquals("<root/>", codec.decodeBinaryAs(data, xmlType, String.class, null));
  }

  @Test
  void decodeBinaryAs_unsupported() {
    byte[] data = "<root/>".getBytes(StandardCharsets.UTF_8);
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(data, xmlType, Integer.class, null));
  }

  @Test
  void decodeTextAs_String() throws SQLException {
    assertEquals("<root/>", codec.decodeTextAs("<root/>", xmlType, String.class, null));
  }

  @Test
  void decodeTextAs_unsupported() {
    assertThrows(PSQLException.class,
        () -> codec.decodeTextAs("<root/>", xmlType, Integer.class, null));
  }

  @Test
  void binaryRoundtrip() throws SQLException {
    String xml = "<root><child attr=\"val\">text</child></root>";
    byte[] encoded = codec.encodeBinary(xml, xmlType, null);
    assertEquals(xml, codec.decodeBinary(encoded, xmlType, null));
  }
}
