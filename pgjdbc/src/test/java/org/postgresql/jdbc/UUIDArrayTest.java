/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

class UUIDArrayTest {

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
  static void setUp() throws Exception {
    con = TestUtil.openDB();
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_6));
    try (Statement stmt = con.createStatement()) {
      stmt.execute("CREATE TABLE " + TABLE_NAME
          + " (id int PRIMARY KEY, data1 UUID[], data2 UUID[][])");
    }
  }

  @AfterAll
  static void tearDown() throws Exception {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
    }
    TestUtil.closeDB(con);
  }

  @Test
  void test1DWithCreateArrayOf() throws SQLException {
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
        UUID[] array = (UUID[]) rs.getArray(1).getArray();
        assertEquals(uids1[0], array[0]);
        assertEquals(uids1[1], array[1]);
      }
    }
  }

  @Test
  void test1DWithSetObject() throws SQLException {
    try (Connection c = assertDoesNotThrow(() -> TestUtil.openDB());
         PreparedStatement stmt1 = c.prepareStatement(INSERT1);
         PreparedStatement stmt2 = c.prepareStatement(SELECT1)) {
      stmt1.setInt(1, 101);
      stmt1.setObject(2, uids1);
      stmt1.execute();

      stmt2.setInt(1, 101);
      stmt2.execute();
      try (ResultSet rs = stmt2.getResultSet()) {
        assertTrue(rs.next());
        UUID[] array = (UUID[]) rs.getArray(1).getArray();
        assertEquals(uids1[0], array[0]);
        assertEquals(uids1[1], array[1]);
      }
    }
  }

  @Test
  void test2DWithCreateArrayOf() throws SQLException {
    try (Connection c = assertDoesNotThrow(() -> TestUtil.openDB());
         PreparedStatement stmt1 = c.prepareStatement(INSERT2);
         PreparedStatement stmt2 = c.prepareStatement(SELECT2)) {
      stmt1.setInt(1, 200);
      stmt1.setArray(2, c.createArrayOf("uuid", uids2));
      stmt1.execute();

      stmt2.setInt(1, 200);
      stmt2.execute();
      try (ResultSet rs = stmt2.getResultSet()) {
        assertTrue(rs.next());
        UUID[][] array = (UUID[][]) rs.getArray(1).getArray();
        assertEquals(uids2[0][0], array[0][0]);
        assertEquals(uids2[0][1], array[0][1]);
      }
    }
  }

  @Test
  void test2DWithSetObject() throws SQLException {
    try (Connection c = assertDoesNotThrow(() -> TestUtil.openDB());
         PreparedStatement stmt1 = c.prepareStatement(INSERT2);
         PreparedStatement stmt2 = c.prepareStatement(SELECT2)) {
      stmt1.setInt(1, 201);
      stmt1.setObject(2, uids2);
      stmt1.execute();

      stmt2.setInt(1, 201);
      stmt2.execute();
      try (ResultSet rs = stmt2.getResultSet()) {
        assertTrue(rs.next());
        UUID[][] array = (UUID[][]) rs.getArray(1).getArray();
        assertEquals(uids2[0][0], array[0][0]);
        assertEquals(uids2[0][1], array[0][1]);
      }
    }
  }
}
