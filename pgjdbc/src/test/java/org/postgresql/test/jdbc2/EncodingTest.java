/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.Encoding;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.util.Locale;

/**
 * Tests for the Encoding class.
 */
public class EncodingTest {

  @Test
  public void testCreationDatabaseEncoding() throws Exception {
    Encoding encoding = Encoding.getDatabaseEncoding("UTF8");
    assertNotNull(encoding);
    assertEquals("UTF-8", encoding.name());
    assertTrue(encoding.hasAsciiNumbers());
    encoding = Encoding.getDatabaseEncoding("LATIN1");
    assertEquals("ISO8859_1", encoding.toString());
    assertEquals("ISO-8859-1", encoding.name());
    encoding = Encoding.getDatabaseEncoding("SQL_ASCII");
    assertTrue(encoding.name().toUpperCase(Locale.US).contains("ASCII"));
    assertEquals("When encoding is unknown the default encoding should be used",
        Encoding.defaultEncoding().name(), Encoding.getDatabaseEncoding("UNKNOWN").name());
  }

  @Test
  public void testCreationJVMEncoding() throws Exception {
    Encoding encoding = Encoding.getJVMEncoding("UTF8");
    assertNotNull(encoding);
    assertEquals("UTF-8", encoding.name());
    encoding = Encoding.getJVMEncoding("ISO-8859-1");
    assertEquals("ISO-8859-1", encoding.name());
    encoding = Encoding.getJVMEncoding("ASCII");
    assertEquals("US-ASCII", encoding.name());
    assertEquals("ASCII", encoding.toString());
    assertEquals("When encoding is unknown the default encoding should be used",
        Encoding.defaultEncoding().name(), Encoding.getJVMEncoding("UNKNOWN").name());
  }

  @Test
  public void testTransformations() throws Exception {
    Encoding encoding = Encoding.getDatabaseEncoding("UTF8");
    assertEquals("ab", encoding.decode(new byte[]{97, 98}));

    assertEquals(2, encoding.encode("ab").length);
    assertEquals(97, encoding.encode("a")[0]);
    assertEquals(98, encoding.encode("b")[0]);

    encoding = Encoding.defaultEncoding();
    assertEquals("a".getBytes()[0], encoding.encode("a")[0]);
    assertEquals(new String(new byte[]{97}), encoding.decode(new byte[]{97}));
  }

  @Test
  public void testReader() throws Exception {
    Encoding encoding = Encoding.getDatabaseEncoding("SQL_ASCII");
    InputStream stream = new ByteArrayInputStream(new byte[]{97, 98});
    Reader reader = encoding.getDecodingReader(stream);
    assertEquals(97, reader.read());
    assertEquals(98, reader.read());
    assertEquals(-1, reader.read());
  }

  @Test
  public void testEncoder() throws Exception {
    Encoding encoding = Encoding.getDatabaseEncoding("SQL_ASCII");
    assertNotNull(encoding);
    byte[] strByte = encoding.encode("Hola mundo!");
    assertNotNull(strByte);
    assertEquals(new String(strByte, "US-ASCII"), "Hola mundo!");

    encoding = Encoding.getDatabaseEncoding("UTF8");
    strByte = encoding.encode("Привет мир!");
    assertEquals(new String(strByte, "UTF-8"), "Привет мир!");
  }

  @Test
  public void testDecoder() throws Exception {
    Encoding encoding = Encoding.getDatabaseEncoding("SQL_ASCII");
    assertNotNull(encoding);
    byte[] strByte = "Hola mundo!".getBytes("US-ASCII");
    assertEquals(encoding.decode(strByte), "Hola mundo!");

    encoding = Encoding.getDatabaseEncoding("UTF8");
    strByte = encoding.encode("Привет мир!");
    assertEquals(encoding.decode(strByte), "Привет мир!");
  }
}
