package org.postgresql.jdbc;

import org.postgresql.core.Field;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;

import java.util.List;

class CallableBatchResultHandler extends BatchResultHandler {
  CallableBatchResultHandler(PgStatement statement, Query[] queries, ParameterList[] parameterLists) {
    super(statement, queries, parameterLists, false);
  }

  public void handleResultRows(Query fromQuery, Field[] fields, List<byte[][]> tuples, ResultCursor cursor) {
    /* ignore */
  }
}
