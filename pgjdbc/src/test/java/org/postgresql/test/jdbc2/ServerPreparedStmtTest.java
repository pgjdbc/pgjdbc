/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGStatement;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.Assume;
import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/*
 * Tests for using server side prepared statements
 */
public class ServerPreparedStmtTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();

    Assume.assumeTrue("Server-prepared statements are not supported in simple protocol, thus ignoring the tests",
        preferQueryMode != PreferQueryMode.SIMPLE);

    Statement stmt = con.createStatement();

    TestUtil.createTable(con, "testsps", "id integer, value boolean");

    stmt.executeUpdate("INSERT INTO testsps VALUES (1,'t')");
    stmt.executeUpdate("INSERT INTO testsps VALUES (2,'t')");
    stmt.executeUpdate("INSERT INTO testsps VALUES (3,'t')");
    stmt.executeUpdate("INSERT INTO testsps VALUES (4,'t')");
    stmt.executeUpdate("INSERT INTO testsps VALUES (6,'t')");
    stmt.executeUpdate("INSERT INTO testsps VALUES (9,'f')");

    stmt.close();
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testsps");
    super.tearDown();
  }

  @Test
  public void testEmptyResults() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT * FROM testsps WHERE id = ?");
    ((PGStatement) pstmt).setUseServerPrepare(true);
    for (int i = 0; i < 10; ++i) {
      pstmt.setInt(1, -1);
      ResultSet rs = pstmt.executeQuery();
      assertFalse(rs.next());
      rs.close();
    }
    pstmt.close();
  }

  @Test
  public void testPreparedExecuteCount() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("UPDATE testsps SET id = id + 44");
    ((PGStatement) pstmt).setUseServerPrepare(true);
    int count = pstmt.executeUpdate();
    assertEquals(6, count);
    pstmt.close();
  }


  @Test
  public void testPreparedStatementsNoBinds() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT * FROM testsps WHERE id = 2");
    ((PGStatement) pstmt).setUseServerPrepare(true);
    assertTrue(((PGStatement) pstmt).isUseServerPrepare());

    // Test that basic functionality works
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    rs.close();

    // Verify that subsequent calls still work
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    rs.close();

    // Verify that using the statement still works after turning off prepares


    if (Boolean.getBoolean("org.postgresql.forceBinary")) {
      return;
    }
    ((PGStatement) pstmt).setUseServerPrepare(false);
    assertTrue(!((PGStatement) pstmt).isUseServerPrepare());

    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    rs.close();

    pstmt.close();
  }

  @Test
  public void testPreparedStatementsWithOneBind() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT * FROM testsps WHERE id = ?");
    ((PGStatement) pstmt).setUseServerPrepare(true);
    assertTrue(((PGStatement) pstmt).isUseServerPrepare());

    // Test that basic functionality works
    pstmt.setInt(1, 2);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    rs.close();

    // Verify that subsequent calls still work
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    rs.close();

    // Verify that using the statement still works after turning off prepares
    if (Boolean.getBoolean("org.postgresql.forceBinary")) {
      return;
    }

    ((PGStatement) pstmt).setUseServerPrepare(false);
    assertTrue(!((PGStatement) pstmt).isUseServerPrepare());

    pstmt.setInt(1, 9);
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(9, rs.getInt(1));
    rs.close();

    pstmt.close();
  }

  // Verify we can bind booleans-as-objects ok.
  @Test
  public void testBooleanObjectBind() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT * FROM testsps WHERE value = ?");
    ((PGStatement) pstmt).setUseServerPrepare(true);
    assertTrue(((PGStatement) pstmt).isUseServerPrepare());

    pstmt.setObject(1, Boolean.FALSE, java.sql.Types.BIT);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(9, rs.getInt(1));
    rs.close();
  }

  // Verify we can bind booleans-as-integers ok.
  @Test
  public void testBooleanIntegerBind() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT * FROM testsps WHERE id = ?");
    ((PGStatement) pstmt).setUseServerPrepare(true);
    assertTrue(((PGStatement) pstmt).isUseServerPrepare());

    pstmt.setObject(1, Boolean.TRUE, java.sql.Types.INTEGER);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    rs.close();
  }

  // Verify we can bind booleans-as-native-types ok.
  @Test
  public void testBooleanBind() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT * FROM testsps WHERE value = ?");
    ((PGStatement) pstmt).setUseServerPrepare(true);
    assertTrue(((PGStatement) pstmt).isUseServerPrepare());

    pstmt.setBoolean(1, false);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(9, rs.getInt(1));
    rs.close();
  }

  @Test
  public void testPreparedStatementsWithBinds() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT * FROM testsps WHERE id = ? or id = ?");
    ((PGStatement) pstmt).setUseServerPrepare(true);
    assertTrue(((PGStatement) pstmt).isUseServerPrepare());

    // Test that basic functionality works
    // bind different datatypes
    pstmt.setInt(1, 2);
    pstmt.setLong(2, 2);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    rs.close();

    // Verify that subsequent calls still work
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    rs.close();

    pstmt.close();
  }

  @Test
  public void testSPSToggle() throws Exception {
    // Verify we can toggle UseServerPrepare safely before a query is executed.
    PreparedStatement pstmt = con.prepareStatement("SELECT * FROM testsps WHERE id = 2");
    ((PGStatement) pstmt).setUseServerPrepare(true);
    ((PGStatement) pstmt).setUseServerPrepare(false);
  }

  @Test
  public void testBytea() throws Exception {
    // Verify we can use setBytes() with a server-prepared update.
    try {
      TestUtil.createTable(con, "testsps_bytea", "data bytea");

      PreparedStatement pstmt = con.prepareStatement("INSERT INTO testsps_bytea(data) VALUES (?)");
      ((PGStatement) pstmt).setUseServerPrepare(true);
      pstmt.setBytes(1, new byte[100]);
      pstmt.executeUpdate();
    } finally {
      TestUtil.dropTable(con, "testsps_bytea");
    }
  }

  // Check statements are not transformed when they shouldn't be.
  @Test
  public void testCreateTable() throws Exception {
    // CREATE TABLE isn't supported by PREPARE; the driver should realize this and
    // still complete without error.
    PreparedStatement pstmt = con.prepareStatement("CREATE TABLE testsps_bad(data int)");
    ((PGStatement) pstmt).setUseServerPrepare(true);
    pstmt.executeUpdate();
    TestUtil.dropTable(con, "testsps_bad");
  }

  @Test
  public void testMultistatement() throws Exception {
    // Shouldn't try to PREPARE this one, if we do we get:
    // PREPARE x(int,int) AS INSERT .... $1 ; INSERT ... $2 -- syntax error
    try {
      TestUtil.createTable(con, "testsps_multiple", "data int");
      PreparedStatement pstmt = con.prepareStatement(
          "INSERT INTO testsps_multiple(data) VALUES (?); INSERT INTO testsps_multiple(data) VALUES (?)");
      ((PGStatement) pstmt).setUseServerPrepare(true);
      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.executeUpdate(); // Two inserts.

      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.executeUpdate(); // Two more inserts.

      ResultSet check = con.createStatement().executeQuery("SELECT COUNT(*) FROM testsps_multiple");
      assertTrue(check.next());
      assertEquals(4, check.getInt(1));
    } finally {
      TestUtil.dropTable(con, "testsps_multiple");
    }
  }

  @Test
  public void testTypeChange() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT CAST (? AS TEXT)");
    ((PGStatement) pstmt).setUseServerPrepare(true);

    // Prepare with int parameter.
    pstmt.setInt(1, 1);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertTrue(!rs.next());

    // Change to text parameter, check it still works.
    pstmt.setString(1, "test string");
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals("test string", rs.getString(1));
    assertTrue(!rs.next());
  }
}
