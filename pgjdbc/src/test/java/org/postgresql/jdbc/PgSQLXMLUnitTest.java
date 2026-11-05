/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class PgSQLXMLUnitTest {

  @Test
  void testToStringInitializedWithString() throws SQLException {
    String xmlData = "<root><item id=\"1\">test</item></root>";
    PgSQLXML xml = new PgSQLXML(null, xmlData);
    assertEquals(xmlData, xml.getString());
    assertEquals(xmlData, xml.toString());
  }

  @Test
  void testToStringInitializedWithNull() throws SQLException {
    PgSQLXML xml = new PgSQLXML(null, null);
    assertNull(xml.getString());
    assertNull(xml.toString());
  }

  @Test
  void testToStringUninitializedFallsBackToDefault() {
    PgSQLXML xml = new PgSQLXML(null);
    String str = xml.toString();
    assertTrue(str.startsWith("org.postgresql.jdbc.PgSQLXML@"),
        "Uninitialized PgSQLXML.toString() should fall back to Object.toString()");
  }

  @Test
  void testToStringFreedFallsBackToDefault() throws SQLException {
    PgSQLXML xml = new PgSQLXML(null, "<data/>");
    xml.free();
    String str = xml.toString();
    assertTrue(str.startsWith("org.postgresql.jdbc.PgSQLXML@"),
        "Freed PgSQLXML.toString() should fall back to Object.toString()");
  }

  @Test
  void testToStringAfterSetString() throws SQLException {
    PgSQLXML xml = new PgSQLXML(null);
    String xmlData = "<message>hello</message>";
    xml.setString(xmlData);
    assertEquals(xmlData, xml.getString());
    assertEquals(xmlData, xml.toString());
  }
}
