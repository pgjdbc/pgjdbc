/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class AutoSaveIsolationTest {
  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    Properties props = new Properties();
    con = TestUtil.openDB(props);
  }

  @AfterEach
  void tearDown() throws Exception {
    con = TestUtil.openDB();
    TestUtil.closeDB(con);
  }

  @Test
  public void testAlwaysAutoSaveBetweenBeginAndSetIsolationLevel() throws SQLException {
    Properties props = new Properties();
    PGProperty.AUTOSAVE.set(props,"always");
    con = TestUtil.openDB(props);
    int initialIsolationLevel = con.getTransactionIsolation();

    con.setAutoCommit(false);

    Statement stmt = con.createStatement();

    try {
      stmt.executeUpdate("set local transaction isolation level serializable read only deferrable");
    } catch (Exception e) {
      fail("Isolation level should have been set before sending a SAVEPOINT PGJDBC_AUTOSAVE");
    }

    int newIsolationLevel = con.getTransactionIsolation();

    assertEquals(Connection.TRANSACTION_SERIALIZABLE, newIsolationLevel);

    con.commit();

    int afterCommitIsolationLevel = con.getTransactionIsolation();

    assertEquals(initialIsolationLevel,afterCommitIsolationLevel);

    stmt.close();
  }

  @Test
  public void testNeverAutoSaveBetweenBeginAndSetIsolationLevel() throws SQLException {
    Properties props = new Properties();
    PGProperty.AUTOSAVE.set(props,"never");//default
    con = TestUtil.openDB(props);
    int initialIsolationLevel = con.getTransactionIsolation();

    con.setAutoCommit(false);

    Statement stmt = con.createStatement();

    try {
      stmt.executeUpdate("set local transaction isolation level serializable read only deferrable");
    } catch (Exception e) {
      fail("Isolation level should have been set successfully");
    }

    int newIsolationLevel = con.getTransactionIsolation();

    assertEquals(Connection.TRANSACTION_SERIALIZABLE, newIsolationLevel);

    con.commit();

    int afterCommitIsolationLevel = con.getTransactionIsolation();

    assertEquals(initialIsolationLevel,afterCommitIsolationLevel);

    stmt.close();
  }

}
