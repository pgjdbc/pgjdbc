package org.postgresql.jdbc;

import org.postgresql.core.*;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.*;
import java.util.List;

class CallableBatchResultHandler extends BatchResultHandler
{
    CallableBatchResultHandler(PgStatement statement, Query[] queries, ParameterList[] parameterLists, int[] updateCounts) {
        super(statement, queries, parameterLists, updateCounts, false);
    }

    public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) 
    {
        /* ignore */
    }
}
