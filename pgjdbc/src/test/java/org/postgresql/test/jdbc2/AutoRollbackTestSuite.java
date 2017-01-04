/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ResultHandler;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TransactionState;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Properties;

@RunWith(Parameterized.class)
public class AutoRollbackTestSuite extends BaseTest4 {

  private enum FailMode {
    /**
     * Executes "select 1/0" and causes transaction failure (if autocommit=no).
     * Mitigation: "autosave=always" or "autocommit=true"
     */
    SELECT,
    /**
     * Executes "alter table rollbacktest", thus it breaks a prepared select over that table
     * Mitigation: "autosave in (always, conservative)"
     */
    ALTER,
    /**
     * Executes DEALLOCATE ALL
     * Mitigation:
     *  1) QueryExecutor tracks "DEALLOCATE ALL" responses ({@see org.postgresql.core.QueryExecutor#setFlushCacheOnDeallocate(boolean)}
     *  2) QueryExecutor tracks "prepared statement name is invalid" and unprepares relevant statements ({@link org.postgresql.core.v3.QueryExecutorImpl#processResults(ResultHandler, int)}
     *  3) "autosave in (always, conservative)"
     *  4) Non-transactional cases are healed by retry (when no transaction present, just retry is possible)
     */
    DEALLOCATE,
    /**
     * Executes DISCARD ALL
     * Mitigation: the same as for {@link #DEALLOCATE}
     */
    DISCARD,
    /**
     * Executes "insert ... select 1/0" in a batch statement, thus causing the transaction to fail.
     */
    INSERT_BATCH,
  }

  private final static EnumSet<FailMode> DEALLOCATES =
      EnumSet.of(FailMode.DEALLOCATE, FailMode.DISCARD);

  private final static EnumSet<FailMode> TRANS_KILLERS =
      EnumSet.of(FailMode.SELECT, FailMode.INSERT_BATCH);

  private enum ContinueMode {
    COMMIT,
    IS_VALID,
    SELECT,
  }

  private final AutoSave autoSave;
  private final AutoCommit autoCommit;
  private final FailMode failMode;
  private final ContinueMode continueMode;
  private final boolean flushCacheOnDeallocate;

