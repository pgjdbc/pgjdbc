/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.PGProperty;
import org.postgresql.core.PGStream;
import org.postgresql.core.QueryExecutor;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

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
   * Tests for session and transaction read only behavior with "always" read only mode.
   */
  @Test
  public void testReadOnly_always() throws Exception {
    final Properties props = new Properties();
    PGProperty.READ_ONLY_MODE.set(props, "always");
    con = TestUtil.openDB(props);
    Statement st;
    ResultSet rs;

    con.setAutoCommit(true);
    con.setReadOnly(true);
    assertTrue(con.getAutoCommit());
    assertTrue(con.isReadOnly());

    // Now test insert with auto commit true and read only
    st = con.createStatement();
    try {
      st.executeUpdate("insert into test_a (imagename,image,id) values ('comttest',1234,5678)");
      fail("insert should have failed when read only");
    } catch (SQLException e) {
      assertStringContains(e.getMessage(), "read-only");
    }

    con.setAutoCommit(false);

    // auto commit false and read only
    try {
      st.executeUpdate("insert into test_a (imagename,image,id) values ('comttest',1234,5678)");
      fail("insert should have failed when read only");
    } catch (SQLException e) {
      assertStringContains(e.getMessage(), "read-only");
    }

    try {
      con.setReadOnly(false);
      fail("cannot set read only during transaction");
    } catch (SQLException e) {
      assertStringContains(e.getMessage(), "Cannot change transaction read-only");
    }

    // end the transaction
    con.rollback();

    // disable read only
    con.setReadOnly(false);

    assertEquals(1, st.executeUpdate("insert into test_a (imagename,image,id) values ('comttest',1234,5678)"));

    // Now update image to 9876 and commit
    st.executeUpdate("update test_a set image=9876 where id=5678");
    con.commit();

    // back to read only for successful query
    con.setReadOnly(true);
    rs = st.executeQuery("select image from test_a where id=5678");
    assertTrue(rs.next());
    assertEquals(9876, rs.getInt(1));
    rs.close();

    // Now try to change with auto commit false
    try {
      st.executeUpdate("update test_a set image=1111 where id=5678");
      fail("update should fail when read only");
    } catch (SQLException e) {
      assertStringContains(e.getMessage(), "read-only");
      con.rollback();
    }

    // test that value did not change
    rs = st.executeQuery("select image from test_a where id=5678");
    assertTrue(rs.next());
    assertEquals(9876, rs.getInt(1)); // Should not change!
    rs.close();

    // repeat attempt to chagne with auto commit true
    con.setAutoCommit(true);

    try {
      st.executeUpdate("update test_a set image=1111 where id=5678");
      fail("update should fail when read only");
    } catch (SQLException e) {
      assertStringContains(e.getMessage(), "read-only");
    }

    // test that value did not change
    rs = st.executeQuery("select image from test_a where id=5678");
    assertTrue(rs.next());
    assertEquals(9876, rs.getInt(1)); // Should not change!
    rs.close();

    TestUtil.closeDB(con);
  }

  /*
   * Tests for session and transaction read only behavior with "ignore" read only mode.
   */
  @Test
  public void testReadOnly_ignore() throws Exception {
    final Properties props = new Properties();
    PGProperty.READ_ONLY_MODE.set(props, "ignore");
    con = TestUtil.openDB(props);
    Statement st;
    ResultSet rs;

    con.setAutoCommit(true);
    con.setReadOnly(true);
    assertTrue(con.getAutoCommit());
    assertTrue(con.isReadOnly());

    // Now test insert with auto commit true and read only
    st = con.createStatement();
    assertEquals(1, st.executeUpdate("insert into test_a (imagename,image,id) values ('comttest',1234,5678)"));
    con.setAutoCommit(false);

    // Now update image to 9876 and commit
    st.executeUpdate("update test_a set image=9876 where id=5678");

    // back to read only for successful query
    rs = st.executeQuery("select image from test_a where id=5678");
    assertTrue(rs.next());
    assertEquals(9876, rs.getInt(1));
    rs.close();

    con.rollback();

    // test that value did not change
    rs = st.executeQuery("select image from test_a where id=5678");
    assertTrue(rs.next());
    assertEquals(1234, rs.getInt(1)); // Should not change!
    rs.close();

    TestUtil.closeDB(con);
  }

  /*
   * Tests for session and transaction read only behavior with "transaction" read only mode.
   */
  @Test
  public void testReadOnly_transaction() throws Exception {
    final Properties props = new Properties();
    PGProperty.READ_ONLY_MODE.set(props, "transaction");
    con = TestUtil.openDB(props);
    Statement st;
    ResultSet rs;

    con.setAutoCommit(false);
    con.setReadOnly(true);
    assertFalse(con.getAutoCommit());
    assertTrue(con.isReadOnly());

    // Test insert with auto commit false and read only
    st = con.createStatement();
    try {
      st.executeUpdate("insert into test_a (imagename,image,id) values ('comttest',1234,5678)");
      fail("insert should have failed when read only");
    } catch (SQLException e) {
      assertStringContains(e.getMessage(), "read-only");
    }

    con.rollback();

    con.setAutoCommit(true);
    assertTrue(con.isReadOnly());
    //with autocommit true and read only, can still insert
    assertEquals(1, st.executeUpdate("insert into test_a (imagename,image,id) values ('comttest',1234,5678)"));

    // Now update image to 9876
    st.executeUpdate("update test_a set image=9876 where id=5678");

    //successful query
    rs = st.executeQuery("select image from test_a where id=5678");
    assertTrue(rs.next());
    assertEquals(9876, rs.getInt(1));
    rs.close();

    con.setAutoCommit(false);
    // Now try to change with auto commit false
    try {
      st.executeUpdate("update test_a set image=1111 where id=5678");
      fail("update should fail when read only");
    } catch (SQLException e) {
      assertStringContains(e.getMessage(), "read-only");
    }

    con.rollback();

    // test that value did not change
    rs = st.executeQuery("select image from test_a where id=5678");
    assertTrue(rs.next());
    assertEquals(9876, rs.getInt(1)); // Should not change!
    rs.close();

    // repeat attempt to chagne with auto commit true
    con.setAutoCommit(true);

    assertEquals(1, st.executeUpdate("update test_a set image=1111 where id=5678"));

    // test that value did not change
    rs = st.executeQuery("select image from test_a where id=5678");
    assertTrue(rs.next());
    assertEquals(1111, rs.getInt(1)); // Should not change!
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

  /**
   * Make sure that type map is empty and not null
   */
  @Test
  public void testGetTypeMapEmpty() throws Exception {
    con = TestUtil.openDB();
    Map typeMap = con.getTypeMap();
    assertNotNull(typeMap);
    assertTrue("TypeMap should be empty", typeMap.isEmpty());
    con.close();
  }

  @Test
  public void testPGStreamSettings() throws Exception {
    con = TestUtil.openDB();
    QueryExecutor queryExecutor = ((PgConnection)con).getQueryExecutor();

    Field f = queryExecutor.getClass().getSuperclass().getDeclaredField("pgStream");
    f.setAccessible(true);
    PGStream pgStream = (PGStream)f.get(queryExecutor);
    pgStream.setNetworkTimeout(1000);
    pgStream.getSocket().setKeepAlive(true);
    pgStream.getSocket().setSendBufferSize(8192);
    pgStream.getSocket().setReceiveBufferSize(2048);
    PGStream newStream = new PGStream(pgStream, 10);
    assertEquals(1000, newStream.getSocket().getSoTimeout());
    assertEquals(2048, newStream.getSocket().getReceiveBufferSize());
    assertEquals(8192, newStream.getSocket().getSendBufferSize());
    assertTrue(newStream.getSocket().getKeepAlive());

    TestUtil.closeDB(con);
  }

  private static void assertStringContains(String orig, String toContain) {
    if (!orig.contains(toContain)) {
      fail("expected [" + orig + ']' + "to contain [" + toContain + "].");
    }
  }
}
