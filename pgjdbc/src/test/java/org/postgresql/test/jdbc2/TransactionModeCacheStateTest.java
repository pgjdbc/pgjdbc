/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

/**
 * Test the functionality of the transaction mode state cache.
 */
@RunWith(Parameterized.class)
public class TransactionModeCacheStateTest {

  @Parameters(name = "{index}: cacheReadOnly={0}, cacheIsolation={1}, readOnlyMode={2}, autoCommitMode={3}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
      {true, true, true, true}, {true, true, true, false},
      {true, true, false, false}, {true, true, false, true},
      {true, false, false, false}, {true, false, true, false},
      {true, false, false, true}, {true, false, true, true},
      {false, false, false, false}, {false, false, false, true},
      {false, false, true, true}, {false, false, true, false},
      {false, true, true, true}, {false, true, false, true},
      {false, true, true, false}, {false, true, false, false}
    });
  }

  private final boolean cacheReadOnly;
  private final boolean cacheIsolation;
  private final boolean readOnlyMode;
  private final boolean autoCommitMode;

  public TransactionModeCacheStateTest(boolean cacheReadOnly, boolean cacheIsolation, boolean readOnlyMode, boolean autoCommitMode) {
    this.cacheReadOnly = cacheReadOnly;
    this.cacheIsolation = cacheIsolation;
    this.readOnlyMode = readOnlyMode;
    this.autoCommitMode = autoCommitMode;
  }

  @Test
  public void testReadOnlyCache() throws Exception {
    Properties prop = new Properties();
    prop.setProperty(PGProperty.CACHE_ISOLATION_STATE.getName(), String.valueOf(cacheIsolation));
    prop.setProperty(PGProperty.CACHE_READ_ONLY_STATE.getName(), String.valueOf(cacheReadOnly));
    prop.setProperty(PGProperty.READ_ONLY.getName(), String.valueOf(readOnlyMode));

    Connection con = TestUtil.openDB(prop);
    Statement stmt;
    ResultSet rs;

    Assert.assertEquals("Connection started as read-only: " + readOnlyMode, readOnlyMode, con.isReadOnly());

    // Set and Get
    con.setReadOnly(false);
    Assert.assertFalse(con.isReadOnly());
    con.setReadOnly(true);
    Assert.assertTrue(con.isReadOnly());

    con.setReadOnly(false);
    stmt = con.createStatement();
    Assert.assertFalse("Check between createStatement", con.isReadOnly());
    rs = stmt.executeQuery("SELECT 1");
    con.setReadOnly(true);
    rs.close();
    Assert.assertTrue("Check between close ResultSet", con.isReadOnly());
    stmt.close();

    // Test double set of read-only, it's generally a no-op.
    con.setReadOnly(false);
    con.setReadOnly(false);
    Assert.assertFalse("Double set false", con.isReadOnly());
    con.setReadOnly(true);
    con.setReadOnly(true);
    Assert.assertTrue("Double set true", con.isReadOnly());

    rs.close();
    stmt.close();
    TestUtil.closeDB(con);
  }

  @Test
  public void testReadOnlyCacheAutoCommit() throws Exception {
    Properties prop = new Properties();
    prop.setProperty(PGProperty.CACHE_ISOLATION_STATE.getName(), String.valueOf(cacheIsolation));
    prop.setProperty(PGProperty.CACHE_READ_ONLY_STATE.getName(), String.valueOf(cacheReadOnly));
    prop.setProperty(PGProperty.READ_ONLY.getName(), String.valueOf(readOnlyMode));

    Connection con = TestUtil.openDB(prop);
    Statement stmt;
    ResultSet rs;

    // Test with autocommit on/off and between transactions
    con.setAutoCommit(autoCommitMode);
    con.setReadOnly(true);
    stmt = con.createStatement();
    Assert.assertTrue("No transaction started", con.isReadOnly());
    rs = stmt.executeQuery("SELECT 1"); // Start of transaction
    try {
      if (!con.getAutoCommit()) {
        con.setReadOnly(false);
        Assert.fail("Cannot change transaction read-only property in the middle of a transaction.");
      }
    } catch (SQLException ex) {
      Assert.assertEquals(PSQLState.ACTIVE_SQL_TRANSACTION.getState(), ex.getSQLState());
    }
    if (!con.getAutoCommit()) {
      con.commit(); // End transaction
      con.setReadOnly(false);
      Assert.assertFalse("Allow change after end of transaction", con.isReadOnly());
    }

    rs.close();
    stmt.close();
    TestUtil.closeDB(con);
  }

  @Test
  public void testTransactionIsolationCache() throws Exception {
    Properties prop = new Properties();
    prop.setProperty(PGProperty.CACHE_ISOLATION_STATE.getName(), String.valueOf(cacheIsolation));
    prop.setProperty(PGProperty.CACHE_READ_ONLY_STATE.getName(), String.valueOf(cacheReadOnly));
    prop.setProperty(PGProperty.READ_ONLY.getName(), String.valueOf(readOnlyMode));

    Connection con = TestUtil.openDB(prop);
    DatabaseMetaData dbmd = con.getMetaData();
    Statement stmt;
    ResultSet rs;

    // Check support of transaction isolation level
    Assert.assertTrue(dbmd.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
    Assert.assertTrue(dbmd.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_UNCOMMITTED));
    Assert.assertTrue(dbmd.supportsTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ));
    Assert.assertTrue(dbmd.supportsTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE));
    Assert.assertFalse(dbmd.supportsTransactionIsolationLevel(Connection.TRANSACTION_NONE));
    Assert.assertFalse(dbmd.supportsTransactionIsolationLevel(-1));
    Assert.assertFalse(dbmd.supportsTransactionIsolationLevel(Integer.MAX_VALUE));

    Assert.assertNotEquals("Connection isolation never -1", -1, con.getTransactionIsolation());
    Assert.assertTrue("Connection must be a supported isolation",
        dbmd.supportsTransactionIsolationLevel(con.getTransactionIsolation()));

    // Set and Get
    con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    Assert.assertEquals(Connection.TRANSACTION_REPEATABLE_READ, con.getTransactionIsolation());

    stmt = con.createStatement();
    Assert.assertEquals("Check between createStatement",
        Connection.TRANSACTION_REPEATABLE_READ, con.getTransactionIsolation());
    rs = stmt.executeQuery("SELECT 1");
    con.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
    rs.close();
    Assert.assertEquals("Check between close ResultSet",
        Connection.TRANSACTION_READ_UNCOMMITTED, con.getTransactionIsolation());
    stmt.close();

    // Test double set of isolation level, it's generally a no-op.
    con.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
    con.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
    Assert.assertEquals("Double set TRANSACTION_READ_UNCOMMITTED",
        Connection.TRANSACTION_READ_UNCOMMITTED, con.getTransactionIsolation());
    con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Assert.assertEquals("Double set TRANSACTION_READ_COMMITTED",
        Connection.TRANSACTION_READ_COMMITTED, con.getTransactionIsolation());
    con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    Assert.assertEquals("Double set TRANSACTION_REPEATABLE_READ",
        Connection.TRANSACTION_REPEATABLE_READ, con.getTransactionIsolation());
    con.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    con.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    Assert.assertEquals("Double set TRANSACTION_SERIALIZABLE",
        Connection.TRANSACTION_SERIALIZABLE, con.getTransactionIsolation());

    rs.close();
    stmt.close();
    TestUtil.closeDB(con);
  }

  @Test
  public void testTransactionIsolationCacheAutoCommit() throws Exception {
    Properties prop = new Properties();
    prop.setProperty(PGProperty.CACHE_ISOLATION_STATE.getName(), String.valueOf(cacheIsolation));
    prop.setProperty(PGProperty.CACHE_READ_ONLY_STATE.getName(), String.valueOf(cacheReadOnly));
    prop.setProperty(PGProperty.READ_ONLY.getName(), String.valueOf(readOnlyMode));

    Connection con = TestUtil.openDB(prop);
    Statement stmt;
    ResultSet rs;

    // Test with autocommit on/off and between transactions
    con.setAutoCommit(autoCommitMode);
    con.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
    stmt = con.createStatement();
    Assert.assertEquals("No transaction started",
        Connection.TRANSACTION_READ_UNCOMMITTED, con.getTransactionIsolation());
    rs = stmt.executeQuery("SELECT 1"); // Start of transaction
    try {
      if (!con.getAutoCommit()) {
        con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        Assert.fail("Cannot change transaction isolation level in the middle of a transaction.");
      }
    } catch (SQLException ex) {
      Assert.assertEquals(PSQLState.ACTIVE_SQL_TRANSACTION.getState(), ex.getSQLState());
    }
    if (!con.getAutoCommit()) {
      con.commit(); // End transaction
      con.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      Assert.assertEquals("Allow change after end of transaction",
          Connection.TRANSACTION_SERIALIZABLE, con.getTransactionIsolation());
    }

    // Invalid isolation levels
    try {
      con.setTransactionIsolation(Connection.TRANSACTION_NONE);
      Assert.fail("Transaction isolation level TRANSACTION_NONE not supported.");
    } catch (SQLException ex) {
      Assert.assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), ex.getSQLState());
    }
    try {
      con.setTransactionIsolation(Integer.MIN_VALUE);
      Assert.fail("Invalid Transaction isolation level (not supported)");
    } catch (SQLException ex) {
      Assert.assertEquals(PSQLState.NOT_IMPLEMENTED.getState(), ex.getSQLState());
    }

    rs.close();
    stmt.close();
    TestUtil.closeDB(con);
  }

}
