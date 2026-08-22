/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Field;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandlerBase;
import org.postgresql.core.SqlCommand;
import org.postgresql.core.SqlCommandType;
import org.postgresql.core.TransactionState;
import org.postgresql.core.Tuple;
import org.postgresql.core.v3.BatchedQuery;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.BatchUpdateException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Internal class, it is not a part of public API.
 */
public class BatchResultHandler extends ResultHandlerBase {

  private final PgStatement pgStatement;
  private int resultIndex;

  private final Query[] queries;
  /** Sub-statements each batch entry reports, parallel to {@link #queries}. */
  private final int[] subStatementCounts;
  private final long[] longUpdateCounts;
  private final @Nullable ParameterList @Nullable [] parameterLists;
  private final boolean expectGeneratedKeys;
  private @Nullable PgResultSet generatedKeys;
  private int committedRows; // 0 means no rows committed. 1 means row 0 was committed, and so on
  private final @Nullable List<List<Tuple>> allGeneratedRows;
  private @Nullable List<Tuple> latestGeneratedRows;
  private @Nullable PgResultSet latestGeneratedKeysRs;
  /** Sub-statements of the entry at {@link #resultIndex} that have not reported yet. */
  private int pendingSubResults;
  /** Whether any sub-statement of the entry at {@link #resultIndex} changed rows. */
  private boolean entryAffectedRows;
  /** A sub-statement returned rows and its {@code CommandComplete} may still follow. */
  private boolean rowsAwaitingStatus;
  /** Whether that sub-statement can have changed rows, so a missing status leaves it unknown. */
  private boolean rowsAwaitingStatusMayHaveChangedRows;
  /** Whether any sub-statement of the entry at {@link #resultIndex} went unaccounted for. */
  private boolean entryCountUnknown;

  BatchResultHandler(PgStatement pgStatement, Query[] queries,
      @Nullable ParameterList @Nullable [] parameterLists,
      boolean expectGeneratedKeys) {
    this.pgStatement = pgStatement;
    this.queries = queries;
    int[] counts = pgStatement.getBatchSubStatementCounts();
    this.subStatementCounts = counts != null ? counts : subStatementCountsOf(queries);
    this.parameterLists = parameterLists;
    this.longUpdateCounts = new long[queries.length];
    this.expectGeneratedKeys = expectGeneratedKeys;
    this.allGeneratedRows = !expectGeneratedKeys ? null : new ArrayList<List<Tuple>>();
  }

  /**
   * Counts the sub-statements of each entry the way the extended protocol splits them.
   *
   * <p>An entry that the extended protocol never split — {@code Statement.addBatch(String)} below
   * {@code preferQueryMode=EXTENDED} — is counted by {@link PgStatement} at {@code addBatch} time
   * instead, and reaches this class through
   * {@link PgStatement#getBatchSubStatementCounts()}.</p>
   */
  private static int[] subStatementCountsOf(Query[] queries) {
    int[] counts = new int[queries.length];
    for (int i = 0; i < queries.length; i++) {
      Query[] subqueries = queries[i].getSubqueries();
      counts[i] = subqueries == null ? 1 : subqueries.length;
    }
    return counts;
  }

