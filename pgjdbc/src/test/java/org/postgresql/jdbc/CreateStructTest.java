/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
package org.postgresql.jdbc;

import org.postgresql.test.TestUtil;
import org.postgresql.core.ServerVersion;

import java.sql.*;

import org.junit.Ignore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class CreateStructTest {

  private static Connection con;
  private static final String TABLE_NAME = "uuid_table";

  @BeforeAll
  public static void setUp() throws Exception {
    con = TestUtil.openDB();
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_6));
    try (Statement stmt = con.createStatement()) {
      stmt.execute("CREATE TABLE " + TABLE_NAME
          + " (id int PRIMARY KEY, data1 UUID[], data2 UUID[][])");
    }
  }

  @AfterAll
  public static void tearDown() throws Exception {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
    }
    TestUtil.closeDB(con);
  }

  @Test
  public void basicTest() throws SQLException {
    try (Connection c = assertDoesNotThrow(() -> TestUtil.openDB())) {
      final String typeName = "my_struct";
      final Object[] attributes = {10, "John", true};

      final Struct struct = c.createStruct(typeName, attributes);

      assertEquals(typeName, struct.getSQLTypeName());

      final int expected1 = 10;
      final String expected2 = "John";
      final boolean expected3 = true;

      assertEquals(expected1, struct.getAttributes()[0]);
      assertEquals(expected2, struct.getAttributes()[1]);
      assertEquals(expected3, struct.getAttributes()[2]);
    }
  }

  /**
   * Should an exception be thrown in this scenario?
   * @throws SQLException
   */
  @Test
  public void nullAttributesTest() throws SQLException {
    try (Connection c = assertDoesNotThrow(() -> TestUtil.openDB())) {
      assumeTrue(TestUtil.haveMinimumServerVersion(c, ServerVersion.v9_6));

      final String typeName = "my_struct";
      final Object[] attributes = null;

      final Struct struct = c.createStruct(typeName, attributes);

      assertEquals(typeName, struct.getSQLTypeName());

      assertNull(struct.getAttributes());
    }
  }

  @Test
  void emptyAttributesTest() throws SQLException {
    try (Connection c = assertDoesNotThrow(() -> TestUtil.openDB())) {
      assumeTrue(TestUtil.haveMinimumServerVersion(c, ServerVersion.v9_6));

      final String typeName = "my_struct";
      final Object[] attributes = {};

      final Struct struct = c.createStruct(typeName, attributes);

      assertEquals(typeName, struct.getSQLTypeName());

      // Excepted and actual lengths
      final int expected = 0;
      final int actual = struct.getAttributes().length;

      assertEquals(expected, actual);
    }
  }

  @Test
  @Ignore
  void unsupportedTypeNameTest() throws SQLException {
    try (Connection c = assertDoesNotThrow(() -> TestUtil.openDB())) {
      assumeTrue(TestUtil.haveMinimumServerVersion(c, ServerVersion.v9_6));

      final String typeName = "unsupported_struct";
      final Object[] attributes = {10, "John"};

      // Creating a STRUCT object with an unsupported type name should throw an exception
      // assertThrows(SQLException.class, () -> c.createStruct(typeName, attributes));
    }
  }

  @Test
  void complexStructTest() throws SQLException {
    try (Connection c = assertDoesNotThrow(() -> TestUtil.openDB())) {
      final String typeName = "complex_struct";
      final Object[] attributes = {42, "Test", new int[]{1, 2, 3}};

      Struct struct = c.createStruct(typeName, attributes);

      assertEquals(typeName, struct.getSQLTypeName());

      final int expectedValue1 = 42;
      final String expectedValue2 = "Test";
      final int[] expectedValue3 = {1, 2, 3};

      assertEquals(expectedValue1, struct.getAttributes()[0]);
      assertEquals(expectedValue2, struct.getAttributes()[1]);
      assertArrayEquals(expectedValue3, (int[]) struct.getAttributes()[2]);
    }
  }
}
