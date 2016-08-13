package org.postgresql.test.jdbc2;

import org.postgresql.PGProperty;
import org.postgresql.core.TransactionState;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
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

  private enum ContinueMode {
    COMMIT,
    IS_VALID,
    SELECT,
  }

  private final AutoSave autoSave;
  private final AutoCommit autoCommit;
  private final FailMode failMode;
  private final ContinueMode continueMode;

  public AutoRollbackTestSuite(AutoSave autoSave, AutoCommit autoCommit,
      FailMode failMode, ContinueMode continueMode) {
    this.autoSave = autoSave;
    this.autoCommit = autoCommit;
    this.failMode = failMode;
    this.continueMode = continueMode;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "rollbacktest", "a int, str text");
    con.setAutoCommit(autoCommit == AutoCommit.YES);
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


  @Parameterized.Parameters(name = "{index}: autorollback(autoSave={0}, autoCommit={1}, failMode={2}, continueMode={3})")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
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
            ids.add(new Object[]{autoSave, autoCommit, failMode, continueMode});
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
        doCommit();
        return;
      case IS_VALID:
        Assert.assertTrue("Connection.isValid should return true unless the connection is closed",
            con.isValid(4));
        return;
      default:
        break;
    }

    try {
      // Try execute server-prepared statement again
      ps.executeQuery().close();
    } catch (SQLException e) {
      if (autoSave != AutoSave.ALWAYS && failMode == FailMode.ALTER) {
        Assert.assertEquals(
            "AutoSave==" + autoSave + " != ALWAYS, thus ALTER TABLE causes SELECT * to fail with "
                + "'cached plan must not change result type', "
                + " error message is " + e.getMessage(),
            PSQLState.NOT_IMPLEMENTED.getState(), e.getSQLState());
        return;
      }
      if (autoSave == AutoSave.NEVER
          || autoSave == AutoSave.CONSERVATIVE && (failMode == FailMode.SELECT
          || failMode == FailMode.INSERT_BATCH)) {
        Assert.assertEquals(
            "AutoSave==NEVER, thus statements should fail with 'current transaction is aborted...', "
                + " error message is " + e.getMessage(),
            PSQLState.IN_FAILED_SQL_TRANSACTION.getState(), e.getSQLState());
        return;
      }
      throw e;
    }

    try {
      assertRows("rollbacktest", 1);
    } catch (SQLException e) {
      if (autoSave == AutoSave.NEVER
          || autoSave == AutoSave.CONSERVATIVE && failMode == FailMode.SELECT) {
        Assert.assertEquals(
            "AutoSave==NEVER, thus statements should fail with 'current transaction is aborted...', "
                + " error message is " + e.getMessage(),
            PSQLState.IN_FAILED_SQL_TRANSACTION.getState(), e.getSQLState());
        return;
      }
      throw e;
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