  /**
   * Records one sub-statement's outcome and closes the batch entry once its last sub-statement has
   * reported.
   *
   * <p>JDBC gives an entry one update count, so an entry of several statements cannot report a
   * per-statement figure. Summing them would invent a number no statement produced. What survives
   * is the distinction the caller can act on: an entry of several statements reports {@code 0} only
   * when every statement of it reported a row count of its own and every one of those counts was
   * zero, and {@link Statement#SUCCESS_NO_INFO} otherwise — including when a statement's outcome
   * never reached the driver, which is what {@code SUCCESS_NO_INFO} says. An entry of one statement
   * keeps reporting that statement's own count, which for {@code RETURNING} under a no-results
   * batch has always been {@code 0}.</p>
   *
   * @param updateCount rows the sub-statement reported
   * @param countUnknown whether the sub-statement's outcome never reached the driver
   */
  private void completeSubResult(long updateCount, boolean countUnknown) {
    if (resultIndex >= queries.length) {
      handleError(new PSQLException(GT.tr("Too many update results were returned."),
          PSQLState.TOO_MANY_RESULTS));
      return;
    }
    if (pendingSubResults == 0) {
      pendingSubResults = subStatementCounts[resultIndex];
      entryAffectedRows = false;
      entryCountUnknown = false;
    }
    entryAffectedRows |= updateCount != 0;
    entryCountUnknown |= countUnknown;
    if (--pendingSubResults > 0) {
      return;
    }
    longUpdateCounts[resultIndex] = subStatementCounts[resultIndex] == 1 ? updateCount
        : entryAffectedRows || entryCountUnknown ? Statement.SUCCESS_NO_INFO : 0;
    resultIndex++;
  }

