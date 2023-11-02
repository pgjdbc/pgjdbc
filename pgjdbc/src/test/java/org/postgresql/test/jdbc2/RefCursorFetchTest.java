/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

@RunWith(Parameterized.class)
public class RefCursorFetchTest extends BaseTest4 {
  private final int numRows;
  private final @Nullable Integer defaultFetchSize;
  private final @Nullable Integer resultSetFetchSize;
  private final AutoCommit autoCommit;
  private final boolean commitAfterExecute;

  public RefCursorFetchTest(BinaryMode binaryMode, int numRows,
      @Nullable Integer defaultFetchSize,
      @Nullable Integer resultSetFetchSize,
      AutoCommit autoCommit, boolean commitAfterExecute) {
    this.numRows = numRows;
    this.defaultFetchSize = defaultFetchSize;
    this.resultSetFetchSize = resultSetFetchSize;
    this.autoCommit = autoCommit;
    this.commitAfterExecute = commitAfterExecute;
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "binary = {0}, numRows = {1}, defaultFetchSize = {2}, resultSetFetchSize = {3}, autoCommit = {4}, commitAfterExecute = {5}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      for (int numRows : new int[]{0, 10, 101}) {
        for (Integer defaultFetchSize : new Integer[]{null, 9, 50}) {
          for (AutoCommit autoCommit : AutoCommit.values()) {
            for (boolean commitAfterExecute : new boolean[]{true, false}) {
              for (Integer resultSetFetchSize : new Integer[]{null, 9, 50}) {
                ids.add(new Object[]{binaryMode, numRows, defaultFetchSize, resultSetFetchSize, autoCommit, commitAfterExecute});
              }
            }
          }
        }
      }
    }
    return ids;
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    if (defaultFetchSize != null) {
      PGProperty.DEFAULT_ROW_FETCH_SIZE.set(props, defaultFetchSize);
    }
  }

  @BeforeClass
  public static void beforeClass() throws Exception {
    TestUtil.assumeHaveMinimumServerVersion(ServerVersion.v9_0);
    try (Connection con = TestUtil.openDB();) {
      assumeCallableStatementsSupported(con);
      TestUtil.createTable(con, "test_blob", "content bytea");
      TestUtil.execute(con, "");
      TestUtil.execute(con, "--create function to read data\n"
          + "CREATE OR REPLACE FUNCTION test_blob(p_cur OUT REFCURSOR, p_limit int4) AS $body$\n"
          + "BEGIN\n"
          + "OPEN p_cur FOR SELECT content FROM test_blob LIMIT p_limit;\n"
          + "END;\n"
          + "$body$ LANGUAGE plpgsql STABLE");

      TestUtil.execute(con,"--generate 101 rows with 4096 bytes:\n"
          + "insert into test_blob\n"
          + "select(select decode(string_agg(lpad(to_hex(width_bucket(random(), 0, 1, 256) - 1), 2, '0'), ''), 'hex')"
          + " FROM generate_series(1, 4096))\n"
          + "from generate_series (1, 200)");
    }
  }

  @AfterClass
  public static void afterClass() throws Exception {
    try (Connection con = TestUtil.openDB();) {
      TestUtil.dropTable(con, "test_blob");
      TestUtil.dropFunction(con,"test_blob", "REFCURSOR, int4");
    }
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    con.setAutoCommit(autoCommit == AutoCommit.YES);
  }

  @Test
  public void fetchAllRows() throws SQLException {
    int cnt = 0;
    try (CallableStatement call = con.prepareCall("{? = call test_blob(?)}")) {
      con.setAutoCommit(false); // ref cursors only work if auto commit is off
      call.registerOutParameter(1, Types.REF_CURSOR);
      call.setInt(2, numRows);
      call.execute();
      if (commitAfterExecute) {
        if (autoCommit == AutoCommit.NO) {
          con.commit();
        } else {
          con.setAutoCommit(false);
          con.setAutoCommit(true);
        }
      }
      try (ResultSet rs = (ResultSet) call.getObject(1)) {
        if (resultSetFetchSize != null) {
          rs.setFetchSize(resultSetFetchSize);
        }
        while (rs.next()) {
          cnt++;
        }
        assertEquals("number of rows from test_blob(...) call", numRows, cnt);
      } catch (SQLException e) {
        if (commitAfterExecute && "34000".equals(e.getSQLState())) {
          // Transaction commit closes refcursor, so the fetch call is expected to fail
          // File: postgres.c, Routine: exec_execute_message, Line: 2070
          //   Server SQLState: 34000
          int expectedRows =
              defaultFetchSize != null && defaultFetchSize != 0 ? Math.min(defaultFetchSize, numRows) : numRows;
          assertEquals(
              "The transaction was committed before processing the results,"
                  + " so expecting ResultSet to buffer fetchSize=" + defaultFetchSize + " rows out of "
                  + numRows,
              expectedRows,
              cnt
          );
          return;
        }
        throw e;
      }
    }
  }
}
