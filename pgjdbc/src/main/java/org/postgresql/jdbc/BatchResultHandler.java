package org.postgresql.jdbc;

import org.postgresql.core.Field;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandler;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.BatchUpdateException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.List;

class BatchResultHandler implements ResultHandler {
  private PgStatement pgStatement;
  private BatchUpdateException batchException = null;
  private int resultIndex = 0;

  private final Query[] queries;
  private final ParameterList[] parameterLists;
  private final int[] updateCounts;
  private final boolean expectGeneratedKeys;
  private ResultSet generatedKeys;

  BatchResultHandler(PgStatement pgStatement, Query[] queries, ParameterList[] parameterLists,
      int[] updateCounts, boolean expectGeneratedKeys) {
    this.pgStatement = pgStatement;
    this.queries = queries;
    this.parameterLists = parameterLists;
    this.updateCounts = updateCounts;
    this.expectGeneratedKeys = expectGeneratedKeys;
  }

  public void handleResultRows(Query fromQuery, Field[] fields, List<byte[][]> tuples,
      ResultCursor cursor) {
    if (!expectGeneratedKeys) {
      handleError(new PSQLException(GT.tr("A result was returned when none was expected."),
          PSQLState.TOO_MANY_RESULTS));
    } else {
      if (generatedKeys == null) {
        try {
          generatedKeys = pgStatement.createResultSet(fromQuery, fields, tuples, cursor);
        } catch (SQLException e) {
          handleError(e);

        }
      } else {
        ((PgResultSet) generatedKeys).addRows(tuples);
      }
    }
  }

  public void handleCommandStatus(String status, int updateCount, long insertOID) {
    if (resultIndex >= updateCounts.length) {
      handleError(new PSQLException(GT.tr("Too many update results were returned."),
          PSQLState.TOO_MANY_RESULTS));
      return;
    }

    updateCounts[resultIndex++] = updateCount;
  }

  public void handleWarning(SQLWarning warning) {
    pgStatement.addWarning(warning);
  }

  public void handleError(SQLException newError) {
    if (batchException == null) {
      int[] successCounts;

      if (resultIndex >= updateCounts.length) {
        successCounts = updateCounts;
      } else {
        successCounts = new int[resultIndex];
        System.arraycopy(updateCounts, 0, successCounts, 0, resultIndex);
      }

      String queryString = "<unknown>";
      if (resultIndex < queries.length) {
        queryString = queries[resultIndex].toString(parameterLists[resultIndex]);
      }

      batchException = new BatchUpdateException(
          GT.tr("Batch entry {0} {1} was aborted.  Call getNextException to see the cause.",
              new Object[]{resultIndex,
                  queryString}),
          newError.getSQLState(),
          successCounts);
    }

    batchException.setNextException(newError);
  }

  public void handleCompletion() throws SQLException {
    if (batchException != null) {
      throw batchException;
    }
  }

  public ResultSet getGeneratedKeys() {
    return generatedKeys;
  }
}