  @Override
  public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
      @Nullable ResultCursor cursor) {
    // If SELECT, then handleCommandStatus call would just be missing, so the sub-statement that
    // preceded this one is only known to be over now.
    if (rowsAwaitingStatus) {
      completeSubResultAwaitingStatus();
    }
    rowsAwaitingStatus = true;
    // A SELECT changes nothing, so a missing status still means zero. Anything else that returns
    // rows may have changed some, and on the no-results path its count never reaches the driver:
    // the rows are discarded, so tuples is empty whether or not it changed anything.
    SqlCommand command = fromQuery.getSqlCommand();
    rowsAwaitingStatusMayHaveChangedRows =
        command == null || command.getType() != SqlCommandType.SELECT;
    if (!expectGeneratedKeys) {
      // No rows expected -> just ignore rows
      return;
    }
    if (generatedKeys == null) {
      try {
        // If SELECT, the resulting ResultSet is not valid
        // Thus it is up to handleCommandStatus to decide if resultSet is good enough
        latestGeneratedKeysRs = (PgResultSet) pgStatement.createResultSet(fromQuery, fields,
            new ArrayList<>(), cursor);
      } catch (SQLException e) {
        handleError(e);
      }
    }
    latestGeneratedRows = tuples;
  }

  @Override
  public void handleCommandStatus(String status, long updateCount, long insertOID) {
    List<Tuple> latestGeneratedRows = this.latestGeneratedRows;
    if (latestGeneratedRows != null) {
      // If exception thrown, no need to collect generated keys
      // Note: some generated keys might be secured in generatedKeys
      if (updateCount > 0 && (getException() == null || isProgressDurable())) {
        List<List<Tuple>> allGeneratedRows = castNonNull(this.allGeneratedRows, "allGeneratedRows");
        allGeneratedRows.add(latestGeneratedRows);
        if (generatedKeys == null) {
          generatedKeys = latestGeneratedKeysRs;
        }
      }
      this.latestGeneratedRows = null;
      // This status belongs to the sub-statement that just returned rows, so it decides whether
      // that sub-statement changed anything. Only a statement with RETURNING gets here — the rows
      // are collected as generated keys — so the tag is INSERT, UPDATE, DELETE or MERGE and the
      // count is rows changed.
      rowsAwaitingStatus = false;
      latestGeneratedKeysRs = null;
      completeSubResult(updateCount, false);
      return;
    } else if (rowsAwaitingStatus) {
      // The sub-statement that returned rows gets no status of its own, so it ends here. This
      // status belongs to the sub-statement after it.
      completeSubResultAwaitingStatus();
    }

    latestGeneratedKeysRs = null;
    // No rows came back, so a retrieval tag is not a read at all: CREATE TABLE AS, SELECT INTO and
    // CREATE MATERIALIZED VIEW report the rows they wrote into the new relation under SELECT n.
    // The number is real but it is not this entry's row count, and the relation now exists.
    completeSubResult(updateCount, !reportsRowCount(status) || isRetrieval(status));
  }

  /**
   * Returns whether a command tag is one the server uses for reading rows. Only the caller that saw
   * no rows asks this: {@code CREATE TABLE AS} and {@code SELECT INTO} report the rows they wrote
   * into a new relation under the same tag a plain {@code SELECT} uses, so a retrieval tag with no
   * rows behind it is a statement whose real outcome this entry cannot state.
   *
   * @param status the command tag the server sent
   */
  private static boolean isRetrieval(String status) {
    return status.startsWith("SELECT") || status.startsWith("FETCH") || status.startsWith("MOVE");
  }

  /**
   * Returns whether a command tag carries a row count. The server appends one only to commands that
   * have it, so {@code TRUNCATE TABLE} or {@code CREATE INDEX} arrive as a count of zero that says
   * nothing about what they did. {@code EMPTY} is the driver's own tag for an empty statement,
   * which changed nothing by definition.
   *
   * @param status the command tag the server sent
   */
  private static boolean reportsRowCount(String status) {
    return "EMPTY".equals(status)
        || !status.isEmpty() && Character.isDigit(status.charAt(status.length() - 1));
  }

  /**
   * Closes a sub-statement that returned rows and never got a {@code CommandComplete}. A
   * {@code SELECT} is known to have changed nothing; for DML with {@code RETURNING} the count never
   * arrived, so the entry can no longer report a figure of its own.
   */
  private void completeSubResultAwaitingStatus() {
    rowsAwaitingStatus = false;
    completeSubResult(0, rowsAwaitingStatusMayHaveChangedRows);
  }

  /**
   * Returns whether progress made so far can be secured, that is, recorded as committed so a later
   * error reports the earlier rows as succeeded rather than {@code EXECUTE_FAILED}.
   *
   * <p>Progress is durable only when each statement commits on its own. An XA branch keeps
   * {@code autoCommit=true} while a server-side transaction is open (see
   * <a href="https://github.com/pgjdbc/pgjdbc/issues/4309">issue #4309</a>), so the flag alone would
   * secure rows that the transaction manager can still roll back. Requiring
   * {@link TransactionState#IDLE} also covers a manual {@code BEGIN} issued under auto-commit.</p>
   *
   * @return true when the connection is in auto-commit mode with no transaction open
   */
  private boolean isProgressDurable() {
    try {
      BaseConnection connection = pgStatement.getPGConnection();
      return connection.getAutoCommit()
          && connection.getTransactionState() == TransactionState.IDLE;
    } catch (SQLException e) {
      // getAutoCommit() throws when the connection is already closed (for instance, the server
      // terminated the connection in the middle of a batch). There is nothing to secure in that
      // case, so treat progress as not durable rather than failing with an assertion.
      return false;
    }
  }

  @Override
  public void secureProgress() {
    if (!isProgressDurable()) {
      return;
    }
    // A sub-statement that returned rows has finished, and so has the entry it ends. Leaving it
    // open would commit one entry too few, and a later failure would report that committed entry as
    // EXECUTE_FAILED. This runs after the protocol layer drained to ReadyForQuery, so no status is
    // still in flight — the same reason handleError and handleCompletion close unconditionally.
    if (rowsAwaitingStatus) {
      completeSubResultAwaitingStatus();
    }
    committedRows = resultIndex;
    updateGeneratedKeys();
  }

  private void updateGeneratedKeys() {
    List<List<Tuple>> allGeneratedRows = this.allGeneratedRows;
    if (allGeneratedRows == null || allGeneratedRows.isEmpty()) {
      return;
    }
    PgResultSet generatedKeys = castNonNull(this.generatedKeys, "generatedKeys");
    for (List<Tuple> rows : allGeneratedRows) {
      generatedKeys.addRows(rows);
    }
    allGeneratedRows.clear();
  }

  @Override
  public void handleWarning(SQLWarning warning) {
    pgStatement.addWarning(warning);
  }

  @Override
  public void handleError(SQLException newError) {
    // A sub-statement that returned rows and never got a status still ends the entry it belongs to.
    // This has to happen before resultIndex is read below, otherwise the error is charged to the
    // finished entry and the exception quotes its SQL.
    if (rowsAwaitingStatus) {
      completeSubResultAwaitingStatus();
    }
    if (getException() == null) {
      Arrays.fill(longUpdateCounts, committedRows, longUpdateCounts.length, Statement.EXECUTE_FAILED);
      if (allGeneratedRows != null) {
        allGeneratedRows.clear();
      }

      String queryString = "<unknown>";
      if (pgStatement.getPGConnection().getLogServerErrorDetail()) {
        if (resultIndex < queries.length) {
          queryString = queries[resultIndex].toString(
             parameterLists == null ? null : parameterLists[resultIndex]);
        }
      }

      BatchUpdateException batchException;
      batchException = new BatchUpdateException(
          GT.tr("Batch entry {0} {1} was aborted: {2}  Call getNextException to see other errors in the batch.",
              resultIndex, queryString, newError.getMessage()),
          newError.getSQLState(), 0, uncompressLongUpdateCount(), newError);

      super.handleError(batchException);
    }
    // The failing entry is done, however many of its statements never reported.
    pendingSubResults = 0;
    resultIndex++;

    super.handleError(newError);
  }

  @Override
  public void handleCompletion() throws SQLException {
    if (rowsAwaitingStatus) {
      completeSubResultAwaitingStatus();
    }
    updateGeneratedKeys();
    SQLException batchException = getException();
    if (batchException != null) {
      if (isProgressDurable()) {
        // Re-create batch exception since rows after exception might indeed succeed.
        BatchUpdateException newException;
        newException = new BatchUpdateException(
            batchException.getMessage(),
            batchException.getSQLState(), 0,
            uncompressLongUpdateCount(),
            batchException.getCause()
        );

        SQLException next = batchException.getNextException();
        if (next != null) {
          newException.setNextException(next);
        }
        batchException = newException;
      }
      throw batchException;
    }
  }

  public @Nullable ResultSet getGeneratedKeys() {
    return generatedKeys;
  }

  private int[] uncompressUpdateCount() {
    long[] original = uncompressLongUpdateCount();
    int[] copy = new int[original.length];
    for (int i = 0; i < original.length; i++) {
      copy[i] = original[i] > Integer.MAX_VALUE ? Statement.SUCCESS_NO_INFO : (int) original[i];
    }
    return copy;
  }

  public int[] getUpdateCount() {
    return uncompressUpdateCount();
  }

  private long[] uncompressLongUpdateCount() {
    if (!(queries[0] instanceof BatchedQuery)) {
      return longUpdateCounts;
    }
    int totalRows = 0;
    boolean hasRewrites = false;
    for (Query query : queries) {
      int batchSize = query.getBatchSize();
      totalRows += batchSize;
      hasRewrites |= batchSize > 1;
    }
    if (!hasRewrites) {
      return longUpdateCounts;
    }

    /* In this situation there is a batch that has been rewritten. Substitute
     * the running total returned by the database with a status code to
     * indicate successful completion for each row the driver client added
     * to the batch.
     */
    long[] newUpdateCounts = new long[totalRows];
    int offset = 0;
    for (int i = 0; i < queries.length; i++) {
      Query query = queries[i];
      int batchSize = query.getBatchSize();
      long superBatchResult = longUpdateCounts[i];
      if (batchSize == 1) {
        newUpdateCounts[offset++] = superBatchResult;
        continue;
      }
      if (superBatchResult > 0) {
        // If some rows inserted, we do not really know how did they spread over individual
        // statements
        superBatchResult = Statement.SUCCESS_NO_INFO;
      }
      Arrays.fill(newUpdateCounts, offset, offset + batchSize, superBatchResult);
      offset += batchSize;
    }
    return newUpdateCounts;
  }

  public long[] getLargeUpdateCount() {
    return uncompressLongUpdateCount();
  }

}
