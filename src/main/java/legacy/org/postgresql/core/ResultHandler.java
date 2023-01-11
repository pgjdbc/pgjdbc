/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.core;

import java.sql.*;
import java.util.Vector;

/**
 * Callback interface for passing query results from the protocol-specific
 * layer to the protocol-independent JDBC implementation code.
 *<p>
 * In general, a single query execution will consist of a number of calls
 * to handleResultRows, handleCommandStatus, handleWarning, and handleError,
 * followed by a single call to handleCompletion when query execution is
 * complete. If the caller wants to throw SQLException, this can be done 
 * in handleCompletion.
 *<p>
 * Each executed query ends with a call to handleResultRows,
 * handleCommandStatus, or handleError. If an error occurs, subsequent queries
 * won't generate callbacks.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
public interface ResultHandler {
    /**
     * Called when result rows are received from a query.
     *
     * @param fromQuery the underlying query that generated these results;
     *   this may not be very specific (e.g. it may be a query that includes
     *   multiple statements).
     * @param fields column metadata for the resultset; might be
     *   <code>null</code> if Query.QUERY_NO_METADATA was specified.
     * @param tuples the actual data
     * @param cursor a cursor to use to fetch additional data;
     *   <code>null</code> if no further results are present.
     */
    void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor);

    /**
     * Called when a query that did not return a resultset completes.
     *
     * @param status the command status string (e.g. "SELECT") returned by
     *   the backend
     * @param updateCount the number of rows affected by an INSERT, UPDATE,
     *   DELETE, FETCH, or MOVE command; -1 if not available.
     * @param insertOID for a single-row INSERT query, the OID of the newly
     *   inserted row; 0 if not available.
     */
    void handleCommandStatus(String status, int updateCount, long insertOID);

    /**
     * Called when a warning is emitted.
     *
     * @param warning the warning that occured.
     */
    void handleWarning(SQLWarning warning);

    /**
     * Called when an error occurs. Subsequent queries are abandoned;
     * in general the only calls between a handleError call and 
     * a subsequent handleCompletion call are handleError or handleWarning.
     *
     * @param error the error that occurred
     */
    void handleError(SQLException error);

    /**
     * Called before a QueryExecutor method returns. This method
     * may throw a SQLException if desired; if it does, the QueryExecutor
     * method will propagate that exception to the original caller.
     *
     * @throws SQLException if the handler wishes the original method to
     *   throw an exception.
     */
    void handleCompletion() throws SQLException;
}
