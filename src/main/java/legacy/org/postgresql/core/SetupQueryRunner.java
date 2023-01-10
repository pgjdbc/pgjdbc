/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.core;

import java.util.Vector;
import java.sql.SQLWarning;
import java.sql.SQLException;

import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PSQLState;
import legacy.org.postgresql.util.PSQLException;

/**
 * Poor man's Statement & ResultSet, used for initial queries while we're
 * still initializing the system.
 */
public class SetupQueryRunner {

    private static class SimpleResultHandler implements ResultHandler {
        private SQLException error;
        private Vector tuples;
        private final ProtocolConnection protoConnection;

        SimpleResultHandler(ProtocolConnection protoConnection) {
            this.protoConnection = protoConnection;
        }

        Vector getResults() {
            return tuples;
        }

        public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
            this.tuples = tuples;
        }

        public void handleCommandStatus(String status, int updateCount, long insertOID) {
        }

        public void handleWarning(SQLWarning warning) {
            // We ignore warnings.  We assume we know what we're
            // doing in the setup queries.
        }

        public void handleError(SQLException newError) {
            if (error == null)
                error = newError;
            else
                error.setNextException(newError);
        }

        public void handleCompletion() throws SQLException {
            if (error != null)
                throw error;
        }
    }

    public static byte[][] run(ProtocolConnection protoConnection, String queryString, boolean wantResults) throws SQLException {
        QueryExecutor executor = protoConnection.getQueryExecutor();
        Query query = executor.createSimpleQuery(queryString);
        SimpleResultHandler handler = new SimpleResultHandler(protoConnection);

        int flags = QueryExecutor.QUERY_ONESHOT | QueryExecutor.QUERY_SUPPRESS_BEGIN;
        if (!wantResults)
            flags |= QueryExecutor.QUERY_NO_RESULTS | QueryExecutor.QUERY_NO_METADATA;

        try
        {
            executor.execute(query, null, handler, 0, 0, flags);
        }
        finally
        {
            query.close();
        }

        if (!wantResults)
            return null;

        Vector tuples = handler.getResults();
        if (tuples == null || tuples.size() != 1)
            throw new PSQLException(GT.tr("An unexpected result was returned by a query."), PSQLState.CONNECTION_UNABLE_TO_CONNECT);

        return (byte[][]) tuples.elementAt(0);
    }

}
