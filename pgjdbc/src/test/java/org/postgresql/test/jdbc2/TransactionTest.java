/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;

public class TransactionTest extends BaseTest4 {
  @Before
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, "transaction_test_table", "i int");
    con.setAutoCommit(false);
  }

  @After
  public void tearDown() throws SQLException {
    con.setAutoCommit(true);
    TestUtil.dropTable(con, "transaction_test_table");
    super.tearDown();
  }

  private void setupTransaction() throws SQLException {
    Statement st = con.createStatement();
    st.execute("insert into transaction_test_table(i) values(42)");
    try {
      st.execute("invalid sql to abort transaction");
    } catch (SQLException ignored) {
    }
    TestUtil.closeQuietly(st);
  }

  private void assertFails(Callable<Void> action) throws Exception {
    String msg = "commit should fail since the transaction is in invalid state";
    try {
      action.call();
      Assert.fail(msg);
    } catch (SQLException e) {
      try {
        Assert.assertEquals(
            msg,
            PSQLState.IN_FAILED_SQL_TRANSACTION.getState(),
            e.getSQLState()
        );
      } catch (AssertionError t) {
        t.initCause(e);
        throw t;
      }
    }
  }

  @Test
  public void commitShouldFailIfTransactionWasInvalidState() throws Exception {
    setupTransaction();
    assertFails(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        con.commit();
        return null;
      }
    });
  }

  @Test
  public void execute_commit_statement_ShouldFailIfTransactionWasInvalidState() throws Exception {
    setupTransaction();
    assertFails(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        con.createStatement().execute("commit");
        return null;
      }
    });
  }

  @Test
  public void execute_CoMMiT_statement_ShouldFailIfTransactionWasInvalidState() throws Exception {
    setupTransaction();
    assertFails(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        con.createStatement().execute("CoMMiT");
        return null;
      }
    });
  }

  @Test
  public void execute_CoMMiT_with_comments_statement_ShouldFailIfTransactionWasInvalidState() throws Exception {
    setupTransaction();
    assertFails(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        con.createStatement().execute("/* Dear database, please save the data if possible, thanks */CoMMiT");
        return null;
      }
    });
  }

  @Test
  public void execute_rollback_with_comments_statement_should_not_fail() throws Exception {
    setupTransaction();
    // Note: there's rollback, so exception should not be there
    con.createStatement().execute("/*adsf,commit*/rollBack/*CoMMiT,commit*/");
  }

  @Test
  public void execute_rollback_statement_shouldsucceed() throws Exception {
    setupTransaction();
    con.createStatement().execute("rollback");
  }

  @Test
  public void rolback_shouldsucceed() throws Exception {
    setupTransaction();
    con.rollback();
  }

  @Test
  public void switch_to_autocommit_should_fail_because_it_does_implicit_commit() throws Exception {
    setupTransaction();
    assertFails(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        con.setAutoCommit(true);
        return null;
      }
    });
  }
}
