package org.postgresql.jdbc;

import org.postgresql.core.Field;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandler;
import org.postgresql.core.v3.BatchedQueryDecorator;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

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
public class BatchResultHandler implements ResultHandler {
  private PgStatement pgStatement;
  private BatchUpdateException batchException = null;
  private int resultIndex = 0;

  private final Query[] queries;
  private final int[] updateCounts;
  private final ParameterList[] parameterLists;
  private final boolean expectGeneratedKeys;
  private PgResultSet generatedKeys;
  private int committedRows; // 0 means no rows committed. 1 means row 0 was committed, and so on
  private List<List<byte[][]>> allGeneratedRows;
  private List<byte[][]> latestGeneratedRows;
  private PgResultSet latestGeneratedKeysRs;

  BatchResultHandler(PgStatement pgStatement, Query[] queries, ParameterList[] parameterLists,
      boolean expectGeneratedKeys) {
    this.pgStatement = pgStatement;
    this.queries = queries;
    this.parameterLists = parameterLists;
    this.updateCounts = new int[queries.length];
    this.expectGeneratedKeys = expectGeneratedKeys;
    this.allGeneratedRows = !expectGeneratedKeys ? null : new ArrayList<List<byte[][]>>();
  }

  public void handleResultRows(Query fromQuery, Field[] fields, List<byte[][]> tuples,
      ResultCursor cursor) {
    // If SELECT, then handleCommandStatus call would just be missing
    resultIndex++;
    if (!expectGeneratedKeys) {
      // No rows expected -> just ignore rows
      return;
    }
    if (generatedKeys == null) {
      try {
        // If SELECT, the resulting ResultSet is not valid
        // Thus it is up to handleCommandStatus to decide if resultSet is good enough
        latestGeneratedKeysRs =
            (PgResultSet) pgStatement.createResultSet(fromQuery, fields,
                new ArrayList<byte[][]>(), cursor);
      } catch (SQLException e) {
        handleError(e);
      }
    }
    latestGeneratedRows = tuples;
  }

  public void handleCommandStatus(String status, int updateCount, long insertOID) {
    if (latestGeneratedRows != null) {
      // We have DML. Decrease resultIndex that was just increased in handleResultRows
      resultIndex--;
      // If exception thrown, no need to collect generated keys
      // Note: some generated keys might be secured in generatedKeys
      if (updateCount > 0 && batchException == null) {
        allGeneratedRows.add(latestGeneratedRows);
        if (generatedKeys == null) {
          generatedKeys = latestGeneratedKeysRs;
        }
      }
      latestGeneratedRows = null;
    }

    if (resultIndex >= queries.length) {
      handleError(new PSQLException(GT.tr("Too many update results were returned."),
          PSQLState.TOO_MANY_RESULTS));
      return;
    }
    latestGeneratedKeysRs = null;

    updateCounts[resultIndex++] = updateCount;
  }

  public void secureProgress() {
    try {
      if (batchException == null && pgStatement.getConnection().getAutoCommit()) {
        committedRows = resultIndex;
        updateGeneratedKeys();
      }
    } catch (SQLException e) {
        /* Should not get here */
    }
  }

  private void updateGeneratedKeys() {
    if (allGeneratedRows == null || allGeneratedRows.isEmpty()) {
      return;
    }
    for (List<byte[][]> rows : allGeneratedRows) {
      generatedKeys.addRows(rows);
    }
    allGeneratedRows.clear();
  }

  public void handleWarning(SQLWarning warning) {
    pgStatement.addWarning(warning);
  }

  public void handleError(SQLException newError) {
    if (batchException == null) {
      Arrays.fill(updateCounts, committedRows, updateCounts.length, Statement.EXECUTE_FAILED);
      if (allGeneratedRows != null) {
        allGeneratedRows.clear();
      }

      String queryString = "<unknown>";
      if (resultIndex < queries.length) {
        queryString = queries[resultIndex].toString(parameterLists[resultIndex]);
      }

      batchException = new BatchUpdateException(
          GT.tr("Batch entry {0} {1} was aborted.  Call getNextException to see the cause.",
              new Object[]{resultIndex, queryString}),
          newError.getSQLState(), uncompressUpdateCount());
    }

    batchException.setNextException(newError);
  }

  public void handleCompletion() throws SQLException {
    if (batchException != null) {
      throw batchException;
    }
    updateGeneratedKeys();
  }

  public ResultSet getGeneratedKeys() {
    return generatedKeys;
  }

  private int[] uncompressUpdateCount() {
    if (!queries[0].isStatementReWritableInsert()) {
      return updateCounts;
    }
    int totalRows = 0;
    boolean hasRewrites = false;
    for (Query query : queries) {
      BatchedQueryDecorator bqd = (BatchedQueryDecorator) query;
      int batchSize = bqd.getBatchSize();
      totalRows += batchSize;
      hasRewrites |= batchSize > 1;
    }
    if (!hasRewrites) {
      return updateCounts;
    }

    /* In this situation there is a batch that has been rewritten. Substitute
     * the running total returned by the database with a status code to
     * indicate successful completion for each row the driver client added
     * to the batch.
     */
    int[] newUpdateCounts = new int[totalRows];
    int offset = 0;
    // In case of rewrite, we split into various queries
    // It would be weird to have "no info" for some and 1 for another.
    boolean isMultiValueBatchRewrite = ((BatchedQueryDecorator) queries[0]).getBatchSize() > 1;
    for (int i = 0; i < queries.length; i++) {
      BatchedQueryDecorator bqd = (BatchedQueryDecorator) queries[i];
      int batchSize = bqd.getBatchSize();
      int superBatchResult = updateCounts[i];
      if (!isMultiValueBatchRewrite) {
        newUpdateCounts[offset++] = superBatchResult;
        continue;
      }
      if (superBatchResult >= 0) {
        // If some rows inserted, we do not really know how did they spread over individual
        // statements
        superBatchResult = Statement.SUCCESS_NO_INFO;
      }
      Arrays.fill(newUpdateCounts, offset, offset + batchSize, superBatchResult);
      offset += batchSize;
      // The same instance can be used several times in a row, so we reset only if next is not same.
      if (i == queries.length - 1 || queries[i + 1] != bqd) {
        bqd.reset();
      }
    }
    return newUpdateCounts;
  }

  public int[] getUpdateCount() {
    return uncompressUpdateCount();
  }
}
