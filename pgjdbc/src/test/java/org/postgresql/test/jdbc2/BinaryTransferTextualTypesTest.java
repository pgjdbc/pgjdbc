/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

/**
 * Types enabled with {@code binaryTransferEnable} that have no
 * {@link org.postgresql.util.PGBinaryObject} implementation must not be read as {@code null}.
 */
@ParameterizedClass(name = "binary = {0}")
@MethodSource("data")
public class BinaryTransferTextualTypesTest extends BaseTest4 {

  public BinaryTransferTextualTypesTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    if (binaryMode == BinaryMode.FORCE) {
      PGProperty.BINARY_TRANSFER_ENABLE.set(props, "bool,json,jsonb,xml,inet");
    }
  }

  @Test
  public void json() throws SQLException {
    String json = "{\"a\": [1, \"Zürich\", null]}";
    assertTextValue("json", json, json);
  }

  @Test
  public void jsonb() throws SQLException {
    // jsonb normalises the text
    assertTextValue("jsonb", "{\"a\":  [1, \"Zürich\", null]}", "{\"a\": [1, \"Zürich\", null]}");
  }

  @Test
  public void xml() throws SQLException {
    assumeTrue(isXmlEnabled(), "Server has been compiled --with-libxml");

    try (PreparedStatement ps = con.prepareStatement("select cast(? as xml)")) {
      ps.setString(1, "<a>Zürich</a>");
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertEquals("<a>Zürich</a>", rs.getString(1));
        assertEquals("<a>Zürich</a>", rs.getSQLXML(1).getString());
        assertEquals("<a>Zürich</a>", assertInstanceOf(SQLXML.class, rs.getObject(1)).getString());
      }
    }
  }

  @Test
  public void typeWithoutBinaryDecoder() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("select cast('127.0.0.1' as inet)");
         ResultSet rs = ps.executeQuery()) {
      rs.next();

      if (binaryMode == BinaryMode.FORCE) {
        assertThrows(PSQLException.class, () -> rs.getString(1));
        assertThrows(PSQLException.class, () -> rs.getObject(1));
        assertThrows(PSQLException.class, () -> rs.getObject(1, PGobject.class));
      } else {
        assertEquals("127.0.0.1", rs.getString(1));
        assertEquals("127.0.0.1", assertInstanceOf(PGobject.class, rs.getObject(1)).getValue());
      }
    }
  }

  private void assertTextValue(String type, String input, String expected) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("select cast(? as " + type + ")")) {
      ps.setString(1, input);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertEquals(expected, rs.getString(1), "getString()");
        assertFalse(rs.wasNull(), "wasNull()");

        PGobject object = assertInstanceOf(PGobject.class, rs.getObject(1), "getObject()");
        assertEquals(type, object.getType(), "getObject().getType()");
        assertEquals(expected, object.getValue(), "getObject().getValue()");
        assertEquals(expected, rs.getObject(1, PGobject.class).getValue(),
            "getObject(PGobject.class)");
      }
    }
  }

  private boolean isXmlEnabled() {
    try (Statement stmt = con.createStatement()) {
      stmt.executeQuery("select cast('<a>b</a>' as xml)").close();
      return true;
    } catch (SQLException e) {
      return false;
    }
  }
}
