/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import java.sql.*;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CreateStructTest {

  private static Connection con;
  private static final String TABLE_NAME = "uuid_table";
  private static final String INSERT1 = "INSERT INTO " + TABLE_NAME
      + " (id, data1) VALUES (?, ?)";
  private static final String INSERT2 = "INSERT INTO " + TABLE_NAME
      + " (id, data2) VALUES (?, ?)";
  private static final String SELECT1 = "SELECT data1 FROM " + TABLE_NAME
      + " WHERE id = ?";
  private static final String SELECT2 = "SELECT data2 FROM " + TABLE_NAME
      + " WHERE id = ?";
  private static final UUID[] uids1 = new UUID[]{UUID.randomUUID(), UUID.randomUUID()};
  private static final UUID[][] uids2 = new UUID[][]{uids1};

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
  void test1DWithSetObject() throws SQLException {
    try (Connection c = assertDoesNotThrow(() -> TestUtil.openDB());
         PreparedStatement stmt1 = c.prepareStatement(INSERT1);
         PreparedStatement stmt2 = c.prepareStatement(SELECT1)) {
      stmt1.setInt(1, 100);
      stmt1.setArray(2, c.createArrayOf("uuid", uids1));
      stmt1.execute();

      stmt2.setInt(1, 100);
      stmt2.execute();
      try (ResultSet rs = stmt2.getResultSet()) {
        assertTrue(rs.next());
        UUID[] array = (UUID[])rs.getArray(1).getArray();
        assertEquals(uids1[0], array[0]);
        assertEquals(uids1[1], array[1]);
      }
    }
  }

  /*
  @Test
  public void testCreateStruct() throws SQLException {
    try (Connection connection = assertDoesNotThrow(() -> TestUtil.openDB());
      PreparedStatement insertStmt = connection.prepareStatement(INSERT1);
      PreparedStatement selectStmt = connection.prepareStatement(SELECT2)) {
      // Prepare test data
      int id = 101;
      UUID[] uids = {UUID.randomUUID(), UUID.randomUUID()};
      String typeName = "my_struct";
      Object[] attributes = {id, uids};

      // Insert a row with the data
      insertStmt.setInt(1, id);
      insertStmt.setObject(2, attributes);
      insertStmt.execute();

      // Retrieve the row from the database
      selectStmt.setInt(1, id);
      selectStmt.execute();

      try (ResultSet rs = selectStmt.getResultSet()) {
      assertTrue(rs.next());
      Struct struct = rs.getStruct(1);
      Object[] retrievedAttributes = struct.getAttributes();

      // Assert the retrieved attributes
      assertEquals(id, retrievedAttributes[0]);
      assertArrayEquals(uids, (Object[]) retrievedAttributes[1]);
      }
    }
  }
  */
}
