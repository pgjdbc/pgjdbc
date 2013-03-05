/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.sql.SQLException;

import org.postgresql.copy.CopyOperation;

/**
 * Abstracts the protocol-specific details of executing a query.
 *<p>
 * Every connection has a single QueryExecutor implementation associated with it.
 * This object provides:
 * 
 * <ul>
 *   <li> factory methods for Query objects ({@link #createSimpleQuery}
 *        and {@link #createParameterizedQuery})
 *   <li> execution methods for created Query objects ({@link #execute(Query,ParameterList,ResultHandler,int,int,int)}
 *        for single queries and {@link #execute(Query[],ParameterList[],ResultHandler,int,int,int)} for batches of
 *        queries)
 *   <li> a fastpath call interface ({@link #createFastpathParameters}
 *        and {@link #fastpathCall}).
 * </ul>
 *
 *<p>
 * Query objects may represent a query that has parameter placeholders. To provide
 * actual values for these parameters, a {@link ParameterList} object is created
 * via a factory method ({@link Query#createParameterList}). The parameters are filled
 * in by the caller and passed along with the query to the query execution methods.
 * Several ParameterLists for a given query might exist at one time (or over time);
 * this allows the underlying Query to be reused for several executions, or for
 * batch execution of the same Query.
 *
 *<p>
 * In general, a Query created by a particular QueryExecutor may only be
 * executed by that QueryExecutor, and a ParameterList created by a particular
 * Query may only be used as parameters to that Query. Unpredictable things will
 * happen if this isn't done.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
public interface QueryExecutor {
    /**
     * Flag for query execution that indicates the given Query object is unlikely
     * to be reused.
     */
    static int QUERY_ONESHOT = 1;

    /**
     * Flag for query execution that indicates that resultset metadata isn't needed
     * and can be safely omitted.
     */
    static int QUERY_NO_METADATA = 2;

    /**
     * Flag for query execution that indicates that a resultset isn't expected and
     * the query executor can safely discard any rows (although the resultset should
     * still appear to be from a resultset-returning query).
     */
    static int QUERY_NO_RESULTS = 4;

    /**
     * Flag for query execution that indicates a forward-fetch-capable cursor should
     * be used if possible.
     */
    static int QUERY_FORWARD_CURSOR = 8;

    /**
     * Flag for query execution that indicates the automatic BEGIN on the first statement
     * when outside a transaction should not be done.
     */
    static int QUERY_SUPPRESS_BEGIN = 16;

    /**
     * Flag for query execution when we don't really want to execute, we just
     * want to get the parameter metadata for the statement.
     */
    static int QUERY_DESCRIBE_ONLY = 32;

    /**
     * Flag for query execution used by generated keys where we want to receive
     * both the ResultSet and associated update count from the command status.
     */
    static int QUERY_BOTH_ROWS_AND_STATUS = 64;

    /**
     * Flag to disable batch execution when we expect results (generated keys)
     * from a statement.
     */
    static int QUERY_DISALLOW_BATCHING = 128;

    /**
     * Flag for query execution to avoid using binary transfer.
     */
    static int QUERY_NO_BINARY_TRANSFER = 256;

    /**
     * Execute a Query, passing results to a provided ResultHandler.
     *
     * @param query the query to execute; must be a query returned from
     *  calling {@link #createSimpleQuery(String)} or {@link #createParameterizedQuery(String)}
     *  on this QueryExecutor object.
     * @param parameters the parameters for the query. Must be non-<code>null</code>
     *  if the query takes parameters. Must be a parameter object returned by
     *  {@link org.postgresql.core.Query#createParameterList()}.
     * @param handler a ResultHandler responsible for handling results generated
     *  by this query
     * @param maxRows the maximum number of rows to retrieve
     * @param fetchSize if QUERY_FORWARD_CURSOR is set, the preferred number of rows to retrieve before suspending
     * @param flags a combination of QUERY_* flags indicating how to handle the query.
     *
     * @throws SQLException if query execution fails
     */
    void execute(Query query,
                 ParameterList parameters,
                 ResultHandler handler,
                 int maxRows,
                 int fetchSize,
                 int flags)
    throws SQLException;

    /**
     * Execute several Query, passing results to a provided ResultHandler.
     *
     * @param queries the queries to execute; each must be a query returned from
     *  calling {@link #createSimpleQuery(String)} or {@link #createParameterizedQuery(String)}
     *  on this QueryExecutor object.
     * @param parameterLists the parameter lists for the queries. The parameter lists
     *  correspond 1:1 to the queries passed in the <code>queries</code> array. Each must be
     *  non-<code>null</code> if the corresponding query takes parameters, and must
     *  be a parameter object returned by {@link org.postgresql.core.Query#createParameterList()}
     *  created by the corresponding query.
     * @param handler a ResultHandler responsible for handling results generated
     *  by this query
     * @param maxRows the maximum number of rows to retrieve
     * @param fetchSize if QUERY_FORWARD_CURSOR is set, the preferred number of rows to retrieve before suspending
     * @param flags a combination of QUERY_* flags indicating how to handle the query.
     *
     * @throws SQLException if query execution fails
     */
    void execute(Query[] queries,
                 ParameterList[] parameterLists,
                 ResultHandler handler,
                 int maxRows,
                 int fetchSize,
                 int flags)
    throws SQLException;

    /**
     * Fetch additional rows from a cursor.
     *
     * @param cursor the cursor to fetch from
     * @param handler the handler to feed results to
     * @param fetchSize the preferred number of rows to retrieve before suspending
     * @throws SQLException if query execution fails
     */
    void fetch(ResultCursor cursor, ResultHandler handler, int fetchSize) throws SQLException;

    /**
     * Create an unparameterized Query object suitable for execution by
     * this QueryExecutor. The provided query string is not parsed for
     * parameter placeholders ('?' characters), and the 
     * {@link Query#createParameterList} of the returned object will
     * always return an empty ParameterList.
     *
     * @param sql the SQL for the query to create
     * @return a new Query object
     */
    Query createSimpleQuery(String sql);

    /**
     * Create a parameterized Query object suitable for execution by
     * this QueryExecutor. The provided query string is parsed for
     * parameter placeholders ('?' characters), and the 
     * {@link Query#createParameterList} of the returned object will
     * create an appropriately-sized ParameterList.
     *
     * @param sql the SQL for the query to create, with '?' placeholders for
     *   parameters.
     * @return a new Query object
     */
    Query createParameterizedQuery(String sql); // Parsed for parameter placeholders ('?')

    /**
     * Prior to attempting to retrieve notifications, we need to pull
     * any recently received notifications off of the network buffers.
     * The notification retrieval in ProtocolConnection cannot do this
     * as it is prone to deadlock, so the higher level caller must be
     * responsible which requires exposing this method.
     */
    void processNotifies() throws SQLException;

    //
    // Fastpath interface.
    //

    /**
     * Create a new ParameterList implementation suitable for invoking a
     * fastpath function via {@link #fastpathCall}.
     *
     * @param count the number of parameters the fastpath call will take
     * @return a ParameterList suitable for passing to {@link #fastpathCall}.
     */
    ParameterList createFastpathParameters(int count);

    /**
     * Invoke a backend function via the fastpath interface.
     *
     * @param fnid the OID of the backend function to invoke
     * @param params a ParameterList returned from {@link #createFastpathParameters}
     *  containing the parameters to pass to the backend function
     *
     * @return the binary-format result of the fastpath call, or <code>null</code>
     *  if a void result was returned
     * @throws SQLException if an error occurs while executing the fastpath call
     */
    byte[] fastpathCall(int fnid, ParameterList params, boolean suppressBegin) throws SQLException;

    /**
     * Issues a COPY FROM STDIN / COPY TO STDOUT statement and returns
     * handler for associated operation.  Until the copy operation completes,
     * no other database operation may be performed.
     * Implemented for protocol version 3 only.
     * @throws SQLException when initializing the given query fails
     */
    CopyOperation startCopy(String sql, boolean suppressBegin) throws SQLException;
}
