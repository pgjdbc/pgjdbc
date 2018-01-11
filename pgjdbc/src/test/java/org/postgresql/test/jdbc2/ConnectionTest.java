/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * TestCase to test the internal functionality of org.postgresql.jdbc2.Connection and it's
 * superclass.
 */
public class ConnectionTest {
  private Connection con;

  // Set up the fixture for this testcase: the tables for this test.
  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();

    TestUtil.createTable(con, "test_a", "imagename name,image oid,id int4");
    TestUtil.createTable(con, "test_c", "source text,cost money,imageid int4");

    TestUtil.closeDB(con);
  }

  // Tear down the fixture for this test case.
  @After
  public void tearDown() throws Exception {
    TestUtil.closeDB(con);

    con = TestUtil.openDB();

    TestUtil.dropTable(con, "test_a");
    TestUtil.dropTable(con, "test_c");

    TestUtil.closeDB(con);
  }

  /*
   * Tests the two forms of createStatement()
   */
  @Test
  public void testCreateStatement() throws Exception {
    con = TestUtil.openDB();

    // A standard Statement
    Statement stat = con.createStatement();
    assertNotNull(stat);
    stat.close();

    // Ask for Updateable ResultSets
    stat = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    assertNotNull(stat);
    stat.close();
  }

  /*
   * Tests the two forms of prepareStatement()
   */
  @Test
  public void testPrepareStatement() throws Exception {
    con = TestUtil.openDB();

    String sql = "select source,cost,imageid from test_c";

    // A standard Statement
    PreparedStatement stat = con.prepareStatement(sql);
    assertNotNull(stat);
    stat.close();

    // Ask for Updateable ResultSets
    stat = con.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    assertNotNull(stat);
    stat.close();
  }

  /*
   * Put the test for createPrepareCall here
   */
  @Test
  public void testPrepareCall() {
  }

  /*
   * Test nativeSQL
   */
  @Test
  public void testNativeSQL() throws Exception {
    // test a simple escape
    con = TestUtil.openDB();
    assertEquals("DATE '2005-01-24'", con.nativeSQL("{d '2005-01-24'}"));
  }

  /*
   * Test autoCommit (both get & set)
   */
  @Test
  public void testTransactions() throws Exception {
    con = TestUtil.openDB();
    Statement st;
    ResultSet rs;

    // Turn it off
    con.setAutoCommit(false);
    assertTrue(!con.getAutoCommit());

    // Turn it back on
    con.setAutoCommit(true);
    assertTrue(con.getAutoCommit());

    // Now test commit
    st = con.createStatement();
    st.executeUpdate("insert into test_a (imagename,image,id) values ('comttest',1234,5678)");

    con.setAutoCommit(false);

    // Now update image to 9876 and commit
    st.executeUpdate("update test_a set image=9876 where id=5678");
    con.commit();
    rs = st.executeQuery("select image from test_a where id=5678");
    assertTrue(rs.next());
    assertEquals(9876, rs.getInt(1));
    rs.close();

    // Now try to change it but rollback
    st.executeUpdate("update test_a set image=1111 where id=5678");
    con.rollback();
    rs = st.executeQuery("select image from test_a where id=5678");
    assertTrue(rs.next());
    assertEquals(9876, rs.getInt(1)); // Should not change!
    rs.close();

    TestUtil.closeDB(con);
  }

  /*
   * Simple test to see if isClosed works.
   */
  @Test
  public void testIsClosed() throws Exception {
    con = TestUtil.openDB();

    // Should not say closed
    assertTrue(!con.isClosed());

    TestUtil.closeDB(con);

    // Should now say closed
    assertTrue(con.isClosed());
  }

  /*
   * Test the warnings system
   */
  @Test
  public void testWarnings() throws Exception {
    con = TestUtil.openDB();

    String testStr = "This Is OuR TeSt message";

    // The connection must be ours!
    assertTrue(con instanceof org.postgresql.PGConnection);

    // Clear any existing warnings
    con.clearWarnings();

    // Set the test warning
    ((PgConnection) con).addWarning(new SQLWarning(testStr));

    // Retrieve it
    SQLWarning warning = con.getWarnings();
    assertNotNull(warning);
    assertEquals(testStr, warning.getMessage());

    // Finally test clearWarnings() this time there must be something to delete
    con.clearWarnings();
    assertNull(con.getWarnings());

    TestUtil.closeDB(con);
  }

  /*
   * Transaction Isolation Levels
   */
  @Test
  public void testTransactionIsolation() throws Exception {
    con = TestUtil.openDB();

    int defaultLevel = con.getTransactionIsolation();

    // Begin a transaction
    con.setAutoCommit(false);

    // The isolation level should not have changed
    assertEquals(defaultLevel, con.getTransactionIsolation());

    // Now run some tests with autocommit enabled.
    con.setAutoCommit(true);

    assertEquals(defaultLevel, con.getTransactionIsolation());

    con.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    assertEquals(Connection.TRANSACTION_SERIALIZABLE, con.getTransactionIsolation());

    con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    assertEquals(Connection.TRANSACTION_READ_COMMITTED, con.getTransactionIsolation());

    // Test if a change of isolation level before beginning the
    // transaction affects the isolation level inside the transaction.
    con.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    assertEquals(Connection.TRANSACTION_SERIALIZABLE, con.getTransactionIsolation());
    con.setAutoCommit(false);
    assertEquals(Connection.TRANSACTION_SERIALIZABLE, con.getTransactionIsolation());
    con.setAutoCommit(true);
    assertEquals(Connection.TRANSACTION_SERIALIZABLE, con.getTransactionIsolation());
    con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    assertEquals(Connection.TRANSACTION_READ_COMMITTED, con.getTransactionIsolation());
    con.setAutoCommit(false);
    assertEquals(Connection.TRANSACTION_READ_COMMITTED, con.getTransactionIsolation());
    con.commit();

    // Test that getTransactionIsolation() does not actually start a new txn.
    // Shouldn't start a new transaction.
    con.getTransactionIsolation();
    // Should be ok -- we're not in a transaction.
    con.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    // Should still be ok.
    con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

    // Test that we can't change isolation mid-transaction
    Statement stmt = con.createStatement();
    stmt.executeQuery("SELECT 1"); // Start transaction.
    stmt.close();

    try {
      con.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      fail("Expected an exception when changing transaction isolation mid-transaction");
    } catch (SQLException e) {
      // Ok.
    }

    con.rollback();
    TestUtil.closeDB(con);
  }

  /*
   * JDBC2 Type mappings
   */
  @Test
  public void testTypeMaps() throws Exception {
    con = TestUtil.openDB();

    // preserve the current map
    Map<String, Class<?>> oldmap = con.getTypeMap();

    // now change it for an empty one
    Map<String, Class<?>> newmap = new HashMap<String, Class<?>>();
    con.setTypeMap(newmap);
    assertEquals(newmap, con.getTypeMap());

    // restore the old one
    con.setTypeMap(oldmap);
    assertEquals(oldmap, con.getTypeMap());

    TestUtil.closeDB(con);
  }

  /**
   * Closing a Connection more than once is not an error.
   */
  @Test
  public void testDoubleClose() throws Exception {
    con = TestUtil.openDB();
    con.close();
    con.close();
  }
}
