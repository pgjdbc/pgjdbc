/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.Map;

class CreateStructTest {

  private static Connection conn;
  private static final String TABLE_NAME = "uuid_table";

  @BeforeAll
  public static void setUp() throws Exception {
    conn = TestUtil.openDB();
    assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_6));
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE " + TABLE_NAME + " (id int PRIMARY KEY, data1 UUID[], data2 UUID[][])");
    }
  }

  @AfterAll
  public static void tearDown() throws Exception {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
    }
    TestUtil.closeDB(conn);
  }

  @Test
  public void testGetSQLTypeName() throws SQLException {
    final String typeName = "my_struct";
    final Object[] attributes = {10, "John", true};

    final Struct struct = conn.createStruct(typeName, attributes);

    assertEquals(typeName, struct.getSQLTypeName());
  }

  @Test
  public void testWithDifferentDataTypes() throws SQLException {
    final String typeName = "my_struct";
    final Object[] attributes = {10, "John", true};

    final Struct struct = conn.createStruct(typeName, attributes);

    final Object[] actualAttributes = struct.getAttributes();

    assertArrayEquals(attributes, actualAttributes);
  }

  @Test
  void complexStructTest() throws SQLException {
    final String typeName = "complex_struct";
    final Object[] attributes = {42, "Test", new int[]{1, 2, 3}};

    final Struct struct = conn.createStruct(typeName, attributes);

    assertEquals(typeName, struct.getSQLTypeName());

    final Object[] actualAttributes = struct.getAttributes();

    assertArrayEquals(attributes, actualAttributes);
  }

  @Test
  public void testGetAttributesWithMapWithInteger() throws SQLException {
    final String typeName = "my_struct";
    final int attribute = 10;
    final Object[] attributes = {attribute};
    final Struct struct = conn.createStruct(typeName, attributes);

    // Create a map to define attribute type mappings
    Map<String, Class<?>> attributeMap = new HashMap<>();
    attributeMap.put(Integer.class.getName(), Integer.class);

    final Object[] mappedAttributes = struct.getAttributes(attributeMap);

    assertArrayEquals(attributes, mappedAttributes);
  }

  @Test
  public void testGetAttributesWithMapWithString() throws SQLException {
    final String typeName = "my_struct";
    final String attribute = "John";
    final Object[] attributes = {attribute};
    final Struct struct = conn.createStruct(typeName, attributes);

    // Create a map to define attribute type mappings
    Map<String, Class<?>> attributeMap = new HashMap<>();
    attributeMap.put(String.class.getName(), String.class);

    final Object[] mappedAttributes = struct.getAttributes(attributeMap);

    assertArrayEquals(attributes, mappedAttributes);
  }

  @Test
  public void testGetAttributesWithMapWithBoolean() throws SQLException {
    final String typeName = "my_struct";
    final Boolean attribute = true;
    final Object[] attributes = {attribute};
    final Struct struct = conn.createStruct(typeName, attributes);

    // Create a map to define attribute type mappings
    Map<String, Class<?>> attributeMap = new HashMap<>();
    attributeMap.put(Boolean.class.getName(), Boolean.class);

    final Object[] mappedAttributes = struct.getAttributes(attributeMap);

    assertArrayEquals(attributes, mappedAttributes);
  }

  @Test
  public void testGetAttributesWithMapWithSeveralDataTypes() throws SQLException {
    final String typeName = "my_struct";
    final Object[] attributes = {10, "John", true};
    final Struct struct = conn.createStruct(typeName, attributes);

    // Create a map to define attribute type mappings
    Map<String, Class<?>> attributeMap = new HashMap<>();
    attributeMap.put(Integer.class.getName(), Integer.class);
    attributeMap.put(String.class.getName(), String.class);
    attributeMap.put(Boolean.class.getName(), Boolean.class);

    final Object[] mappedAttributes = struct.getAttributes(attributeMap);

    assertArrayEquals(attributes, mappedAttributes);
  }

  @Test
  public void testGetAttributesWithMapWithDifferentDataTypeMapping() throws SQLException {
    final String typeName = "my_struct";
    final Object[] attributes = {10};
    final Struct struct = conn.createStruct(typeName, attributes);

    // Create a map to define attribute type mappings
    Map<String, Class<?>> attributeMap = new HashMap<>();
    attributeMap.put(Integer.class.getName(), String.class);

    final Object[] mappedAttributes = struct.getAttributes(attributeMap);

    final String expected = "10";
    final String actual = String.valueOf(mappedAttributes[0]);

    assertEquals(expected, actual);
  }

  /************************
   * Unresolved edge cases.
   ************************/
  /**
   * How should this case be handled?
   * @throws SQLException
   */
  @Test
  public void testWithNullTypeName() throws SQLException {
    final String typeName = null;
    final Object[] attributes = {true};

    // Enforce decision for how scenario should be handled.
    assertThrows(IllegalArgumentException.class, () -> conn.createStruct(typeName, attributes));
  }

  /**
   * How should this case be handled?
   * @throws SQLException
   */
  @Test
  public void testWithEmptyTypeName() throws SQLException {
    final String typeName = "";
    final Object[] attributes = {true};

    // Enforce decision for how scenario should be handled.
    assertThrows(IllegalArgumentException.class, () -> conn.createStruct(typeName, attributes));
  }

  /**
   * How should this case be handled?
   * @throws SQLException
   */
  @Test
  public void testWithBlankTypeName() throws SQLException {
    final String typeName = " ";
    final Object[] attributes = {true};

    // Enforce decision for how scenario should be handled.
    assertThrows(IllegalArgumentException.class, () -> conn.createStruct(typeName, attributes));
  }

  /**
   * How should this case be handled?
   * @throws SQLException
   */
  @Test
  public void testWithNullAttributes() throws SQLException {
    final String typeName = "my_struct";
    final Object[] attributes = null;

    // Enforce decision for how scenario should be handled.
    assertThrows(IllegalArgumentException.class, () -> conn.createStruct(typeName, attributes));
  }

  /**
   * How should this case be handled?
   * @throws SQLException
   */
  @Test
  void emptyAttributesTest() throws SQLException {
    final String typeName = "my_struct";
    final Object[] attributes = {};

    // Enforce decision for how scenario should be handled.
    assertThrows(IllegalArgumentException.class, () -> conn.createStruct(typeName, attributes));
  }
}