  public AutoRollbackTestSuite(AutoSave autoSave, AutoCommit autoCommit,
      FailMode failMode, ContinueMode continueMode, boolean flushCacheOnDeallocate) {
    this.autoSave = autoSave;
    this.autoCommit = autoCommit;
    this.failMode = failMode;
    this.continueMode = continueMode;
    this.flushCacheOnDeallocate = flushCacheOnDeallocate;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "rollbacktest", "a int, str text");
    con.setAutoCommit(autoCommit == AutoCommit.YES);
    BaseConnection baseConnection = con.unwrap(BaseConnection.class);
    baseConnection.setFlushCacheOnDeallocate(flushCacheOnDeallocate);
    Assume.assumeTrue("DEALLOCATE ALL requires PostgreSQL 8.3+",
        failMode != FailMode.DEALLOCATE || TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3));
    Assume.assumeTrue("DISCARD ALL requires PostgreSQL 8.3+",
        failMode != FailMode.DISCARD || TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3));
  }

  @Override
  public void tearDown() throws SQLException {
    try {
      con.setAutoCommit(true);
      TestUtil.dropTable(con, "rollbacktest");
    } catch (Exception e) {
      e.printStackTrace();
    }
    super.tearDown();
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.AUTOSAVE.set(props, autoSave.value());
    PGProperty.PREPARE_THRESHOLD.set(props, 1);
  }


  @Parameterized.Parameters(name = "{index}: autorollback(autoSave={0}, autoCommit={1}, failMode={2}, continueMode={3}, flushOnDeallocate={4})")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    boolean[] booleans = new boolean[] {true, false};
    for (AutoSave autoSave : AutoSave.values()) {
      for (AutoCommit autoCommit : AutoCommit.values()) {
        for (FailMode failMode : FailMode.values()) {
          // ERROR: DISCARD ALL cannot run inside a transaction block
          if (failMode == FailMode.DISCARD && autoCommit == AutoCommit.NO) {
            continue;
          }
          for (ContinueMode continueMode : ContinueMode.values()) {
            if (failMode == FailMode.ALTER && continueMode != ContinueMode.SELECT) {
              continue;
            }
            for (boolean flushCacheOnDeallocate : booleans) {
              if (!(flushCacheOnDeallocate || DEALLOCATES.contains(failMode))) {
                continue;
              }

              ids.add(new Object[]{autoSave, autoCommit, failMode, continueMode, flushCacheOnDeallocate});
            }
          }
        }
      }
    }
    return ids;
  }


  @Test
  public void run() throws SQLException {
    if (continueMode == ContinueMode.IS_VALID) {
      // make "isValid" a server-prepared statement
      con.isValid(4);
    } else if (continueMode == ContinueMode.COMMIT) {
      doCommit();
    } else if (continueMode == ContinueMode.SELECT) {
      assertRows("rollbacktest", 0);
    }

    Statement statement = con.createStatement();
    statement.executeUpdate("insert into rollbacktest(a, str) values (0, 'test')");

    PreparedStatement ps = con.prepareStatement("select * from rollbacktest");
    // Server-prepare the statement
    ps.executeQuery().close();

    switch (failMode) {
      case SELECT:
        try {
          statement.execute("select 1/0");
          Assert.fail("select 1/0 should fail");
        } catch (SQLException e) {
          Assert.assertEquals("division by zero expected",
              PSQLState.DIVISION_BY_ZERO.getState(), e.getSQLState());
        }
        break;
      case DEALLOCATE:
        statement.executeUpdate("DEALLOCATE ALL");
        break;
      case DISCARD:
        statement.executeUpdate("DISCARD ALL");
        break;
      case ALTER:
        statement.executeUpdate("alter table rollbacktest add q int");
        break;
      case INSERT_BATCH:
        try {
          statement.addBatch("insert into rollbacktest(a, str) values (1/0, 'test')");
          statement.executeBatch();
          Assert.fail("select 1/0 should fail");
        } catch (SQLException e) {
          Assert.assertEquals("division by zero expected",
              PSQLState.DIVISION_BY_ZERO.getState(), e.getSQLState());
        }
        break;
      default:
        Assert.fail("Fail mode " + failMode + " is not implemented");
    }

    PgConnection pgConnection = con.unwrap(PgConnection.class);
    if (autoSave == AutoSave.ALWAYS) {
      Assert.assertNotEquals("In AutoSave.ALWAYS, transaction should not fail",
          TransactionState.FAILED, pgConnection.getTransactionState());
    }
    if (autoCommit == AutoCommit.NO) {
      Assert.assertNotEquals("AutoCommit == NO, thus transaction should be active (open or failed)",
          TransactionState.IDLE, pgConnection.getTransactionState());
    }
    statement.close();

    switch (continueMode) {
      case COMMIT:
        try {
          doCommit();
          // No assert here: commit should always succeed with exception of well known failure cases in catch
        } catch (SQLException e) {
          if (!flushCacheOnDeallocate && DEALLOCATES.contains(failMode)
              && autoSave == AutoSave.NEVER) {
            Assert.assertEquals(
                "flushCacheOnDeallocate is disabled, thus " + failMode + " should cause 'prepared statement \"...\" does not exist'"
                    + " error message is " + e.getMessage(),
                PSQLState.INVALID_SQL_STATEMENT_NAME.getState(), e.getSQLState());
            return;
          }
          throw e;
        }
        return;
      case IS_VALID:
        if (!flushCacheOnDeallocate && autoSave == AutoSave.NEVER
            && DEALLOCATES.contains(failMode) && autoCommit == AutoCommit.NO
            && con.unwrap(PGConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE) {
          Assert.assertFalse("Connection.isValid should return false since failMode=" + failMode
              + ", flushCacheOnDeallocate=false, and autosave=NEVER",
              con.isValid(4));
        } else {
          Assert.assertTrue("Connection.isValid should return true unless the connection is closed",
              con.isValid(4));
        }
        return;
      default:
        break;
    }

    try {
      // Try execute server-prepared statement again
      ps.executeQuery().close();
      executeSqlSuccess();
    } catch (SQLException e) {
      if (autoSave != AutoSave.ALWAYS && TRANS_KILLERS.contains(failMode) && autoCommit == AutoCommit.NO) {
        Assert.assertEquals(
            "AutoSave==" + autoSave + ", thus statements should fail with 'current transaction is aborted...', "
                + " error message is " + e.getMessage(),
            PSQLState.IN_FAILED_SQL_TRANSACTION.getState(), e.getSQLState());
        return;
      }

      if (autoSave == AutoSave.NEVER && autoCommit == AutoCommit.NO) {
        if (DEALLOCATES.contains(failMode) && !flushCacheOnDeallocate) {
          Assert.assertEquals(
              "flushCacheOnDeallocate is disabled, thus " + failMode + " should cause 'prepared statement \"...\" does not exist'"
                  + " error message is " + e.getMessage(),
              PSQLState.INVALID_SQL_STATEMENT_NAME.getState(), e.getSQLState());
        } else if (failMode == FailMode.ALTER) {
          Assert.assertEquals(
              "AutoSave==NEVER, autocommit=NO, thus ALTER TABLE causes SELECT * to fail with "
                  + "'cached plan must not change result type', "
                  + " error message is " + e.getMessage(),
              PSQLState.NOT_IMPLEMENTED.getState(), e.getSQLState());
        } else {
          throw e;
        }
      } else {
        throw e;
      }
    }


    try {
      assertRows("rollbacktest", 1);
      executeSqlSuccess();
    } catch (SQLException e) {
      if (autoSave == AutoSave.NEVER && autoCommit == AutoCommit.NO) {
        if (DEALLOCATES.contains(failMode) && !flushCacheOnDeallocate
            || failMode == FailMode.ALTER) {
          // The above statement failed with "prepared statement does not exist", thus subsequent one should fail with
          // transaction aborted.
          Assert.assertEquals(
              "AutoSave==NEVER, thus statements should fail with 'current transaction is aborted...', "
                  + " error message is " + e.getMessage(),
              PSQLState.IN_FAILED_SQL_TRANSACTION.getState(), e.getSQLState());
        }
      } else {
        throw e;
      }
    }
  }

  private void executeSqlSuccess() throws SQLException {
    if (autoCommit == AutoCommit.YES) {
      // in autocommit everything should just work
    } else if (TRANS_KILLERS.contains(failMode)) {
      if (autoSave != AutoSave.ALWAYS) {
        Assert.fail(
            "autosave= " + autoSave + " != ALWAYS, thus the transaction should be killed");
      }
    } else if (DEALLOCATES.contains(failMode)) {
      if (autoSave == AutoSave.NEVER && !flushCacheOnDeallocate
          && con.unwrap(PGConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE) {
        Assert.fail("flushCacheOnDeallocate == false, thus DEALLOCATE ALL should kill the transaction");
      }
    } else if (failMode == FailMode.ALTER) {
      if (autoSave == AutoSave.NEVER
          && con.unwrap(PGConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE) {
        Assert.fail("autosave=NEVER, thus the transaction should be killed");
      }
    } else {
      Assert.fail("It is not specified why the test should pass, thus marking a failure");
    }
  }

  private void assertRows(String tableName, int nrows) throws SQLException {
    Statement st = con.createStatement();
    ResultSet rs = st.executeQuery("select count(*) from " + tableName);
    rs.next();
    Assert.assertEquals("Table " + tableName, nrows, rs.getInt(1));
  }

  private void doCommit() throws SQLException {
    // Such a dance is required since "commit" checks "current transaction state",
    // so we need some pending changes, so "commit" query would be sent to the database
    if (con.getAutoCommit()) {
      con.setAutoCommit(false);
      Statement st = con.createStatement();
      st.executeUpdate(
          "insert into rollbacktest(a, str) values (42, '" + System.currentTimeMillis() + "')");
      st.close();
    }
    con.commit();
    con.setAutoCommit(autoCommit == AutoCommit.YES);
  }
}
