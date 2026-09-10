/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;


import static junit.framework.TestCase.assertEquals;

import org.junit.Test;

/**
 * @author Thomas Kellerer
 */
public class PgStringClobTest {

  public PgStringClobTest() {
  }

  @Test
  public void testLength()
      throws Exception {
    String data = "Hello, Clob";
    PgStringClob clob = new PgStringClob(data);
    assertEquals(data.length(), clob.length());
  }

  @Test
  public void testGetSubString()
      throws Exception {
    String data = "Hello, Clob";
    PgStringClob clob = new PgStringClob(data);
    String result = clob.getSubString(1, 5);
    assertEquals("Hello", result);
  }

  @Test
  public void testPositionString()
      throws Exception {
    String data = "Hello, Clob";
    PgStringClob clob = new PgStringClob(data);
    long result = clob.position(",", 1);
    assertEquals(data.indexOf(',') + 1, result);
  }

  @Test
  public void testPositionClob()
      throws Exception {
    String data = "Hello, Clob";
    PgStringClob clob = new PgStringClob(data);
    PgStringClob searchstr = new PgStringClob("Clob");
    long result = clob.position(searchstr, 1);
    assertEquals(data.indexOf("Clob") + 1, result);
  }

  @Test
  public void testSetString1()
      throws Exception {
    String data = "Hello, Clob";
    PgStringClob clob = new PgStringClob(data);
    clob.setString(8, "World");
    assertEquals("Hello, World", clob.toString());
  }

  @Test
  public void testSetString2()
      throws Exception {
  }

  @Test
  public void testTruncate()
      throws Exception {
    String data = "Hello, Clob";
    PgStringClob clob = new PgStringClob(data);
    clob.truncate(5);
    assertEquals(5, clob.length());
    assertEquals("Hello", clob.toString());
  }

  @Test
  public void testToString() {
    String data = "Hello, Clob";
    PgStringClob clob = new PgStringClob(data);
    assertEquals(data, clob.toString());
  }

}
