/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.Encoding;
import org.postgresql.core.Oid;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

class TextualBinaryDecodingTest {

  private static final Encoding UTF8 = Encoding.getDatabaseEncoding("UTF8");

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] jsonb(int version, String s) {
    byte[] text = utf8(s);
    byte[] result = new byte[text.length + 1];
    result[0] = (byte) version;
    System.arraycopy(text, 0, result, 1, text.length);
    return result;
  }

  @Test
  void json() throws PSQLException {
    assertEquals("{\"a\": \"Zürich 😀\"}",
        TextualBinaryDecoding.decode(Oid.JSON, utf8("{\"a\": \"Zürich 😀\"}"), UTF8));
  }

  @Test
  void jsonEmpty() throws PSQLException {
    assertEquals("", TextualBinaryDecoding.decode(Oid.JSON, new byte[0], UTF8));
  }

  @Test
  void xml() throws PSQLException {
    assertEquals("<a>Zürich</a>", TextualBinaryDecoding.decode(Oid.XML, utf8("<a>Zürich</a>"), UTF8));
  }

  @Test
  void jsonbSkipsVersionByte() throws PSQLException {
    assertEquals("{\"a\": \"Zürich\"}",
        TextualBinaryDecoding.decode(Oid.JSONB, jsonb(1, "{\"a\": \"Zürich\"}"), UTF8));
  }

  @Test
  void jsonbWithoutVersionByte() {
    PSQLException e = assertThrows(PSQLException.class,
        () -> TextualBinaryDecoding.decode(Oid.JSONB, new byte[0], UTF8));
    assertEquals(PSQLState.DATA_ERROR.getState(), e.getSQLState());
    assertTrue(e.getMessage().contains("<none>"), e.getMessage());
  }

  @Test
  void jsonbWithUnknownVersion() {
    PSQLException e = assertThrows(PSQLException.class,
        () -> TextualBinaryDecoding.decode(Oid.JSONB, jsonb(2, "{}"), UTF8));
    assertEquals(PSQLState.DATA_ERROR.getState(), e.getSQLState());
    assertTrue(e.getMessage().contains("jsonb"), e.getMessage());
  }

  @Test
  void typeWithoutTextualBinaryFormat() throws PSQLException {
    assertNull(TextualBinaryDecoding.decode(Oid.INET, new byte[]{2, 32, 0, 4, 127, 0, 0, 1}, UTF8));
  }

  @Test
  void decodingFailure() {
    Encoding failing = new Encoding(StandardCharsets.UTF_8) {
      @Override
      public String decode(byte[] encodedString, int offset, int length) throws IOException {
        throw new IOException("invalid");
      }
    };

    PSQLException e = assertThrows(PSQLException.class,
        () -> TextualBinaryDecoding.decode(Oid.JSON, utf8("{}"), failing));
    assertEquals(PSQLState.DATA_ERROR.getState(), e.getSQLState());
    assertInstanceOf(IOException.class, e.getCause());
  }
}
