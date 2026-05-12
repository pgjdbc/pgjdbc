/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.Map;

/**
 * Integration tests for composite type escaping.
 *
 * <p>Tests proper handling of special characters in composite type text format:
 * <ul>
 *   <li>Comma (,) - field separator</li>
 *   <li>Double quote (") - quote delimiter</li>
 *   <li>Backslash (\) - escape character</li>
 *   <li>Parentheses () - composite delimiters</li>
 *   <li>Empty string vs NULL</li>
 * </ul>
 */
public class CompositeEscapingTest {

  private static Connection conn;

  @BeforeAll
  public static void setUp() throws Exception {
    conn = TestUtil.openDB();
    try (Statement stmt = conn.createStatement()) {
      // Create test composite type
      stmt.execute("DROP TYPE IF EXISTS escape_test CASCADE");
      stmt.execute("CREATE TYPE escape_test AS (id int, value text, suffix text)");

      // Create nested composite type
      stmt.execute("DROP TYPE IF EXISTS nested_escape CASCADE");
      stmt.execute("CREATE TYPE nested_escape AS (name text, data escape_test)");
    }
  }

  @AfterAll
  public static void tearDown() throws Exception {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TYPE IF EXISTS nested_escape CASCADE");
        stmt.execute("DROP TYPE IF EXISTS escape_test CASCADE");
      }
      conn.close();
    }
  }

  // ==================== Basic Escaping Tests ====================

  @Test
  void textFormat_fieldWithComma() throws SQLException {
    String compositeText = "(1,\"val,ue\",3)";
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::escape_test")) {
      ps.setString(1, compositeText);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(1, attrs[0]);
        assertEquals("val,ue", attrs[1]);
        assertEquals("3", attrs[2]);
      }
    }
  }

  @Test
  void textFormat_doubleQuoteEscape() throws SQLException {
    // PostgreSQL uses "" to escape a double quote inside a quoted value
    String compositeText = "(1,\"val\"\"ue\",3)";
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::escape_test")) {
      ps.setString(1, compositeText);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(1, attrs[0]);
        assertEquals("val\"ue", attrs[1]);
        assertEquals("3", attrs[2]);
      }
    }
  }

  @Test
  void textFormat_backslashQuoteEscape() throws SQLException {
    // PostgreSQL also supports \" to escape a double quote
    String compositeText = "(1,\"val\\\"ue\",3)";
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::escape_test")) {
      ps.setString(1, compositeText);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(1, attrs[0]);
        assertEquals("val\"ue", attrs[1]);
        assertEquals("3", attrs[2]);
      }
    }
  }

  @Test
  void textFormat_fieldWithParentheses() throws SQLException {
    String compositeText = "(1,\"val(ue)\",3)";
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::escape_test")) {
      ps.setString(1, compositeText);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(1, attrs[0]);
        assertEquals("val(ue)", attrs[1]);
        assertEquals("3", attrs[2]);
      }
    }
  }

  @Test
  void textFormat_fieldWithBackslash() throws SQLException {
    // Backslash needs to be escaped as \\
    String compositeText = "(1,\"val\\\\ue\",3)";
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::escape_test")) {
      ps.setString(1, compositeText);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(1, attrs[0]);
        assertEquals("val\\ue", attrs[1]);
        assertEquals("3", attrs[2]);
      }
    }
  }

  @Test
  void textFormat_emptyStringVsNull() throws SQLException {
    // Empty string: ""
    String emptyString = "(1,\"\",3)";
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::escape_test")) {
      ps.setString(1, emptyString);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(1, attrs[0]);
        assertEquals("", attrs[1]); // Empty string, not null
        assertEquals("3", attrs[2]);
      }
    }

    // NULL: just empty between commas
    String nullValue = "(1,,3)";
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::escape_test")) {
      ps.setString(1, nullValue);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(1, attrs[0]);
        assertNull(attrs[1]); // NULL, not empty string
        assertEquals("3", attrs[2]);
      }
    }
  }

  @Test
  void textFormat_combinedEscapes() throws SQLException {
    // A value with comma, quote, backslash, and parentheses
    String compositeText = "(1,\"a,b\\\"c\\\\d(e)f\",3)";
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::escape_test")) {
      ps.setString(1, compositeText);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(1, attrs[0]);
        assertEquals("a,b\"c\\d(e)f", attrs[1]);
        assertEquals("3", attrs[2]);
      }
    }
  }

  // ==================== Roundtrip Tests ====================

  @Test
  void roundtrip_specialCharactersPreserved() throws SQLException {
    // Create struct with special characters using createStruct
    String specialValue = "hello,world\"with'quotes\\and\\\\backslash(parens)";
    Struct original = conn.createStruct("escape_test", new Object[]{42, specialValue, "end"});

    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::escape_test")) {
      ps.setObject(1, original);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Struct result = (Struct) rs.getObject(1);
        assertNotNull(result);
        Object[] attrs = result.getAttributes();
        assertEquals(42, attrs[0]);
        assertEquals(specialValue, attrs[1]);
        assertEquals("end", attrs[2]);
      }
    }
  }

  @Test
  void roundtrip_sqlDataWithSpecialChars() throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("escape_test", EscapeTestData.class);

    EscapeTestData original = new EscapeTestData();
    original.id = 99;
    original.value = "comma,quote\"backslash\\paren(test)";
    original.suffix = "done";

    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::escape_test")) {
      ps.setObject(1, original);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        EscapeTestData result = rs.getObject(1, EscapeTestData.class);
        assertNotNull(result);
        assertEquals(original.id, result.id);
        assertEquals(original.value, result.value);
        assertEquals(original.suffix, result.suffix);
      }
    }
  }

  // ==================== Nested Composite Tests ====================

  @Test
  void nestedComposite_escapingPreserved() throws SQLException {
    // Create a nested composite where inner value has special characters
    String innerValue = "nested,\"quoted\"\\backslash";
    Struct inner = conn.createStruct("escape_test", new Object[]{1, innerValue, "x"});
    Struct outer = conn.createStruct("nested_escape", new Object[]{"outer_name", inner});

    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::nested_escape")) {
      ps.setObject(1, outer);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Struct result = (Struct) rs.getObject(1);
        assertNotNull(result);
        Object[] outerAttrs = result.getAttributes();
        assertEquals("outer_name", outerAttrs[0]);

        Struct innerResult = (Struct) outerAttrs[1];
        assertNotNull(innerResult);
        Object[] innerAttrs = innerResult.getAttributes();
        assertEquals(1, innerAttrs[0]);
        assertEquals(innerValue, innerAttrs[1]);
        assertEquals("x", innerAttrs[2]);
      }
    }
  }

  // ==================== SQLData Implementation for Testing ====================

  public static class EscapeTestData implements SQLData {
    public int id;
    public String value;
    public String suffix;

    @Override
    public String getSQLTypeName() {
      return "escape_test";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      id = stream.readInt();
      value = stream.readString();
      suffix = stream.readString();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(id);
      stream.writeString(value);
      stream.writeString(suffix);
    }
  }
}
