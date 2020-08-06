/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.EscapeSyntaxCallMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class ProcedureTransactionTest extends BaseTest4 {

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.ESCAPE_SYNTAX_CALL_MODE.set(props, EscapeSyntaxCallMode.CALL_IF_NO_RETURN.value());
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    Statement stmt = con.createStatement();
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v11)) {
      stmt.execute("create temp table proc_test ( some_val bigint )");
      stmt.execute(
          "CREATE OR REPLACE PROCEDURE mycommitproc(a INOUT bigint) AS 'BEGIN INSERT INTO proc_test values(a); commit; END;' LANGUAGE plpgsql");
      stmt.execute(
          "CREATE OR REPLACE PROCEDURE myrollbackproc(a INOUT bigint) AS 'BEGIN INSERT INTO proc_test values(a); rollback; END;' LANGUAGE plpgsql");
      stmt.execute(
          "CREATE OR REPLACE PROCEDURE mynotxnproc(a INOUT bigint) AS 'BEGIN INSERT INTO proc_test values(a); END;' LANGUAGE plpgsql");
    }
  }

  @Override
  public void tearDown() throws SQLException {
    Statement stmt = con.createStatement();
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v11)) {
      stmt.execute("drop procedure mycommitproc(a INOUT bigint) ");
      stmt.execute("drop procedure myrollbackproc(a INOUT bigint) ");
      stmt.execute("drop procedure mynotxnproc(a INOUT bigint) ");
      stmt.execute("drop table proc_test ");
    }
    stmt.close();
    super.tearDown();
  }

  @Test
  public void testProcWithNoTxnControl() throws SQLException {
    assumeMinimumServerVersion(ServerVersion.v11);
    assumeCallableStatementsSupported();
    CallableStatement cs = con.prepareCall("call mynotxnproc(?)");
    int val = 1;
    cs.setInt(1, val);
    cs.execute();
    TestUtil.closeQuietly(cs);

    cs = con.prepareCall("select some_val from proc_test where some_val = ?");
    cs.setInt(1, val);
    ResultSet rs = cs.executeQuery();

    assertTrue(rs.next());
    assertTrue(rs.getInt(1) == val);

    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(cs);
  }

  @Test
  public void testProcWithCommitInside() throws SQLException {
    assumeMinimumServerVersion(ServerVersion.v11);
    assumeCallableStatementsSupported();
    CallableStatement cs = con.prepareCall("call mycommitproc(?)");
    int val = 2;
    cs.setInt(1, val);
    cs.execute();
    TestUtil.closeQuietly(cs);

    cs = con.prepareCall("select some_val from proc_test where some_val = ?");
    cs.setInt(1, val);
    ResultSet rs = cs.executeQuery();

    assertTrue(rs.next());
    assertTrue(rs.getInt(1) == val);

    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(cs);
  }

  @Test
  public void testProcWithRollbackInside() throws SQLException {
    assumeMinimumServerVersion(ServerVersion.v11);
    assumeCallableStatementsSupported();
    CallableStatement cs = con.prepareCall("call myrollbackproc(?)");
    int val = 3;
    cs.setInt(1, val);
    cs.execute();
    TestUtil.closeQuietly(cs);

    cs = con.prepareCall("select some_val from proc_test where some_val = ?");
    cs.setInt(1, val);
    ResultSet rs = cs.executeQuery();

    assertFalse(rs.next());

    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(cs);
  }

  @Test
  public void testProcAutoCommitTrue() throws SQLException {
    con.setAutoCommit(true);
    testProcAutoCommit();
  }

  @Test
  public void testProcAutoCommitFalse() throws SQLException {
    // setting autocommit false enables application transaction control, meaning JDBC driver issues a BEGIN
    // as of PostgreSQL 11, Stored Procedures with transaction control inside the procedure cannot be
    // invoked inside a transaction, the procedure must start the top level transaction
    // see: https://www.postgresql.org/docs/current/plpgsql-transactions.html
    con.setAutoCommit(false);
    try {
      testProcAutoCommit();
      fail("Should throw an exception");
    } catch (SQLException ex) {
      //2D000 invalid_transaction_termination
      assertTrue(ex.getSQLState().equalsIgnoreCase(PSQLState.INVALID_TRANSACTION_TERMINATION.getState()));
      con.rollback();
    }

  }

  private void testProcAutoCommit() throws SQLException {
    assumeMinimumServerVersion(ServerVersion.v11);
    assumeCallableStatementsSupported();
    CallableStatement cs = con.prepareCall("call mycommitproc(?)");
    int val = 4;
    cs.setInt(1, val);
    cs.execute();
    TestUtil.closeQuietly(cs);

    cs = con.prepareCall("select some_val from proc_test where some_val = ?");
    cs.setInt(1, val);
    ResultSet rs = cs.executeQuery();

    assertTrue(rs.next());
    assertTrue(rs.getInt(1) == val);

    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(cs);
  }

}
