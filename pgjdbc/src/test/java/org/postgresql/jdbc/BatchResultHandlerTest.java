/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.postgresql.core.Field;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

/**
 * Drives {@link BatchResultHandler}'s callbacks directly, in the order the protocol layer would
 * deliver them.
 *
 * <p>Two things are only reachable this way. {@code secureProgress} runs when the executor forces a
 * Sync mid-batch, which needs about 64 KB of estimated responses to provoke and yields a committed
 * count that depends on those estimates. And every parameterisation of
 * {@code BatchExecuteTest} runs with auto-commit off, so {@code isProgressDurable} is false
 * throughout and the whole secure-progress path is dark there.</p>
 */
class BatchResultHandlerTest {
  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    con.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  /**
   * Fails when a batch entry whose last sub-statement returned rows is committed short of its
   * boundary. Such a sub-statement gets no {@code CommandComplete} of its own, so the entry stays
   * open until the next report; if {@code secureProgress} records the committed count while it is
   * open, the entry is one short and a later failure reports a committed entry as
   * {@code EXECUTE_FAILED}.
   */
  @Test
  void secureProgressCommitsAnEntryEndingInRows() throws Exception {
    Query[] queries = queries("SELECT 1", "SELECT 1/0");
    BatchResultHandler handler = new BatchResultHandler(
        (PgStatement) con.createStatement(), queries, new ParameterList[queries.length], false);

    handler.handleResultRows(queries[0], new Field[0], new ArrayList<>(), null);
    handler.secureProgress();
    handler.handleError(new PSQLException("division by zero", PSQLState.DIVISION_BY_ZERO));

    assertArrayEquals(new long[]{0, Statement.EXECUTE_FAILED}, handler.getLargeUpdateCount(),
        "the entry that returned rows was committed, so only the failing entry may be marked failed");
  }

  private Query[] queries(String... sql) throws SQLException {
    Query[] queries = new Query[sql.length];
    for (int i = 0; i < sql.length; i++) {
      queries[i] = ((PgConnection) con).createQuery(sql[i], false, false).query;
    }
    return queries;
  }
}
