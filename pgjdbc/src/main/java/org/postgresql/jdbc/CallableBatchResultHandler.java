package org.postgresql.jdbc;

import org.postgresql.core.Field;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;

import java.util.List;

class CallableBatchResultHandler extends BatchResultHandler {
  CallableBatchResultHandler(PgStatement statement, Query[] queries, ParameterList[] parameterLists,
      int[] updateCounts) {
    super(statement, queries, parameterLists, updateCounts, false);
  }

  public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
        /* ignore */
  }
}
