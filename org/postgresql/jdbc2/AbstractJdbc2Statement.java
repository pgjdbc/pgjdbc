/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;


import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.postgresql.Driver;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.CachedQuery;
import org.postgresql.core.Field;
import org.postgresql.core.Oid;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandler;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.v3.QueryExecutorImpl;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.HStoreConverter;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;
import org.postgresql.util.PGTime;
import org.postgresql.util.PGTimestamp;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * This class defines methods of the jdbc2 specification.
 * The real Statement class (for jdbc2) is org.postgresql.jdbc2.Jdbc2Statement
 */
public abstract class AbstractJdbc2Statement implements BaseStatement
{
    /**
     * Default state for use or not binary transfers. Can use only for testing purposes
     */
    private static final boolean DEFAULT_FORCE_BINARY_TRANSFERS = Boolean.getBoolean("org.postgresql.forceBinary");
    // only for testing purposes. even single shot statements will use binary transfers
    private boolean forceBinaryTransfers = DEFAULT_FORCE_BINARY_TRANSFERS;

    protected ArrayList<Query> batchStatements = null;
    protected ArrayList<ParameterList> batchParameters = null;
    protected final int resultsettype;   // the resultset type to return (ResultSet.TYPE_xxx)
    protected final int concurrency;   // is it updateable or not?     (ResultSet.CONCUR_xxx)
    protected int fetchdirection = ResultSet.FETCH_FORWARD;  // fetch direction hint (currently ignored)

    /**
     * Protects current statement from cancelTask starting, waiting for a bit, and waking up exactly on subsequent query execution.
     * The idea is to atomically compare and swap the reference to the task, so the task can detect that statement executes different
     * query than the one the cancelTask was created.
     * Note: the field must be set/get/compareAndSet via {@link #CANCEL_TIMER_UPDATER} as per {@link AtomicReferenceFieldUpdater} javadoc.
     */
    private volatile TimerTask cancelTimerTask = null;
    private static final AtomicReferenceFieldUpdater<AbstractJdbc2Statement, TimerTask> CANCEL_TIMER_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(AbstractJdbc2Statement.class, TimerTask.class, "cancelTimerTask");

    /**
     * Protects statement from out-of-order cancels. It protects from both {@link #setQueryTimeout(int)} and {@link #cancel()} induced ones.
     *
     * .execute() and friends change statementState to STATE_IN_QUERY during execute.
     * .cancel() ignores cancel request if state is IDLE.
     * In case .execute() observes non-IN_QUERY state as it completes the query, it waits till
     * STATE_CANCELLED.
     * Note: the field must be set/get/compareAndSet via {@link #STATE_UPDATER} as per {@link AtomicIntegerFieldUpdater} javadoc.
     */
    private volatile int statementState = STATE_IDLE;
    private final static int STATE_IDLE = 0;
    private final static int STATE_IN_QUERY = 1;
    private final static int STATE_CANCELLING = 2;
    private final static int STATE_CANCELLED = 3;

    private static final AtomicIntegerFieldUpdater<AbstractJdbc2Statement> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractJdbc2Statement.class, "statementState");

    /**
     * Does the caller of execute/executeUpdate want generated keys for this
     * execution?  This is set by Statement methods that have generated keys
     * arguments and cleared after execution is complete.
     */
    protected boolean wantsGeneratedKeysOnce = false;

    /**
     * Was this PreparedStatement created to return generated keys for every
     * execution?  This is set at creation time and never cleared by
     * execution.
     */
    public boolean wantsGeneratedKeysAlways = false;

    // The connection who created us
    protected final BaseConnection connection;

    /** The warnings chain. */
    protected SQLWarning warnings = null;
    /** The last warning of the warning chain. */
    protected SQLWarning lastWarning = null;

    /** Maximum number of rows to return, 0 = unlimited */
    protected int maxrows = 0;

    /** Number of rows to get in a batch. */
    protected int fetchSize = 0;

    /** Timeout (in milliseconds) for a query */
    protected int timeout = 0;

    protected boolean replaceProcessingEnabled = true;

    /** The current results. */
    protected ResultWrapper result = null;

    /** The first unclosed result. */
    protected ResultWrapper firstUnclosedResult = null;

    /** Results returned by a statement that wants generated keys. */
    protected ResultWrapper generatedKeys = null;
    
    /** used to differentiate between new function call
     * logic and old function call logic
     * will be set to true if the server is < 8.1 or 
     * if we are using v2 protocol
     * There is an exception to this where we are using v3, and the
     * call does not have an out parameter before the call
     */
    protected boolean adjustIndex = false;
    
    /*
     * Used to set adjustIndex above
     */
    protected boolean outParmBeforeFunc=false;
    
    // Static variables for parsing SQL when replaceProcessing is true.
    private static final short IN_SQLCODE = 0;
    private static final short IN_STRING = 1;
    private static final short IN_IDENTIFIER = 6;
    private static final short BACKSLASH = 2;
    private static final short ESC_TIMEDATE = 3;
    private static final short ESC_FUNCTION = 4;
    private static final short ESC_OUTERJOIN = 5;
    private static final short ESC_ESCAPECHAR = 7;
    
    protected final CachedQuery preparedQuery;        // Query fragments for prepared statement.
    protected final ParameterList preparedParameters; // Parameter values for prepared statement.
    protected Query lastSimpleQuery;

    protected int m_prepareThreshold;                // Reuse threshold to enable use of PREPARE

    //Used by the callablestatement style methods
    private boolean isFunction;
    // functionReturnType contains the user supplied value to check
    // testReturn contains a modified version to make it easier to
    // check the getXXX methods..
    private int []functionReturnType;
    private int []testReturn;
    // returnTypeSet is true when a proper call to registerOutParameter has been made
    private boolean returnTypeSet;
    protected Object []callResult;
    protected int maxfieldSize = 0;

    public ResultSet createDriverResultSet(Field[] fields, List tuples)
    throws SQLException
    {
        return createResultSet(null, fields, tuples, null);
    }

    public AbstractJdbc2Statement (AbstractJdbc2Connection c, int rsType, int rsConcurrency) throws SQLException
    {
        this.connection = c;
        this.preparedQuery = null;
        this.preparedParameters = null;
        this.lastSimpleQuery = null;
        forceBinaryTransfers |= c.getForceBinary();
        resultsettype = rsType;
        concurrency = rsConcurrency;
        setFetchSize(c.getDefaultFetchSize());
        setPrepareThreshold(c.getPrepareThreshold());
    }

    public AbstractJdbc2Statement(AbstractJdbc2Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency) throws SQLException
    {
        this.connection = connection;
        this.lastSimpleQuery = null;

        CachedQuery cachedQuery = connection.borrowQuery(sql, isCallable);
        if (isCallable)
        {
            this.isFunction = cachedQuery.isFunction;
            this.outParmBeforeFunc = cachedQuery.outParmBeforeFunc;
        }

        this.preparedQuery = cachedQuery;
        this.preparedParameters = preparedQuery.query.createParameterList();

        if (isFunction) {
            int inParamCount =  preparedParameters.getInParameterCount() + 1;
            this.testReturn = new int[inParamCount];
            this.functionReturnType = new int[inParamCount];
        }

        forceBinaryTransfers |= connection.getForceBinary();

        resultsettype = rsType;
        concurrency = rsConcurrency;
        setFetchSize(connection.getDefaultFetchSize());
        setPrepareThreshold(connection.getPrepareThreshold());
    }

    public abstract ResultSet createResultSet(Query originalQuery, Field[] fields, List tuples, ResultCursor cursor)
    throws SQLException;


    public BaseConnection getPGConnection() {
        return connection;
    }

    public String getFetchingCursorName() {
        return null;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    protected boolean wantsScrollableResultSet() {
        return resultsettype != ResultSet.TYPE_FORWARD_ONLY;
    }

    protected boolean wantsHoldableResultSet() {
        return false;
    }

    //
    // ResultHandler implementations for updates, queries, and either-or.
    //

    public class StatementResultHandler implements ResultHandler {
        private SQLException error;
        private ResultWrapper results;

        ResultWrapper getResults() {
            return results;
        }

        private void append(ResultWrapper newResult) {
            if (results == null)
                results = newResult;
            else
                results.append(newResult);
        }

        public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
            try
            {
                ResultSet rs = AbstractJdbc2Statement.this.createResultSet(fromQuery, fields, tuples, cursor);
                append(new ResultWrapper(rs));
            }
            catch (SQLException e)
            {
                handleError(e);
            }
        }

        public void handleCommandStatus(String status, int updateCount, long insertOID) {
            append(new ResultWrapper(updateCount, insertOID));
        }

        public void handleWarning(SQLWarning warning) {
            AbstractJdbc2Statement.this.addWarning(warning);
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

    /*
     * Execute a SQL statement that retruns a single ResultSet
     *
     * @param sql typically a static SQL SELECT statement
     * @return a ResulSet that contains the data produced by the query
     * @exception SQLException if a database access error occurs
     */
    public java.sql.ResultSet executeQuery(String p_sql) throws SQLException
    {
        if (preparedQuery != null)
            throw new PSQLException(GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
                                    PSQLState.WRONG_OBJECT_TYPE);

        if (forceBinaryTransfers) {
        	clearWarnings();
                // Close any existing resultsets associated with this statement.
                while (firstUnclosedResult != null)
                {
                   if (firstUnclosedResult.getResultSet() != null)
                      firstUnclosedResult.getResultSet().close();
                   firstUnclosedResult = firstUnclosedResult.getNext();
                }

        	PreparedStatement ps = connection.prepareStatement(p_sql, resultsettype, concurrency, getResultSetHoldability());
        	ps.setMaxFieldSize(getMaxFieldSize());
        	ps.setFetchSize(getFetchSize());
        	ps.setFetchDirection(getFetchDirection());
        	AbstractJdbc2ResultSet rs = (AbstractJdbc2ResultSet) ps.executeQuery();
        	rs.registerRealStatement(this);

                result = firstUnclosedResult = new ResultWrapper(rs);
        	return rs;
        }

        if (!executeWithFlags(p_sql, 0))
            throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

        if (result.getNext() != null)
            throw new PSQLException(GT.tr("Multiple ResultSets were returned by the query."),
                                    PSQLState.TOO_MANY_RESULTS);

        return (ResultSet)result.getResultSet();
    }

    /*
     * A Prepared SQL query is executed and its ResultSet is returned
     *
     * @return a ResultSet that contains the data produced by the
     *   * query - never null
     * @exception SQLException if a database access error occurs
     */
    public java.sql.ResultSet executeQuery() throws SQLException
    {
        if (!executeWithFlags(0))
            throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

        if (result.getNext() != null)
            throw new PSQLException(GT.tr("Multiple ResultSets were returned by the query."), PSQLState.TOO_MANY_RESULTS);

        return (ResultSet) result.getResultSet();
    }

    /*
     * Execute a SQL INSERT, UPDATE or DELETE statement.  In addition
     * SQL statements that return nothing such as SQL DDL statements
     * can be executed
     *
     * @param sql a SQL statement
     * @return either a row count, or 0 for SQL commands
     * @exception SQLException if a database access error occurs
     */
    public int executeUpdate(String p_sql) throws SQLException
    {
        if (preparedQuery != null)
            throw new PSQLException(GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
                                    PSQLState.WRONG_OBJECT_TYPE);
        if( isFunction )
        {
            executeWithFlags(p_sql, 0);                
            return 0;
        }

        executeWithFlags(p_sql, QueryExecutor.QUERY_NO_RESULTS);

        ResultWrapper iter = result;
        while (iter != null) {
            if (iter.getResultSet() != null) {
                throw new PSQLException(GT.tr("A result was returned when none was expected."),
                    				  PSQLState.TOO_MANY_RESULTS);

            }
            iter = iter.getNext();
        }

        return getUpdateCount();
    }

    /*
     * Execute a SQL INSERT, UPDATE or DELETE statement.  In addition,
     * SQL statements that return nothing such as SQL DDL statements can
     * be executed.
     *
     * @return either the row count for INSERT, UPDATE or DELETE; or
     *   * 0 for SQL statements that return nothing.
     * @exception SQLException if a database access error occurs
     */
    public int executeUpdate() throws SQLException
    {
        if( isFunction )
        {
            executeWithFlags(0);
            return 0;
        }

        executeWithFlags(QueryExecutor.QUERY_NO_RESULTS);

        ResultWrapper iter = result;
        while (iter != null) {
            if (iter.getResultSet() != null) {
                throw new PSQLException(GT.tr("A result was returned when none was expected."),
                    				  PSQLState.TOO_MANY_RESULTS);

            }
            iter = iter.getNext();
        }

        return getUpdateCount();
    }

    /*
     * Execute a SQL statement that may return multiple results. We
     * don't have to worry about this since we do not support multiple
     * ResultSets.  You can use getResultSet or getUpdateCount to
     * retrieve the result.
     *
     * @param sql any SQL statement
     * @return true if the next result is a ResulSet, false if it is
     * an update count or there are no more results
     * @exception SQLException if a database access error occurs
     */
    public boolean execute(String p_sql) throws SQLException
    {
        if (preparedQuery != null)
            throw new PSQLException(GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
                                    PSQLState.WRONG_OBJECT_TYPE);

        return executeWithFlags(p_sql, 0);
    }

    public boolean executeWithFlags(String p_sql, int flags) throws SQLException
    {
        checkClosed();
        p_sql = replaceProcessing(p_sql, replaceProcessingEnabled, connection.getStandardConformingStrings());
        Query simpleQuery = connection.getQueryExecutor().createSimpleQuery(p_sql);
        execute(simpleQuery, null, QueryExecutor.QUERY_ONESHOT | flags);
        this.lastSimpleQuery = simpleQuery;
        return (result != null && result.getResultSet() != null);
    }

    public boolean execute() throws SQLException
    {
        return executeWithFlags(0);
    }

    public boolean executeWithFlags(int flags) throws SQLException
    {
        checkClosed();
   
        execute(preparedQuery.query, preparedParameters, flags);

        // If we are executing and there are out parameters 
        // callable statement function set the return data
        
        if (isFunction && returnTypeSet )
        {
            if (result == null || result.getResultSet() == null)
                throw new PSQLException(GT.tr("A CallableStatement was executed with nothing returned."), PSQLState.NO_DATA);

            ResultSet rs = result.getResultSet();
            if (!rs.next())
                throw new PSQLException(GT.tr("A CallableStatement was executed with nothing returned."), PSQLState.NO_DATA);

            // figure out how many columns
            int cols = rs.getMetaData().getColumnCount();
            
            int outParameterCount = preparedParameters.getOutParameterCount() ;
            
            if ( cols != outParameterCount )
                throw new PSQLException(GT.tr("A CallableStatement was executed with an invalid number of parameters"),PSQLState.SYNTAX_ERROR);
           
            // reset last result fetched (for wasNull)
            lastIndex = 0;

            // allocate enough space for all possible parameters without regard to in/out            
            callResult = new Object[preparedParameters.getParameterCount()+1];
            
            // move them into the result set
            for ( int i=0,j=0; i < cols; i++,j++)
            {
                // find the next out parameter, the assumption is that the functionReturnType 
                // array will be initialized with 0 and only out parameters will have values
                // other than 0. 0 is the value for java.sql.Types.NULL, which should not 
                // conflict
                while( j< functionReturnType.length && functionReturnType[j]==0) j++;
                
                callResult[j] = rs.getObject(i+1);
                int columnType = rs.getMetaData().getColumnType(i+1);

                if (columnType != functionReturnType[j])
                {
                    // this is here for the sole purpose of passing the cts
                    if ( columnType == Types.DOUBLE && functionReturnType[j] == Types.REAL )
                    {
                        // return it as a float
                        if ( callResult[j] != null)
                            callResult[j] = ((Double) callResult[j]).floatValue();
                    }
                    else
                    {    
	                    throw new PSQLException (GT.tr("A CallableStatement function was executed and the out parameter {0} was of type {1} however type {2} was registered.",
	                            new Object[]{i + 1,
	                                "java.sql.Types=" + columnType, "java.sql.Types=" + functionReturnType[j] }),
	                      PSQLState.DATA_TYPE_MISMATCH);
                    }
                }
                    
            }
            rs.close();
            result = null;
            return false;
        }

        return (result != null && result.getResultSet() != null);
    }

    protected void closeForNextExecution() throws SQLException {
        // Every statement execution clears any previous warnings.
        clearWarnings();

        // Close any existing resultsets associated with this statement.
        while (firstUnclosedResult != null)
        {
            ResultSet rs = firstUnclosedResult.getResultSet();
            if (rs != null)
            {
                rs.close();
            }
            firstUnclosedResult = firstUnclosedResult.getNext();
        }
        result = null;

        if (lastSimpleQuery != null) {
            lastSimpleQuery.close();
            lastSimpleQuery = null;
        }

        if (generatedKeys != null) {
            if (generatedKeys.getResultSet() != null) {
                generatedKeys.getResultSet().close();
            }
            generatedKeys = null;
        }
    }

    protected void execute(Query queryToExecute, ParameterList queryParameters, int flags) throws SQLException {
        closeForNextExecution();

        // Enable cursor-based resultset if possible.
        if (fetchSize > 0 && !wantsScrollableResultSet() && !connection.getAutoCommit() && !wantsHoldableResultSet())
            flags |= QueryExecutor.QUERY_FORWARD_CURSOR;

        if (wantsGeneratedKeysOnce || wantsGeneratedKeysAlways)
        {
            flags |= QueryExecutor.QUERY_BOTH_ROWS_AND_STATUS;

            // If the no results flag is set (from executeUpdate)
            // clear it so we get the generated keys results.
            //
            if ((flags & QueryExecutor.QUERY_NO_RESULTS) != 0)
                flags &= ~(QueryExecutor.QUERY_NO_RESULTS);
        }

        // Only use named statements after we hit the threshold. Note that only
        // named statements can be transferred in binary format.
        if (preparedQuery != null && preparedQuery.query == queryToExecute)
        {
            preparedQuery.increaseExecuteCount();
            if ((m_prepareThreshold == 0 || preparedQuery.getExecuteCount() < m_prepareThreshold) && !forceBinaryTransfers)
                flags |= QueryExecutor.QUERY_ONESHOT;
        }

        if (connection.getAutoCommit())
            flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;

        // updateable result sets do not yet support binary updates
        if (concurrency != ResultSet.CONCUR_READ_ONLY)
            flags |= QueryExecutor.QUERY_NO_BINARY_TRANSFER;

        if (queryToExecute.isEmpty())
        {
            flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;
        }

        if (!queryToExecute.isStatementDescribed() && forceBinaryTransfers) {
                int flags2 = flags | QueryExecutor.QUERY_DESCRIBE_ONLY;
                StatementResultHandler handler2 = new StatementResultHandler();
                connection.getQueryExecutor().execute(queryToExecute, queryParameters, handler2, 0, 0, flags2);
                ResultWrapper result2 = handler2.getResults();
                if (result2 != null) {
                    result2.getResultSet().close();
                }
        }

        StatementResultHandler handler = new StatementResultHandler();
        result = null;
        try
        {
            startTimer();
            connection.getQueryExecutor().execute(queryToExecute,
                                                  queryParameters,
                                                  handler,
                                                  maxrows,
                                                  fetchSize,
                                                  flags);
        }
        finally
        {
            killTimerTask();
        }
        result = firstUnclosedResult = handler.getResults();

        if (wantsGeneratedKeysOnce || wantsGeneratedKeysAlways)
        {
            generatedKeys = result;
            result = result.getNext();

            if (wantsGeneratedKeysOnce)
                wantsGeneratedKeysOnce = false;
        }

    }

    /*
     * setCursorName defines the SQL cursor name that will be used by
     * subsequent execute methods. This name can then be used in SQL
     * positioned update/delete statements to identify the current row
     * in the ResultSet generated by this statement.  If a database
     * doesn't support positioned update/delete, this method is a
     * no-op.
     *
     * <p><B>Note:</B> By definition, positioned update/delete execution
     * must be done by a different Statement than the one which
     * generated the ResultSet being used for positioning. Also, cursor
     * names must be unique within a Connection.
     *
     * @param name the new cursor name
     * @exception SQLException if a database access error occurs
     */
    public void setCursorName(String name) throws SQLException
    {
        checkClosed();
        // No-op.
    }

    // This is intentionally non-volatile to avoid performance hit in isClosed checks
    // see #close()
    protected boolean isClosed = false;
    private int lastIndex = 0;
    /*
     * getUpdateCount returns the current result as an update count,
     * if the result is a ResultSet or there are no more results, -1
     * is returned.  It should only be called once per result.
     *
     * @return the current result as an update count.
     * @exception SQLException if a database access error occurs
     */
    public int getUpdateCount() throws SQLException
    {
        checkClosed();
        if (result == null || result.getResultSet() != null)
            return -1;

        return result.getUpdateCount();
    }

    /*
     * getMoreResults moves to a Statement's next result.  If it returns
     * true, this result is a ResulSet.
     *
     * @return true if the next ResultSet is valid
     * @exception SQLException if a database access error occurs
     */
    public boolean getMoreResults() throws SQLException
    {
        if (result == null)
            return false;

        result = result.getNext();

        // Close preceding resultsets.
        while (firstUnclosedResult != result)
        {
            if (firstUnclosedResult.getResultSet() != null)
                firstUnclosedResult.getResultSet().close();
            firstUnclosedResult = firstUnclosedResult.getNext();
        }

        return (result != null && result.getResultSet() != null);
    }

    /*
     * The maxRows limit is set to limit the number of rows that
     * any ResultSet can contain.  If the limit is exceeded, the
     * excess rows are silently dropped.
     *
     * @return the current maximum row limit; zero means unlimited
     * @exception SQLException if a database access error occurs
     */
    public int getMaxRows() throws SQLException
    {
        checkClosed();
        return maxrows;
    }

    /*
     * Set the maximum number of rows
     *
     * @param max the new max rows limit; zero means unlimited
     * @exception SQLException if a database access error occurs
     * @see getMaxRows
     */
    public void setMaxRows(int max) throws SQLException
    {
        checkClosed();
        if (max < 0)
            throw new PSQLException(GT.tr("Maximum number of rows must be a value grater than or equal to 0."),
                                    PSQLState.INVALID_PARAMETER_VALUE);
        maxrows = max;
    }

    /*
     * If escape scanning is on (the default), the driver will do escape
     * substitution before sending the SQL to the database.
     *
     * @param enable true to enable; false to disable
     * @exception SQLException if a database access error occurs
     */
    public void setEscapeProcessing(boolean enable) throws SQLException
    {
        checkClosed();
        replaceProcessingEnabled = enable;
    }

    /*
     * The queryTimeout limit is the number of seconds the driver
     * will wait for a Statement to execute.  If the limit is
     * exceeded, a SQLException is thrown.
     *
     * @return the current query timeout limit in seconds; 0 = unlimited
     * @exception SQLException if a database access error occurs
     */
    public int getQueryTimeout() throws SQLException
    {
        return getQueryTimeoutMs() / 1000;
    }

    /*
     * Sets the queryTimeout limit
     *
     * @param seconds - the new query timeout limit in seconds
     * @exception SQLException if a database access error occurs
     */
    public void setQueryTimeout(int seconds) throws SQLException
    {
        setQueryTimeoutMs(seconds * 1000);
    }

    /*
     * The queryTimeout limit is the number of milliseconds the driver
     * will wait for a Statement to execute.  If the limit is
     * exceeded, a SQLException is thrown.
     *
     * @return the current query timeout limit in milliseconds; 0 = unlimited
     * @exception SQLException if a database access error occurs
     */
    public int getQueryTimeoutMs() throws SQLException
    {
        checkClosed();
        return timeout;
    }

    /*
     * Sets the queryTimeout limit
     *
     * @param seconds - the new query timeout limit in milliseconds
     * @exception SQLException if a database access error occurs
     */
    public void setQueryTimeoutMs(int millis) throws SQLException
    {
        checkClosed();

        if (millis < 0)
            throw new PSQLException(GT.tr("Query timeout must be a value greater than or equals to 0."),
                                    PSQLState.INVALID_PARAMETER_VALUE);
        timeout = millis;
    }

    /**
     * This adds a warning to the warning chain.  We track the
     * tail of the warning chain as well to avoid O(N) behavior
     * for adding a new warning to an existing chain.  Some
     * server functions which RAISE NOTICE (or equivalent) produce
     * a ton of warnings.
     * @param warn warning to add
     */
    public void addWarning(SQLWarning warn)
    {
        if (warnings == null) {
            warnings = warn;
            lastWarning = warn;
        } else {
            lastWarning.setNextWarning(warn);
            lastWarning = warn;
        }
    }

    /*
     * The first warning reported by calls on this Statement is
     * returned.  A Statement's execute methods clear its SQLWarning
     * chain.  Subsequent Statement warnings will be chained to this
     * SQLWarning.
     *
     * <p>The Warning chain is automatically cleared each time a statement
     * is (re)executed.
     *
     * <p><B>Note:</B> If you are processing a ResultSet then any warnings
     * associated with ResultSet reads will be chained on the ResultSet
     * object.
     *
     * @return the first SQLWarning on null
     * @exception SQLException if a database access error occurs
     */
    public SQLWarning getWarnings() throws SQLException
    {
        checkClosed();
        return warnings;
    }

    /*
     * The maxFieldSize limit (in bytes) is the maximum amount of
     * data returned for any column value; it only applies to
     * BINARY, VARBINARY, LONGVARBINARY, CHAR, VARCHAR and LONGVARCHAR
     * columns.  If the limit is exceeded, the excess data is silently
     * discarded.
     *
     * @return the current max column size limit; zero means unlimited
     * @exception SQLException if a database access error occurs
     */
    public int getMaxFieldSize() throws SQLException
    {
        return maxfieldSize;
    }

    /*
     * Sets the maxFieldSize
     *
     * @param max the new max column size limit; zero means unlimited
     * @exception SQLException if a database access error occurs
     */
    public void setMaxFieldSize(int max) throws SQLException
    {
        checkClosed();
        if (max < 0)
            throw new PSQLException(GT.tr("The maximum field size must be a value greater than or equal to 0."),
                                    PSQLState.INVALID_PARAMETER_VALUE);
        maxfieldSize = max;
    }

    /*
     * After this call, getWarnings returns null until a new warning
     * is reported for this Statement.
     *
     * @exception SQLException if a database access error occurs
     */
    public void clearWarnings() throws SQLException
    {
        warnings = null;
        lastWarning = null;
    }

    /*
     * getResultSet returns the current result as a ResultSet. It
     * should only be called once per result.
     *
     * @return the current result set; null if there are no more
     * @exception SQLException if a database access error occurs (why?)
     */
    public java.sql.ResultSet getResultSet() throws SQLException
    {
        checkClosed();

        if (result == null)
            return null;

        return (ResultSet) result.getResultSet();
    }

    /*
     * In many cases, it is desirable to immediately release a
     * Statement's database and JDBC resources instead of waiting
     * for this to happen when it is automatically closed. The
     * close method provides this immediate release.
     *
     * <p><B>Note:</B> A Statement is automatically closed when it is
     * garbage collected.  When a Statement is closed, its current
     * ResultSet, if one exists, is also closed.
     *
     * @exception SQLException if a database access error occurs (why?)
     */
    public void close() throws SQLException
    {
        // closing an already closed Statement is a no-op.
        if (isClosed)
            return ;

        cleanupTimer();
        
        closeForNextExecution();

        if (preparedQuery != null) {
            // See #368. We need to prevent closing the same statement twice
            // Otherwise we might "release" a query that someone else is already using
            // In other words, client does .close() as usual, however cleanup thread might fail to observe isClosed=true
            synchronized (preparedQuery) {
                if (!isClosed) {
                    ((AbstractJdbc2Connection) connection).releaseQuery(preparedQuery);
                }
            }
        }

        isClosed = true;
    }

    /**
     * Statement finalizer is not required as "statement" at the database side
     * is managed by a {@link java.lang.ref.PhantomReference} in {@link QueryExecutorImpl#processDeadParsedQueries()}.
     */

    /*
     * Filter the SQL string of Java SQL Escape clauses.
     *
     * Currently implemented Escape clauses are those mentioned in 11.3
     * in the specification. Basically we look through the sql string for
     * {d xxx}, {t xxx}, {ts xxx}, {oj xxx} or {fn xxx} in non-string sql 
     * code. When we find them, we just strip the escape part leaving only
     * the xxx part.
     * So, something like "select * from x where d={d '2001-10-09'}" would
     * return "select * from x where d= '2001-10-09'".
     */
    static String replaceProcessing(String p_sql, boolean replaceProcessingEnabled, boolean standardConformingStrings) throws SQLException
    {
        if (replaceProcessingEnabled)
        {
            // Since escape codes can only appear in SQL CODE, we keep track
            // of if we enter a string or not.
            int len = p_sql.length();
            StringBuilder newsql = new StringBuilder(len);
            int i=0;
            while (i<len){
                i = parseSql(p_sql, i, newsql, false, standardConformingStrings);
                // We need to loop here in case we encounter invalid
                // SQL, consider: SELECT a FROM t WHERE (1 > 0)) ORDER BY a
                // We can't ending replacing after the extra closing paren
                // because that changes a syntax error to a valid query
                // that isn't what the user specified.
                if (i < len) {
                    newsql.append(p_sql.charAt(i));
                    i++;
                }
            }
            return newsql.toString();
        }
        else
        {
            return p_sql;
        }
    }
    
    /**
     * parse the given sql from index i, appending it to the gven buffer
     * until we hit an unmatched right parentheses or end of string.  When
     * the stopOnComma flag is set we also stop processing when a comma is
     * found in sql text that isn't inside nested parenthesis.
     *
     * @param p_sql the original query text
     * @param i starting position for replacing
     * @param newsql where to write the replaced output
     * @param stopOnComma should we stop after hitting the first comma in sql text?
     * @param stdStrings whether standard_conforming_strings is on
     * @return the position we stopped processing at
     */
    protected static int parseSql(String p_sql,int i,StringBuilder newsql, boolean stopOnComma,
                                  boolean stdStrings)throws SQLException{
        short state = IN_SQLCODE;
        int len = p_sql.length();
        int nestedParenthesis=0;
        boolean endOfNested=false;

        // because of the ++i loop
        i--;
        while (!endOfNested && ++i < len)
        {
            char c = p_sql.charAt(i);
            switch (state)
            {
            case IN_SQLCODE:
                if (c == '\'')      // start of a string?
                    state = IN_STRING;
                else if (c == '"')      // start of a identifer?
                    state = IN_IDENTIFIER;
                else if (c=='(') { // begin nested sql
                    nestedParenthesis++;
                } else if (c==')') { // end of nested sql
                    nestedParenthesis--;
                    if (nestedParenthesis<0){
                        endOfNested=true;
                        break;
                    }
                } else if (stopOnComma && c==',' && nestedParenthesis==0) {
                    endOfNested=true;
                    break;
                } else if (c == '{') {     // start of an escape code?
                    if (i + 1 < len)
                    {
                        char next = p_sql.charAt(i + 1);
                        char nextnext = (i + 2 < len) ? p_sql.charAt(i + 2) : '\0';
                        if (next == 'd' || next == 'D')
                        {
                            state = ESC_TIMEDATE;
                            i++;
                            newsql.append("DATE ");
                            break;
                        }
                        else if (next == 't' || next == 'T')
                        {
                            state = ESC_TIMEDATE;
                            if (nextnext == 's' || nextnext == 'S'){
                                // timestamp constant
                                i+=2;
                                newsql.append("TIMESTAMP ");
                            }else{
                                // time constant
                                i++;
                                newsql.append("TIME ");
                            }
                            break;
                        }
                        else if ( next == 'f' || next == 'F' )
                        {
                            state = ESC_FUNCTION;
                            i += (nextnext == 'n' || nextnext == 'N') ? 2 : 1;
                            break;
                        }
                        else if ( next == 'o' || next == 'O' )
                        {
                            state = ESC_OUTERJOIN;
                            i += (nextnext == 'j' || nextnext == 'J') ? 2 : 1;
                            break;
                        }
                        else if ( next == 'e' || next == 'E' )
                        { // we assume that escape is the only escape sequence beginning with e
                            state = ESC_ESCAPECHAR;
                            break;
                        }
                    }
                }
                newsql.append(c);
                break;

            case IN_STRING:
                if (c == '\'')       // end of string?
                    state = IN_SQLCODE;
                else if (c == '\\' && !stdStrings)      // a backslash?
                    state = BACKSLASH;

                newsql.append(c);
                break;
                
            case IN_IDENTIFIER:
                if (c == '"')       // end of identifier
                    state = IN_SQLCODE;
                newsql.append(c);
                break;

            case BACKSLASH:
                state = IN_STRING;

                newsql.append(c);
                break;

            case ESC_FUNCTION:
                // extract function name
                String functionName;
                int posArgs = p_sql.indexOf('(',i);
                if (posArgs!=-1){
                    functionName=p_sql.substring(i,posArgs).trim();
                    // extract arguments
                    i= posArgs+1;// we start the scan after the first (
                    StringBuilder args=new StringBuilder();
                    i = parseSql(p_sql,i,args,false,stdStrings);
                    // translate the function and parse arguments
                    newsql.append(escapeFunction(functionName,args.toString(),stdStrings));
                }
                // go to the end of the function copying anything found
                i++;
                while (i<len && p_sql.charAt(i)!='}')
                    newsql.append(p_sql.charAt(i++));
                state = IN_SQLCODE; // end of escaped function (or query)
                break;
            case ESC_TIMEDATE:       
            case ESC_OUTERJOIN:
            case ESC_ESCAPECHAR:
                if (c == '}')
                    state = IN_SQLCODE;    // end of escape code.
                else
                    newsql.append(c);
                break;
            } // end switch
        }
        return i;
    }
    
    /**
     * generate sql for escaped functions
     * @param functionName the escaped function name
     * @param args the arguments for this functin
     * @param stdStrings whether standard_conforming_strings is on
     * @return the right postgreSql sql
     */
    protected static String escapeFunction(String functionName, String args, boolean stdStrings) throws SQLException{
        // parse function arguments
        int len = args.length();
        int i=0;
        ArrayList parsedArgs = new ArrayList();
        while (i<len){
            StringBuilder arg = new StringBuilder();
            int lastPos=i;
            i=parseSql(args,i,arg,true,stdStrings);
            if (lastPos!=i){
                parsedArgs.add(arg);
            }
            i++;
        }
        // we can now tranlate escape functions
        try{
            Method escapeMethod = EscapedFunctions.getFunction(functionName);
            return (String) escapeMethod.invoke(null,new Object[] {parsedArgs});
        }catch(InvocationTargetException e){
            if (e.getTargetException() instanceof SQLException)
                throw (SQLException) e.getTargetException();
            else
                throw new PSQLException(e.getTargetException().getMessage(),
                                        PSQLState.SYSTEM_ERROR);
        }catch (Exception e){
            // by default the function name is kept unchanged
            StringBuilder buf = new StringBuilder();
            buf.append(functionName).append('(');
            for (int iArg = 0;iArg<parsedArgs.size();iArg++){
                buf.append(parsedArgs.get(iArg));
                if (iArg!=(parsedArgs.size()-1))
                    buf.append(',');
            }
            buf.append(')');
            return buf.toString();    
        }
    }

    /*
     *
     * The following methods are postgres extensions and are defined
     * in the interface BaseStatement
     *
     */

    /*
     * Returns the Last inserted/updated oid.  Deprecated in 7.2 because
      * range of OID values is greater than a java signed int.
     * @deprecated Replaced by getLastOID in 7.2
     */
    public int getInsertedOID() throws SQLException
    {
        checkClosed();
        if (result == null)
            return 0;
        return (int) result.getInsertOID();
    }

    /*
     * Returns the Last inserted/updated oid.
     * @return OID of last insert
      * @since 7.2
     */
    public long getLastOID() throws SQLException
    {
        checkClosed();
        if (result == null)
            return 0;
        return result.getInsertOID();
    }

    /*
     * Set a parameter to SQL NULL
     *
     * <p><B>Note:</B> You must specify the parameter's SQL type.
     *
     * @param parameterIndex the first parameter is 1, etc...
     * @param sqlType the SQL type code defined in java.sql.Types
     * @exception SQLException if a database access error occurs
     */
    public void setNull(int parameterIndex, int sqlType) throws SQLException
    {
        checkClosed();

        int oid;
        switch (sqlType)
        {
        case Types.INTEGER:
            oid = Oid.INT4;
            break;
        case Types.TINYINT:
        case Types.SMALLINT:
            oid = Oid.INT2;
            break;
        case Types.BIGINT:
            oid = Oid.INT8;
            break;
        case Types.REAL:
            oid = Oid.FLOAT4;
            break;
        case Types.DOUBLE:
        case Types.FLOAT:				
            oid = Oid.FLOAT8;
            break;
        case Types.DECIMAL:
        case Types.NUMERIC:
            oid = Oid.NUMERIC;
            break;
        case Types.CHAR:
            oid = Oid.BPCHAR;
            break;
        case Types.VARCHAR:
        case Types.LONGVARCHAR:
            oid = connection.getStringVarcharFlag() ? Oid.VARCHAR : Oid.UNSPECIFIED;
            break;
        case Types.DATE:
            oid = Oid.DATE;
            break;
        case Types.TIME:
        case Types.TIMESTAMP:
            oid = Oid.UNSPECIFIED;
            break;
        case Types.BIT:
            oid = Oid.BOOL;
            break;
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
            if (connection.haveMinimumCompatibleVersion(ServerVersion.v7_2))
            {
                oid = Oid.BYTEA;
            }
            else
            {
                oid = Oid.OID;
            }
            break;
        case Types.BLOB:
        case Types.CLOB:
            oid = Oid.OID;
            break;
        case Types.ARRAY:
        case Types.DISTINCT:
        case Types.STRUCT:
        case Types.NULL:
        case Types.OTHER:
            oid = Oid.UNSPECIFIED;
            break;
        default:
            // Bad Types value.
            throw new PSQLException(GT.tr("Unknown Types value."), PSQLState.INVALID_PARAMETER_TYPE);
        }
        if ( adjustIndex )
            parameterIndex--;
        preparedParameters.setNull( parameterIndex, oid);
    }

    /*
     * Set a parameter to a Java boolean value.  The driver converts this
     * to a SQL BIT value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setBoolean(int parameterIndex, boolean x) throws SQLException
    {
        checkClosed();
        bindString(parameterIndex, x ? "1" : "0", Oid.BOOL);
    }

    /*
     * Set a parameter to a Java byte value.  The driver converts this to
     * a SQL TINYINT value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setByte(int parameterIndex, byte x) throws SQLException
    {
        setShort(parameterIndex, x);
    }

    /*
     * Set a parameter to a Java short value.  The driver converts this
     * to a SQL SMALLINT value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setShort(int parameterIndex, short x) throws SQLException
    {
        checkClosed();
        if (connection.binaryTransferSend(Oid.INT2)) {
            byte[] val = new byte[2];
            ByteConverter.int2(val, 0, x);
            bindBytes(parameterIndex, val, Oid.INT2);
            return;
        }
        bindLiteral(parameterIndex, Integer.toString(x), Oid.INT2);
    }

    /*
     * Set a parameter to a Java int value.  The driver converts this to
     * a SQL INTEGER value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setInt(int parameterIndex, int x) throws SQLException
    {
        checkClosed();         
        if (connection.binaryTransferSend(Oid.INT4)) {
            byte[] val = new byte[4];
            ByteConverter.int4(val, 0, x);
            bindBytes(parameterIndex, val, Oid.INT4);
            return;
        }
        bindLiteral(parameterIndex, Integer.toString(x), Oid.INT4);
    }

    /*
     * Set a parameter to a Java long value.  The driver converts this to
     * a SQL BIGINT value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setLong(int parameterIndex, long x) throws SQLException
    {
        checkClosed();
        if (connection.binaryTransferSend(Oid.INT8)) {
            byte[] val = new byte[8];
            ByteConverter.int8(val, 0, x);
            bindBytes(parameterIndex, val, Oid.INT8);
            return;
        }
        bindLiteral(parameterIndex, Long.toString(x), Oid.INT8);
    }

    /*
     * Set a parameter to a Java float value.  The driver converts this
     * to a SQL FLOAT value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setFloat(int parameterIndex, float x) throws SQLException
    {
        checkClosed();
        if (connection.binaryTransferSend(Oid.FLOAT4)) {
            byte[] val = new byte[4];
            ByteConverter.float4(val, 0, x);
            bindBytes(parameterIndex, val, Oid.FLOAT4);
            return;
        }
        bindLiteral(parameterIndex, Float.toString(x), Oid.FLOAT8);
    }

    /*
     * Set a parameter to a Java double value. The driver converts this
     * to a SQL DOUBLE value when it sends it to the database
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setDouble(int parameterIndex, double x) throws SQLException
    {
        checkClosed();
        if (connection.binaryTransferSend(Oid.FLOAT8)) {
            byte[] val = new byte[8];
            ByteConverter.float8(val, 0, x);
            bindBytes(parameterIndex, val, Oid.FLOAT8);
            return;
        }
        bindLiteral(parameterIndex, Double.toString(x), Oid.FLOAT8);
    }

    /*
     * Set a parameter to a java.lang.BigDecimal value.  The driver
     * converts this to a SQL NUMERIC value when it sends it to the
     * database.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException
    {
        checkClosed();
        if (x == null)
            setNull(parameterIndex, Types.DECIMAL);
        else
            bindLiteral(parameterIndex, x.toString(), Oid.NUMERIC);
    }

    /*
     * Set a parameter to a Java String value. The driver converts this
     * to a SQL VARCHAR or LONGVARCHAR value (depending on the arguments
     * size relative to the driver's limits on VARCHARs) when it sends it
     * to the database.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setString(int parameterIndex, String x) throws SQLException
    {
        checkClosed();
        setString(parameterIndex, x, getStringType());
    }

    private int getStringType() {
        return (connection.getStringVarcharFlag() ? Oid.VARCHAR : Oid.UNSPECIFIED);
    }

    protected void setString(int parameterIndex, String x, int oid) throws SQLException
    {
        // if the passed string is null, then set this column to null
        checkClosed();
        if (x == null)
        {
            if ( adjustIndex )
                parameterIndex--;
            preparedParameters.setNull( parameterIndex, oid);
        }
        else
            bindString(parameterIndex, x, oid);
    }

    /*
     * Set a parameter to a Java array of bytes.  The driver converts this
     * to a SQL VARBINARY or LONGVARBINARY (depending on the argument's
     * size relative to the driver's limits on VARBINARYs) when it sends
     * it to the database.
     *
     * <p>Implementation note:
     * <br>With org.postgresql, this creates a large object, and stores the
     * objects oid in this column.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setBytes(int parameterIndex, byte[] x) throws SQLException
    {
        checkClosed();

        if (null == x)
        {
            setNull(parameterIndex, Types.VARBINARY);
            return ;
        }

        if (connection.haveMinimumCompatibleVersion(ServerVersion.v7_2))
        {
            //Version 7.2 supports the bytea datatype for byte arrays
            byte[] copy = new byte[x.length];
            System.arraycopy(x, 0, copy, 0, x.length);
            preparedParameters.setBytea( parameterIndex, copy, 0, x.length);
        }
        else
        {
            //Version 7.1 and earlier support done as LargeObjects
            LargeObjectManager lom = connection.getLargeObjectAPI();
            long oid = lom.createLO();
            LargeObject lob = lom.open(oid);
            lob.write(x);
            lob.close();
            setLong(parameterIndex, oid);
        }
    }

    /*
     * Set a parameter to a java.sql.Date value.  The driver converts this
     * to a SQL DATE value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setDate(int parameterIndex, java.sql.Date x) throws SQLException
    {
        setDate(parameterIndex, x, null);
    }

    /*
     * Set a parameter to a java.sql.Time value.  The driver converts
     * this to a SQL TIME value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1...));
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setTime(int parameterIndex, Time x) throws SQLException
    {
        setTime(parameterIndex, x, null);
    }

    /*
     * Set a parameter to a java.sql.Timestamp value.  The driver converts
     * this to a SQL TIMESTAMP value when it sends it to the database.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException
    {
        setTimestamp(parameterIndex, x, null);
    }

    private void setCharacterStreamPost71(int parameterIndex, InputStream x, int length, String encoding) throws SQLException
    {

        if (x == null)
        {
            setNull(parameterIndex, Types.VARCHAR);
            return ;
        }
        if (length < 0)
            throw new PSQLException(GT.tr("Invalid stream length {0}.", length),
                                    PSQLState.INVALID_PARAMETER_VALUE);


        //Version 7.2 supports AsciiStream for all PG text types (char, varchar, text)
        //As the spec/javadoc for this method indicate this is to be used for
        //large String values (i.e. LONGVARCHAR)  PG doesn't have a separate
        //long varchar datatype, but with toast all text datatypes are capable of
        //handling very large values.  Thus the implementation ends up calling
        //setString() since there is no current way to stream the value to the server
        try
        {
            InputStreamReader l_inStream = new InputStreamReader(x, encoding);
            char[] l_chars = new char[length];
            int l_charsRead = 0;
            while (true)
            {
                int n = l_inStream.read(l_chars, l_charsRead, length - l_charsRead);
                if (n == -1)
                    break;

                l_charsRead += n;

                if (l_charsRead == length)
                    break;
            }

            setString(parameterIndex, new String(l_chars, 0, l_charsRead), Oid.VARCHAR);
        }
        catch (UnsupportedEncodingException l_uee)
        {
            throw new PSQLException(GT.tr("The JVM claims not to support the {0} encoding.", encoding), PSQLState.UNEXPECTED_ERROR, l_uee);
        }
        catch (IOException l_ioe)
        {
            throw new PSQLException(GT.tr("Provided InputStream failed."), PSQLState.UNEXPECTED_ERROR, l_ioe);
        }
    }

    /*
     * When a very large ASCII value is input to a LONGVARCHAR parameter,
     * it may be more practical to send it via a java.io.InputStream.
     * JDBC will read the data from the stream as needed, until it reaches
     * end-of-file.  The JDBC driver will do any necessary conversion from
     * ASCII to the database char format.
     *
     * <P><B>Note:</B> This stream object can either be a standard Java
     * stream object or your own subclass that implements the standard
     * interface.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @param length the number of bytes in the stream
     * @exception SQLException if a database access error occurs
     */
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException
    {
        checkClosed();
        if (connection.haveMinimumCompatibleVersion(ServerVersion.v7_2))
        {
            setCharacterStreamPost71(parameterIndex, x, length, "ASCII");
        }
        else
        {
            //Version 7.1 supported only LargeObjects by treating everything
            //as binary data
            setBinaryStream(parameterIndex, x, length);
        }
    }

    /*
     * When a very large Unicode value is input to a LONGVARCHAR parameter,
     * it may be more practical to send it via a java.io.InputStream.
     * JDBC will read the data from the stream as needed, until it reaches
     * end-of-file.  The JDBC driver will do any necessary conversion from
     * UNICODE to the database char format.
     *
     * <P><B>Note:</B> This stream object can either be a standard Java
     * stream object or your own subclass that implements the standard
     * interface.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException
    {
        checkClosed();
        if (connection.haveMinimumCompatibleVersion(ServerVersion.v7_2))
        {
            setCharacterStreamPost71(parameterIndex, x, length, "UTF-8");
        }
        else
        {
            //Version 7.1 supported only LargeObjects by treating everything
            //as binary data
            setBinaryStream(parameterIndex, x, length);
        }
    }

    /*
     * When a very large binary value is input to a LONGVARBINARY parameter,
     * it may be more practical to send it via a java.io.InputStream.
     * JDBC will read the data from the stream as needed, until it reaches
     * end-of-file.
     *
     * <P><B>Note:</B> This stream object can either be a standard Java
     * stream object or your own subclass that implements the standard
     * interface.
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs
     */
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException
    {
        checkClosed();

        if (x == null)
        {
            setNull(parameterIndex, Types.VARBINARY);
            return ;
        }

        if (length < 0)
            throw new PSQLException(GT.tr("Invalid stream length {0}.", length),
                                    PSQLState.INVALID_PARAMETER_VALUE);

        if (connection.haveMinimumCompatibleVersion(ServerVersion.v7_2))
        {
            //Version 7.2 supports BinaryStream for for the PG bytea type
            //As the spec/javadoc for this method indicate this is to be used for
            //large binary values (i.e. LONGVARBINARY) PG doesn't have a separate
            //long binary datatype, but with toast the bytea datatype is capable of
            //handling very large values.

            preparedParameters.setBytea(parameterIndex, x, length);
        }
        else
        {
            //Version 7.1 only supported streams for LargeObjects
            //but the jdbc spec indicates that streams should be
            //available for LONGVARBINARY instead
            LargeObjectManager lom = connection.getLargeObjectAPI();
            long oid = lom.createLO();
            LargeObject lob = lom.open(oid);
            OutputStream los = lob.getOutputStream();
            try
            {
                // could be buffered, but then the OutputStream returned by LargeObject
                // is buffered internally anyhow, so there would be no performance
                // boost gained, if anything it would be worse!
                int c = x.read();
                int p = 0;
                while (c > -1 && p < length)
                {
                    los.write(c);
                    c = x.read();
                    p++;
                }
                los.close();
            }
            catch (IOException se)
            {
                throw new PSQLException(GT.tr("Provided InputStream failed."), PSQLState.UNEXPECTED_ERROR, se);
            }
            // lob is closed by the stream so don't call lob.close()
            setLong(parameterIndex, oid);
        }
    }


    /*
     * In general, parameter values remain in force for repeated used of a
     * Statement.  Setting a parameter value automatically clears its
     * previous value. However, in coms cases, it is useful to immediately
     * release the resources used by the current parameter values; this
     * can be done by calling clearParameters
     *
     * @exception SQLException if a database access error occurs
     */
    public void clearParameters() throws SQLException
    {
        preparedParameters.clear();
    }

    // Helper method for setting parameters to PGobject subclasses.
    private void setPGobject(int parameterIndex, PGobject x) throws SQLException {
        String typename = x.getType();
        int oid = connection.getTypeInfo().getPGType(typename);
        if (oid == Oid.UNSPECIFIED)
            throw new PSQLException(GT.tr("Unknown type {0}.", typename), PSQLState.INVALID_PARAMETER_TYPE);

        if ((x instanceof PGBinaryObject) && connection.binaryTransferSend(oid)) {
            PGBinaryObject binObj = (PGBinaryObject) x;
            byte[] data = new byte[binObj.lengthInBytes()];
            binObj.toBytes(data, 0);
            bindBytes(parameterIndex, data, oid);
        } else {
            setString(parameterIndex, x.getValue(), oid);
        }
    }
    
    private void setMap(int parameterIndex, Map x) throws SQLException {
        int oid = connection.getTypeInfo().getPGType("hstore");
        if (oid == Oid.UNSPECIFIED)
            throw new PSQLException(GT.tr("No hstore extension installed."), PSQLState.INVALID_PARAMETER_TYPE);
        if (connection.binaryTransferSend(oid)) {
            byte[] data = HStoreConverter.toBytes(x, connection.getEncoding());
            bindBytes(parameterIndex, data, oid);
        } else {
            setString(parameterIndex, HStoreConverter.toString(x), oid);
        }
    }

    /*
     * Set the value of a parameter using an object; use the java.lang
     * equivalent objects for integral values.
     *
     * <P>The given Java object will be converted to the targetSqlType before
     * being sent to the database.
     *
     * <P>note that this method may be used to pass database-specific
     * abstract data types.  This is done by using a Driver-specific
     * Java type and using a targetSqlType of java.sql.Types.OTHER
     *
     * @param parameterIndex the first parameter is 1...
     * @param x the object containing the input parameter value
     * @param targetSqlType The SQL type to be send to the database
     * @param scale For java.sql.Types.DECIMAL or java.sql.Types.NUMERIC
     *   * types this is the number of digits after the decimal.  For
     *   * all other types this value will be ignored.
     * @exception SQLException if a database access error occurs
     */
    public void setObject(int parameterIndex, Object in, int targetSqlType, int scale) throws SQLException
    {
        checkClosed();

        if (in == null)
        {
            setNull(parameterIndex, targetSqlType);
            return ;
        }

            switch (targetSqlType)
        {
            case Types.INTEGER:
                setInt(parameterIndex, castToInt(in));
                break;
            case Types.TINYINT:
            case Types.SMALLINT:
                setShort(parameterIndex, castToShort(in));
                break;
            case Types.BIGINT:
                setLong(parameterIndex, castToLong(in));
                break;
            case Types.REAL:
                setFloat(parameterIndex, castToFloat(in));
                break;
            case Types.DOUBLE:
            case Types.FLOAT:
                setDouble(parameterIndex, castToDouble(in));
                break;
            case Types.DECIMAL:
            case Types.NUMERIC:
                setBigDecimal(parameterIndex, castToBigDecimal(in, scale));
                break;
            case Types.CHAR:
                setString(parameterIndex, castToString(in), Oid.BPCHAR);
                break;
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
                setString(parameterIndex, castToString(in), getStringType());
                break;
            case Types.DATE:
                if (in instanceof java.sql.Date)
                    setDate(parameterIndex, (java.sql.Date)in);
                else
                {
                    java.sql.Date tmpd;
                    if (in instanceof java.util.Date) {
                        tmpd = new java.sql.Date(((java.util.Date)in).getTime());
                    } else {
                        tmpd = connection.getTimestampUtils().toDate(null, in.toString());
                    }
                    setDate(parameterIndex, tmpd);
                }
                break;
            case Types.TIME:
                if (in instanceof java.sql.Time)
                    setTime(parameterIndex, (java.sql.Time)in);
                else
                {
                    java.sql.Time tmpt;
                    if (in instanceof java.util.Date) {
                        tmpt = new java.sql.Time(((java.util.Date)in).getTime());
                    } else {
                        tmpt = connection.getTimestampUtils().toTime(null, in.toString());
                    }
                    setTime(parameterIndex, tmpt);
                }
                break;
            case Types.TIMESTAMP:
                if (in instanceof java.sql.Timestamp)
                    setTimestamp(parameterIndex , (java.sql.Timestamp)in);
                else
                {
                    java.sql.Timestamp tmpts;
                    if (in instanceof java.util.Date) {
                        tmpts = new java.sql.Timestamp(((java.util.Date)in).getTime());
                    } else {
                        tmpts = connection.getTimestampUtils().toTimestamp(null, in.toString());
                    }
                    setTimestamp(parameterIndex, tmpts);
                }
                break;
            case Types.BIT:
                setBoolean(parameterIndex, castToBoolean(in));
                break;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                setObject(parameterIndex, in);
                break;
            case Types.BLOB:
                if (in instanceof Blob)
                {
                    setBlob(parameterIndex, (Blob)in);
                }
                else if (in instanceof InputStream)
                {
                    long oid = createBlob(parameterIndex, (InputStream) in, -1);
                    setLong(parameterIndex, oid);
                }
                else
                {
                    throw new PSQLException(GT.tr("Cannot cast an instance of {0} to type {1}", new Object[]{in.getClass().getName(),"Types.BLOB"}), PSQLState.INVALID_PARAMETER_TYPE);
                }
                break;
            case Types.CLOB:
                if (in instanceof Clob)
                    setClob(parameterIndex, (Clob)in);
                else
                    throw new PSQLException(GT.tr("Cannot cast an instance of {0} to type {1}", new Object[]{in.getClass().getName(),"Types.CLOB"}), PSQLState.INVALID_PARAMETER_TYPE);
                break;
            case Types.ARRAY:
                if (in instanceof Array)
                    setArray(parameterIndex, (Array)in);
                else
                    throw new PSQLException(GT.tr("Cannot cast an instance of {0} to type {1}", new Object[]{in.getClass().getName(),"Types.ARRAY"}), PSQLState.INVALID_PARAMETER_TYPE);
                break;
            case Types.DISTINCT:
                bindString(parameterIndex, in.toString(), Oid.UNSPECIFIED);
                break;
            case Types.OTHER:
                if (in instanceof PGobject)
                    setPGobject(parameterIndex, (PGobject)in);
                else
                    bindString(parameterIndex, in.toString(), Oid.UNSPECIFIED);
                break;
            default:
                throw new PSQLException(GT.tr("Unsupported Types value: {0}", targetSqlType), PSQLState.INVALID_PARAMETER_TYPE);
        }
    }

    private static String asString(final Clob in) throws SQLException {
        return in.getSubString(1, (int) in.length());
    }

    private static int castToInt(final Object in) throws SQLException {
        try {
            if (in instanceof String)
                return Integer.parseInt((String) in);
            if (in instanceof Number)
                return ((Number) in).intValue();
            if (in instanceof java.util.Date)
                return (int) ((java.util.Date) in).getTime();
            if (in instanceof Boolean)
                return (Boolean) in ? 1 : 0;
            if (in instanceof Clob)
                return Integer.parseInt(asString((Clob) in));
            if (in instanceof Character)
                return Integer.parseInt(in.toString());
        } catch (final Exception e) {
            throw cannotCastException(in.getClass().getName(), "int", e);
        }
        throw cannotCastException(in.getClass().getName(), "int");
    }

    private static short castToShort(final Object in) throws SQLException {
        try {
            if (in instanceof String)
                return Short.parseShort((String) in);
            if (in instanceof Number)
                return ((Number) in).shortValue();
            if (in instanceof java.util.Date)
                return (short) ((java.util.Date) in).getTime();
            if (in instanceof Boolean)
                return (Boolean) in ? (short) 1 : (short) 0;
            if (in instanceof Clob)
                return Short.parseShort(asString((Clob) in));
            if (in instanceof Character)
                return Short.parseShort(in.toString());
        } catch (final Exception e) {
            throw cannotCastException(in.getClass().getName(), "short", e);
        }
        throw cannotCastException(in.getClass().getName(), "short");
    }

    private static long castToLong(final Object in) throws SQLException {
        try {
            if (in instanceof String)
                return Long.parseLong((String) in);
            if (in instanceof Number)
                return ((Number) in).longValue();
            if (in instanceof java.util.Date)
                return ((java.util.Date) in).getTime();
            if (in instanceof Boolean)
                return (Boolean) in ? 1L : 0L;
            if (in instanceof Clob)
                return Long.parseLong(asString((Clob) in));
            if (in instanceof Character)
                return Long.parseLong(in.toString());
        } catch (final Exception e) {
            throw cannotCastException(in.getClass().getName(), "long", e);
        }
        throw cannotCastException(in.getClass().getName(), "long");
    }

    private static float castToFloat(final Object in) throws SQLException {
        try {
            if (in instanceof String)
                return Float.parseFloat((String) in);
            if (in instanceof Number)
                return ((Number) in).floatValue();
            if (in instanceof java.util.Date)
                return ((java.util.Date) in).getTime();
            if (in instanceof Boolean)
                return (Boolean) in ? 1f : 0f;
            if (in instanceof Clob)
                return Float.parseFloat(asString((Clob) in));
            if (in instanceof Character)
                return Float.parseFloat(in.toString());
        } catch (final Exception e) {
            throw cannotCastException(in.getClass().getName(), "float", e);
        }
        throw cannotCastException(in.getClass().getName(), "float");
    }

    private static double castToDouble(final Object in) throws SQLException {
        try {
            if (in instanceof String)
                return Double.parseDouble((String) in);
            if (in instanceof Number)
                return ((Number) in).doubleValue();
            if (in instanceof java.util.Date)
                return ((java.util.Date) in).getTime();
            if (in instanceof Boolean)
                return (Boolean) in ? 1d : 0d;
            if (in instanceof Clob)
                return Double.parseDouble(asString((Clob) in));
            if (in instanceof Character)
                return Double.parseDouble(in.toString());
        } catch (final Exception e) {
            throw cannotCastException(in.getClass().getName(), "double", e);
        }
        throw cannotCastException(in.getClass().getName(), "double");
    }

    private static BigDecimal castToBigDecimal(final Object in, final int scale) throws SQLException {
        try {
            BigDecimal rc=null;
            if (in instanceof String)
                rc=new BigDecimal((String) in);
            else if (in instanceof BigDecimal)
                rc=((BigDecimal) in);
            else if (in instanceof BigInteger)
                rc=new BigDecimal((BigInteger) in);
            else if (in instanceof Long || in instanceof Integer || in instanceof Short || in instanceof Byte)
                rc=BigDecimal.valueOf(((Number) in).longValue());
            else if (in instanceof Double || in instanceof Float)
                rc=BigDecimal.valueOf(((Number) in).doubleValue());
            else if (in instanceof java.util.Date)
                rc=BigDecimal.valueOf(((java.util.Date) in).getTime());
            else if (in instanceof Boolean)
                rc=(Boolean) in ? BigDecimal.ONE : BigDecimal.ZERO;
            else if (in instanceof Clob)
                rc=new BigDecimal(asString((Clob) in));
            else if (in instanceof Character)
                rc=new BigDecimal(new char[] {(Character) in});
            if (rc!=null) {
                if (scale>=0) {
                    rc=rc.setScale(scale,RoundingMode.HALF_UP);
                }
                return rc;
            }
        } catch (final Exception e) {
            throw cannotCastException(in.getClass().getName(), "BigDecimal", e);
        }
        throw cannotCastException(in.getClass().getName(), "BigDecimal");
    }

    private static boolean castToBoolean(final Object in) throws SQLException {
        try {
            if (in instanceof String)
                return ((String) in).equalsIgnoreCase("true") || ((String) in).equals("1") || ((String) in).equalsIgnoreCase("t");
            if (in instanceof BigDecimal)
                return ((BigDecimal) in).signum() != 0;
            if (in instanceof Number)
                return ((Number) in).longValue() != 0L;
            if (in instanceof java.util.Date)
                return ((java.util.Date) in).getTime() != 0L;
            if (in instanceof Boolean)
                return (Boolean) in;
            if (in instanceof Clob) {
                final String asString = asString((Clob) in);
                return asString.equalsIgnoreCase("true") || asString.equals("1") || asString.equalsIgnoreCase("t");
            }
            if (in instanceof Character)
                return (Character) in == '1' || (Character) in == 't' || (Character) in == 'T';
        } catch (final Exception e) {
            throw cannotCastException(in.getClass().getName(), "boolean", e);
        }
        throw cannotCastException(in.getClass().getName(), "boolean");
    }

    private static String castToString(final Object in) throws SQLException {
        try {
            if (in instanceof String)
                return (String) in;
            if (in instanceof Number || in instanceof Boolean || in instanceof Character || in instanceof java.util.Date)
                return in.toString();
            if (in instanceof Clob)
                return asString((Clob) in);
        } catch (final Exception e) {
            throw cannotCastException(in.getClass().getName(), "String", e);
        }
        throw cannotCastException(in.getClass().getName(), "String");
    }

    private static PSQLException cannotCastException(final String fromType, final String toType) {
        return cannotCastException(fromType, toType, null);
    }

    private static PSQLException cannotCastException(final String fromType, final String toType, final Exception cause) {
        return new PSQLException(GT.tr("Cannot convert an instance of {0} to type {1}", new Object[] { fromType, toType }), PSQLState.INVALID_PARAMETER_TYPE, cause);
    }

    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException
    {
        setObject(parameterIndex, x, targetSqlType, -1);
    }

    /*
     * This stores an Object into a parameter.
     */
    public void setObject(int parameterIndex, Object x) throws SQLException
    {
        checkClosed();
        if (x == null)
            setNull(parameterIndex, Types.OTHER);
        else if (x instanceof String)
            setString(parameterIndex, (String)x);
        else if (x instanceof BigDecimal)
            setBigDecimal(parameterIndex, (BigDecimal)x);
        else if (x instanceof Short)
            setShort(parameterIndex, (Short) x);
        else if (x instanceof Integer)
            setInt(parameterIndex, (Integer) x);
        else if (x instanceof Long)
            setLong(parameterIndex, (Long) x);
        else if (x instanceof Float)
            setFloat(parameterIndex, (Float) x);
        else if (x instanceof Double)
            setDouble(parameterIndex, (Double) x);
        else if (x instanceof byte[])
            setBytes(parameterIndex, (byte[])x);
        else if (x instanceof java.sql.Date)
            setDate(parameterIndex, (java.sql.Date)x);
        else if (x instanceof Time)
            setTime(parameterIndex, (Time)x);
        else if (x instanceof Timestamp)
            setTimestamp(parameterIndex, (Timestamp)x);
        else if (x instanceof Boolean)
            setBoolean(parameterIndex, (Boolean) x);
        else if (x instanceof Byte)
            setByte(parameterIndex, (Byte) x);
        else if (x instanceof Blob)
            setBlob(parameterIndex, (Blob)x);
        else if (x instanceof Clob)
            setClob(parameterIndex, (Clob)x);
        else if (x instanceof Array)
            setArray(parameterIndex, (Array)x);
        else if (x instanceof PGobject)
            setPGobject(parameterIndex, (PGobject)x);
        else if (x instanceof Character)
            setString(parameterIndex, ((Character)x).toString());
        else if (x instanceof Map)
            setMap(parameterIndex, (Map)x);
        else
        {
            // Can't infer a type.
            throw new PSQLException(GT.tr("Can''t infer the SQL type to use for an instance of {0}. Use setObject() with an explicit Types value to specify the type to use.", x.getClass().getName()), PSQLState.INVALID_PARAMETER_TYPE);
        }
    }

    /*
     * Before executing a stored procedure call you must explicitly
     * call registerOutParameter to register the java.sql.Type of each
     * out parameter.
     *
     * <p>Note: When reading the value of an out parameter, you must use
     * the getXXX method whose Java type XXX corresponds to the
     * parameter's registered SQL type.
     *
     * ONLY 1 RETURN PARAMETER if {?= call ..} syntax is used
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @param sqlType SQL type code defined by java.sql.Types; for
     * parameters of type Numeric or Decimal use the version of
     * registerOutParameter that accepts a scale value
     * @exception SQLException if a database-access error occurs.
     */
    public void registerOutParameter(int parameterIndex, int sqlType, boolean setPreparedParameters) throws SQLException
    {
        checkClosed();
        switch( sqlType )
        {
	        case Types.TINYINT:
			    // we don't have a TINYINT type use SMALLINT
			    	sqlType = Types.SMALLINT;
			    	break;
			case Types.LONGVARCHAR:
			    sqlType = Types.VARCHAR;
				break;
			case Types.DECIMAL:
			    sqlType = Types.NUMERIC;
				break;
			case Types.FLOAT:
			    // float is the same as double
			    sqlType = Types.DOUBLE;
				break;
			case Types.VARBINARY:
			case Types.LONGVARBINARY:
			    sqlType = Types.BINARY;
				break;
			default:
			    break;
        }
        if (!isFunction)
            throw new PSQLException (GT.tr("This statement does not declare an OUT parameter.  Use '{' ?= call ... '}' to declare one."), PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
        checkIndex(parameterIndex, false);
        
        if( setPreparedParameters )
            preparedParameters.registerOutParameter( parameterIndex, sqlType );
        // functionReturnType contains the user supplied value to check
        // testReturn contains a modified version to make it easier to
        // check the getXXX methods..
        functionReturnType[parameterIndex-1] = sqlType;
        testReturn[parameterIndex-1] = sqlType;
        
        if (functionReturnType[parameterIndex-1] == Types.CHAR ||
                functionReturnType[parameterIndex-1] == Types.LONGVARCHAR)
            testReturn[parameterIndex-1] = Types.VARCHAR;
        else if (functionReturnType[parameterIndex-1] == Types.FLOAT)
            testReturn[parameterIndex-1] = Types.REAL; // changes to streamline later error checking
        returnTypeSet = true;
    }

    /*
     * You must also specify the scale for numeric/decimal types:
     *
     * <p>Note: When reading the value of an out parameter, you must use
     * the getXXX method whose Java type XXX corresponds to the
     * parameter's registered SQL type.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @param sqlType use either java.sql.Type.NUMERIC or java.sql.Type.DECIMAL
     * @param scale a value greater than or equal to zero representing the
     * desired number of digits to the right of the decimal point
     * @exception SQLException if a database-access error occurs.
     */
    public void registerOutParameter(int parameterIndex, int sqlType,
                                     int scale, boolean setPreparedParameters) throws SQLException
    {
        registerOutParameter (parameterIndex, sqlType, setPreparedParameters); // ignore for now..
    }

    /*
     * An OUT parameter may have the value of SQL NULL; wasNull
     * reports whether the last value read has this special value.
     *
     * <p>Note: You must first call getXXX on a parameter to read its
     * value and then call wasNull() to see if the value was SQL NULL.
     * @return true if the last parameter read was SQL NULL
     * @exception SQLException if a database-access error occurs.
     */
    public boolean wasNull() throws SQLException
    {
        if (lastIndex == 0)
            throw new PSQLException(GT.tr("wasNull cannot be call before fetching a result."), PSQLState.OBJECT_NOT_IN_STATE);

        // check to see if the last access threw an exception
        return (callResult[lastIndex-1] == null);
    }

    /*
     * Get the value of a CHAR, VARCHAR, or LONGVARCHAR parameter as a
     * Java String.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is null
     * @exception SQLException if a database-access error occurs.
     */
    public String getString(int parameterIndex) throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.VARCHAR, "String");
        return (String)callResult[parameterIndex-1];
    }


    /*
     * Get the value of a BIT parameter as a Java boolean.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is false
     * @exception SQLException if a database-access error occurs.
     */
    public boolean getBoolean(int parameterIndex) throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.BIT, "Boolean");
        if (callResult[parameterIndex-1] == null)
            return false;
        
        return (Boolean) callResult[parameterIndex - 1];
    }

    /*
     * Get the value of a TINYINT parameter as a Java byte.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is 0
     * @exception SQLException if a database-access error occurs.
     */
    public byte getByte(int parameterIndex) throws SQLException
    {
        checkClosed();
        // fake tiny int with smallint
        checkIndex (parameterIndex, Types.SMALLINT, "Byte");
        
        if (callResult[parameterIndex-1] == null)
            return 0;
        
        return ((Integer)callResult[parameterIndex-1]).byteValue();
  
    }

    /*
     * Get the value of a SMALLINT parameter as a Java short.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is 0
     * @exception SQLException if a database-access error occurs.
     */
    public short getShort(int parameterIndex) throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.SMALLINT, "Short");
        if (callResult[parameterIndex-1] == null)
            return 0;
        return ((Integer)callResult[parameterIndex-1]).shortValue ();
    }


    /*
     * Get the value of an INTEGER parameter as a Java int.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is 0
     * @exception SQLException if a database-access error occurs.
     */
    public int getInt(int parameterIndex) throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.INTEGER, "Int");
        if (callResult[parameterIndex-1] == null)
            return 0;
        
        return (Integer) callResult[parameterIndex - 1];
    }

    /*
     * Get the value of a BIGINT parameter as a Java long.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is 0
     * @exception SQLException if a database-access error occurs.
     */
    public long getLong(int parameterIndex) throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.BIGINT, "Long");
        if (callResult[parameterIndex-1] == null)
            return 0;
        
        return (Long) callResult[parameterIndex - 1];
    }

    /*
     * Get the value of a FLOAT parameter as a Java float.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is 0
     * @exception SQLException if a database-access error occurs.
     */
    public float getFloat(int parameterIndex) throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.REAL, "Float");
        if (callResult[parameterIndex-1] == null)
            return 0;

        return (Float) callResult[parameterIndex - 1];
    }

    /*
     * Get the value of a DOUBLE parameter as a Java double.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is 0
     * @exception SQLException if a database-access error occurs.
     */
    public double getDouble(int parameterIndex) throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.DOUBLE, "Double");
        if (callResult[parameterIndex-1] == null)
            return 0;
        
        return (Double) callResult[parameterIndex - 1];
    }

    /*
     * Get the value of a NUMERIC parameter as a java.math.BigDecimal
     * object.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @param scale a value greater than or equal to zero representing the
     * desired number of digits to the right of the decimal point
     * @return the parameter value; if the value is SQL NULL, the result is null
     * @exception SQLException if a database-access error occurs.
     * @deprecated in Java2.0
     */
    public BigDecimal getBigDecimal(int parameterIndex, int scale)
    throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.NUMERIC, "BigDecimal");
        return ((BigDecimal)callResult[parameterIndex-1]);
    }

    /*
     * Get the value of a SQL BINARY or VARBINARY parameter as a Java
     * byte[]
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is null
     * @exception SQLException if a database-access error occurs.
     */
    public byte[] getBytes(int parameterIndex) throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.VARBINARY, Types.BINARY, "Bytes");
        return ((byte [])callResult[parameterIndex-1]);
    }


    /*
     * Get the value of a SQL DATE parameter as a java.sql.Date object
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is null
     * @exception SQLException if a database-access error occurs.
     */
    public java.sql.Date getDate(int parameterIndex) throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.DATE, "Date");     
        return (java.sql.Date)callResult[parameterIndex-1];
    }

    /*
     * Get the value of a SQL TIME parameter as a java.sql.Time object.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is null
     * @exception SQLException if a database-access error occurs.
     */
    public java.sql.Time getTime(int parameterIndex) throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.TIME, "Time");
        return (java.sql.Time)callResult[parameterIndex-1];
    }

    /*
     * Get the value of a SQL TIMESTAMP parameter as a java.sql.Timestamp object.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return the parameter value; if the value is SQL NULL, the result is null
     * @exception SQLException if a database-access error occurs.
     */
    public java.sql.Timestamp getTimestamp(int parameterIndex)
    throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.TIMESTAMP, "Timestamp");
        return (java.sql.Timestamp)callResult[parameterIndex-1];
    }

    // getObject returns a Java object for the parameter.
    // See the JDBC spec's "Dynamic Programming" chapter for details.
    /*
     * Get the value of a parameter as a Java object.
     *
     * <p>This method returns a Java object whose type coresponds to the
     * SQL type that was registered for this parameter using
     * registerOutParameter.
     *
     * <P>Note that this method may be used to read datatabase-specific,
     * abstract data types. This is done by specifying a targetSqlType
     * of java.sql.types.OTHER, which allows the driver to return a
     * database-specific Java type.
     *
     * <p>See the JDBC spec's "Dynamic Programming" chapter for details.
     *
     * @param parameterIndex the first parameter is 1, the second is 2,...
     * @return A java.lang.Object holding the OUT parameter value.
     * @exception SQLException if a database-access error occurs.
     */
    public Object getObject(int parameterIndex)
    throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex);        
        return callResult[parameterIndex-1];
    }

    /*
     * Returns the SQL statement with the current template values
     * substituted.
     */
    public String toString()
    {
        if (preparedQuery == null)
            return super.toString();

        return preparedQuery.query.toString(preparedParameters);
    }


    /*
        * Note if s is a String it should be escaped by the caller to avoid SQL
        * injection attacks.  It is not done here for efficency reasons as
        * most calls to this method do not require escaping as the source
        * of the string is known safe (i.e. Integer.toString())
     */
    protected void bindLiteral(int paramIndex, String s, int oid) throws SQLException
    {
        if(adjustIndex)
            paramIndex--;
        preparedParameters.setLiteralParameter(paramIndex, s, oid);
    }

    protected void bindBytes(int paramIndex, byte[] b, int oid) throws SQLException
    {
        if(adjustIndex)
            paramIndex--;
        preparedParameters.setBinaryParameter(paramIndex, b, oid);
    }

    /*
     * This version is for values that should turn into strings
     * e.g. setString directly calls bindString with no escaping;
     * the per-protocol ParameterList does escaping as needed.
     */
    private void bindString(int paramIndex, String s, int oid) throws SQLException
    {
        if (adjustIndex)
            paramIndex--;
        preparedParameters.setStringParameter( paramIndex, s, oid);
    }

    /** helperfunction for the getXXX calls to check isFunction and index == 1
     * Compare BOTH type fields against the return type.
     */
    protected void checkIndex (int parameterIndex, int type1, int type2, String getName)
    throws SQLException
    {
        checkIndex (parameterIndex);
        if (type1 != this.testReturn[parameterIndex-1] && type2 != this.testReturn[parameterIndex-1])
            throw new PSQLException(GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
                                          new Object[]{"java.sql.Types=" + testReturn[parameterIndex-1],
                                                       getName,
                                                       "java.sql.Types=" + type1}),
                                    PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
    }

    /** helperfunction for the getXXX calls to check isFunction and index == 1
     */
    protected void checkIndex (int parameterIndex, int type, String getName)
    throws SQLException
    {
        checkIndex (parameterIndex);
        if (type != this.testReturn[parameterIndex-1])
            throw new PSQLException(GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
                                          new Object[]{"java.sql.Types=" + testReturn[parameterIndex-1],
                                                       getName,
                                                       "java.sql.Types=" + type}),
                                    PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
    }

    private void checkIndex (int parameterIndex) throws SQLException
    {
        checkIndex(parameterIndex, true);
    }

    /** helperfunction for the getXXX calls to check isFunction and index == 1
     * @param parameterIndex index of getXXX (index)
     * check to make sure is a function and index == 1
     */
    private void checkIndex (int parameterIndex, boolean fetchingData) throws SQLException
    {
        if (!isFunction)
            throw new PSQLException(GT.tr("A CallableStatement was declared, but no call to registerOutParameter(1, <some type>) was made."), PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);

        if (fetchingData) {
            if (!returnTypeSet)
                throw new PSQLException(GT.tr("No function outputs were registered."), PSQLState.OBJECT_NOT_IN_STATE);

            if (callResult == null)
                throw new PSQLException(GT.tr("Results cannot be retrieved from a CallableStatement before it is executed."), PSQLState.NO_DATA);

            lastIndex = parameterIndex;
        }
    }

    
    public void setPrepareThreshold(int newThreshold) throws SQLException {
        checkClosed();
       
        if (newThreshold < 0) {
            forceBinaryTransfers = true;
            newThreshold = 1;
        }

        this.m_prepareThreshold = newThreshold;
    }

    public int getPrepareThreshold() {
        return m_prepareThreshold;
    }

    public void setUseServerPrepare(boolean flag) throws SQLException {
        setPrepareThreshold(flag ? 1 : 0);
    }

    public boolean isUseServerPrepare() {
        return (preparedQuery != null && m_prepareThreshold != 0 && preparedQuery.getExecuteCount() + 1 >= m_prepareThreshold);
    }

    protected void checkClosed() throws SQLException
    {
        if (isClosed)
            throw new PSQLException(GT.tr("This statement has been closed."),
                                    PSQLState.OBJECT_NOT_IN_STATE);
    }

    // ** JDBC 2 Extensions **

    public void addBatch(String p_sql) throws SQLException
    {
        checkClosed();

        if (preparedQuery != null)
            throw new PSQLException(GT.tr("Can''t use query methods that take a query string on a PreparedStatement."),
                                    PSQLState.WRONG_OBJECT_TYPE);

        if (batchStatements == null)
        {
            batchStatements = new ArrayList<Query>();
            batchParameters = new ArrayList<ParameterList>();
        }

        p_sql = replaceProcessing(p_sql, replaceProcessingEnabled, connection.getStandardConformingStrings());

        batchStatements.add(connection.getQueryExecutor().createSimpleQuery(p_sql));
        batchParameters.add(null);
    }

    public void clearBatch() throws SQLException
    {
        if (batchStatements != null)
        {
            batchStatements.clear();
            batchParameters.clear();
        }
    }

    //
    // ResultHandler for batch queries.
    //

    private class BatchResultHandler implements ResultHandler {
        private BatchUpdateException batchException = null;
        private int resultIndex = 0;

        private final Query[] queries;
        private final ParameterList[] parameterLists;
        private final int[] updateCounts;
        private final boolean expectGeneratedKeys;
        private ResultSet generatedKeys;

        BatchResultHandler(Query[] queries, ParameterList[] parameterLists, int[] updateCounts, boolean expectGeneratedKeys) {
            this.queries = queries;
            this.parameterLists = parameterLists;
            this.updateCounts = updateCounts;
            this.expectGeneratedKeys = expectGeneratedKeys;
        }

        public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
            if (!expectGeneratedKeys) {
                handleError(new PSQLException(GT.tr("A result was returned when none was expected."),
                                          PSQLState.TOO_MANY_RESULTS));
            } else {
                if (generatedKeys == null) {
                    try
                    {
                        generatedKeys = AbstractJdbc2Statement.this.createResultSet(fromQuery, fields, tuples, cursor);
                    }
                    catch (SQLException e)
                    {
                        handleError(e);
            
                    }
                } else {
                    ((AbstractJdbc2ResultSet)generatedKeys).addRows(tuples);
                }
            }
        }

        public void handleCommandStatus(String status, int updateCount, long insertOID) {
            if (resultIndex >= updateCounts.length)
            {
                handleError(new PSQLException(GT.tr("Too many update results were returned."),
                                              PSQLState.TOO_MANY_RESULTS));
                return ;
            }

            updateCounts[resultIndex++] = updateCount;
        }

        public void handleWarning(SQLWarning warning) {
            AbstractJdbc2Statement.this.addWarning(warning);
        }

        public void handleError(SQLException newError) {
            if (batchException == null)
            {
                int[] successCounts;

                if (resultIndex >= updateCounts.length)
                    successCounts = updateCounts;
                else
                {
                    successCounts = new int[resultIndex];
                    System.arraycopy(updateCounts, 0, successCounts, 0, resultIndex);
                }

                String queryString = "<unknown>";
                if (resultIndex < queries.length)
                    queryString = queries[resultIndex].toString(parameterLists[resultIndex]);

                batchException = new BatchUpdateException(GT.tr("Batch entry {0} {1} was aborted.  Call getNextException to see the cause.",
                                 new Object[]{resultIndex,
                                               queryString}),
                                 newError.getSQLState(),
                                 successCounts);
            }

            batchException.setNextException(newError);
        }

        public void handleCompletion() throws SQLException {
            if (batchException != null)
                throw batchException;
        }

        public ResultSet getGeneratedKeys() {
            return generatedKeys;
        }
    }
    private class CallableBatchResultHandler implements ResultHandler {
        private BatchUpdateException batchException = null;
        private int resultIndex = 0;

        private final Query[] queries;
        private final ParameterList[] parameterLists;
        private final int[] updateCounts;

        CallableBatchResultHandler(Query[] queries, ParameterList[] parameterLists, int[] updateCounts) {
            this.queries = queries;
            this.parameterLists = parameterLists;
            this.updateCounts = updateCounts;
        }

        public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) 
        {
            
        }

        public void handleCommandStatus(String status, int updateCount, long insertOID) {
            if (resultIndex >= updateCounts.length)
            {
                handleError(new PSQLException(GT.tr("Too many update results were returned."),
                                              PSQLState.TOO_MANY_RESULTS));
                return ;
            }

            updateCounts[resultIndex++] = updateCount;
        }

        public void handleWarning(SQLWarning warning) {
            AbstractJdbc2Statement.this.addWarning(warning);
        }

        public void handleError(SQLException newError) {
            if (batchException == null)
            {
                int[] successCounts;

                if (resultIndex >= updateCounts.length)
                    successCounts = updateCounts;
                else
                {
                    successCounts = new int[resultIndex];
                    System.arraycopy(updateCounts, 0, successCounts, 0, resultIndex);
                }

                String queryString = "<unknown>";
                if (resultIndex < queries.length)
                    queryString = queries[resultIndex].toString(parameterLists[resultIndex]);

                batchException = new BatchUpdateException(GT.tr("Batch entry {0} {1} was aborted.  Call getNextException to see the cause.",
                                 new Object[]{resultIndex,
                                               queryString}),
                                 newError.getSQLState(),
                                 successCounts);
            }

            batchException.setNextException(newError);
        }

        public void handleCompletion() throws SQLException {
            if (batchException != null)
                throw batchException;
        }
    }

    
    public int[] executeBatch() throws SQLException
    {
        checkClosed();

        closeForNextExecution();

        if (batchStatements == null || batchStatements.isEmpty())
            return new int[0];

        int size = batchStatements.size();
        int[] updateCounts = new int[size];

        // Construct query/parameter arrays.
        Query[] queries = batchStatements.toArray(new Query[batchStatements.size()]);
        ParameterList[] parameterLists = batchParameters.toArray(new ParameterList[batchParameters.size()]);
        batchStatements.clear();
        batchParameters.clear();

        int flags = 0;

        // Force a Describe before any execution? We need to do this if we're going
        // to send anything dependent on the Desribe results, e.g. binary parameters.
        boolean preDescribe = false;

        if (wantsGeneratedKeysAlways) {
            /*
             * This batch will return generated keys, tell the executor to
             * expect result rows. We also force a Describe later so we know
             * the size of the results to expect.
             *
             * If the parameter type(s) change between batch entries and the
             * default binary-mode changes we might get mixed binary and text
             * in a single result set column, which we cannot handle. To prevent
             * this, disable binary transfer mode in batches that return generated
             * keys. See GitHub issue #267
             */
            flags = QueryExecutor.QUERY_BOTH_ROWS_AND_STATUS
                    | QueryExecutor.QUERY_NO_BINARY_TRANSFER;
        } else {
            // If a batch hasn't specified that it wants generated keys, using the appropriate
            // Connection.createStatement(...) interfaces, disallow any result set.
            flags = QueryExecutor.QUERY_NO_RESULTS;
        }

        // Only use named statements after we hit the threshold
        if (preparedQuery != null)
        {
            preparedQuery.increaseExecuteCount(queries.length);
        }
        if (m_prepareThreshold == 0 || preparedQuery == null || preparedQuery.getExecuteCount() < m_prepareThreshold) {
            flags |= QueryExecutor.QUERY_ONESHOT;
        } else {
            // If a batch requests generated keys and isn't already described,
            // force a Describe of the query before proceeding. That way we can
            // determine the appropriate size of each batch by estimating the
            // maximum data returned. Without that, we don't know how many queries
            // we'll be able to queue up before we risk a deadlock.
            // (see v3.QueryExecutorImpl's MAX_BUFFERED_RECV_BYTES)
            preDescribe = wantsGeneratedKeysAlways && !queries[0].isStatementDescribed();
            /*
             * It's also necessary to force a Describe on the first execution of the
             * new statement, even though we already described it, to work around
             * bug #267.
             */
            flags |= QueryExecutor.QUERY_FORCE_DESCRIBE_PORTAL;
        }

        if (connection.getAutoCommit())
            flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;

        if (preDescribe || forceBinaryTransfers) {
            // Do a client-server round trip, parsing and describing the query so we
            // can determine its result types for use in binary parameters, batch sizing,
            // etc.
            int flags2 = flags | QueryExecutor.QUERY_DESCRIBE_ONLY;
            StatementResultHandler handler2 = new StatementResultHandler();
            connection.getQueryExecutor().execute(queries[0], parameterLists[0], handler2, 0, 0, flags2);
            ResultWrapper result2 = handler2.getResults();
            if (result2 != null) {
                result2.getResultSet().close();
            }
        }

        result = null;
        
        ResultHandler handler;
	if (isFunction) {
		handler = new CallableBatchResultHandler(queries, parameterLists, updateCounts );
	} else {
		handler = new BatchResultHandler(queries, parameterLists, updateCounts, wantsGeneratedKeysAlways);
	}
        
	try {
	    startTimer();
	    connection.getQueryExecutor().execute(queries,
						  parameterLists,
						  handler,
						  maxrows,
						  fetchSize,
						  flags);
	} finally {
	    killTimerTask();
	}

        if (wantsGeneratedKeysAlways) {
            generatedKeys = new ResultWrapper(((BatchResultHandler)handler).getGeneratedKeys());
        }
            
        return updateCounts;
    }

    /*
     * Cancel can be used by one thread to cancel a statement that
     * is being executed by another thread.
     * <p>
     *
     * @exception SQLException only because thats the spec.
     */
    public void cancel() throws SQLException
    {
        if (!STATE_UPDATER.compareAndSet(this, STATE_IN_QUERY, STATE_CANCELLING))
        {
            // Not in query, there's nothing to cancel
            return;
        }
        try
        {
            // Synchronize on connection to avoid spinning in killTimerTask
            synchronized (connection)
            {
                connection.cancelQuery();
            }
        } finally
        {
            STATE_UPDATER.set(this, STATE_CANCELLED);
            synchronized (connection)
            {
                connection.notifyAll(); // wake-up killTimerTask
            }
        }
    }

    public Connection getConnection() throws SQLException
    {
        return (Connection) connection;
    }

    public int getFetchDirection()
    {
        return fetchdirection;
    }

    public int getResultSetConcurrency()
    {
        return concurrency;
    }

    public int getResultSetType()
    {
        return resultsettype;
    }

    public void setFetchDirection(int direction) throws SQLException
    {
        switch (direction)
        {
        case ResultSet.FETCH_FORWARD:
        case ResultSet.FETCH_REVERSE:
        case ResultSet.FETCH_UNKNOWN:
            fetchdirection = direction;
            break;
        default:
            throw new PSQLException(GT.tr("Invalid fetch direction constant: {0}.", direction),
                                    PSQLState.INVALID_PARAMETER_VALUE);
        }
    }

    public void setFetchSize(int rows) throws SQLException
    {
        checkClosed();
        if (rows < 0)
            throw new PSQLException(GT.tr("Fetch size must be a value greater to or equal to 0."),
                                    PSQLState.INVALID_PARAMETER_VALUE);
        fetchSize = rows;
    }

    public void addBatch() throws SQLException
    {
        checkClosed();

        if (batchStatements == null)
        {
            batchStatements = new ArrayList<Query>();
            batchParameters = new ArrayList<ParameterList>();
        }

        // we need to create copies of our parameters, otherwise the values can be changed
        batchStatements.add(preparedQuery.query);
        batchParameters.add(preparedParameters.copy());
    }

    public ResultSetMetaData getMetaData() throws SQLException
    {
        checkClosed();
        ResultSet rs = getResultSet();

        if (rs == null || ((AbstractJdbc2ResultSet)rs).isResultSetClosed() ) {
            // OK, we haven't executed it yet, or it was closed
            // we've got to go to the backend
            // for more info.  We send the full query, but just don't
            // execute it.

            int flags = QueryExecutor.QUERY_ONESHOT | QueryExecutor.QUERY_DESCRIBE_ONLY | QueryExecutor.QUERY_SUPPRESS_BEGIN;
            StatementResultHandler handler = new StatementResultHandler();
            connection.getQueryExecutor().execute(preparedQuery.query, preparedParameters, handler, 0, 0, flags);
            ResultWrapper wrapper = handler.getResults();
            if (wrapper != null) {
                rs = wrapper.getResultSet();
            }
        }

        if (rs != null)
            return rs.getMetaData();

        return null;
    }

    public void setArray(int i, java.sql.Array x) throws SQLException
    {
        checkClosed();

        if (null == x)
        {
            setNull(i, Types.ARRAY);
            return;
        }

        // This only works for Array implementations that return a valid array
        // literal from Array.toString(), such as the implementation we return
        // from ResultSet.getArray(). Eventually we need a proper implementation
        // here that works for any Array implementation.

        // Add special suffix for array identification
        String typename = x.getBaseTypeName() + "[]";
        int oid = connection.getTypeInfo().getPGType(typename);
        if (oid == Oid.UNSPECIFIED)
            throw new PSQLException(GT.tr("Unknown type {0}.", typename), PSQLState.INVALID_PARAMETER_TYPE);

        if (x instanceof AbstractJdbc2Array) {
            AbstractJdbc2Array arr = (AbstractJdbc2Array) x;
            if (arr.isBinary()) {
                bindBytes(i, arr.toBytes(), oid);
                return;
            }
        }
        
        setString(i, x.toString(), oid);
    }

    protected long createBlob(int i, InputStream inputStream, long length) throws SQLException
    {
        LargeObjectManager lom = connection.getLargeObjectAPI();
        long oid = lom.createLO();
        LargeObject lob = lom.open(oid);
        OutputStream outputStream = lob.getOutputStream();
        byte[] buf = new byte[4096];
        try
        {
            long remaining;
            if (length > 0)
            {
                remaining = length;
            }
            else
            {
                remaining = Long.MAX_VALUE;
            }
            int numRead = inputStream.read(buf, 0, (length > 0 && remaining < buf.length ? (int)remaining : buf.length));
            while (numRead != -1 && remaining > 0)
            {
                remaining -= numRead;
                outputStream.write(buf, 0, numRead);
                numRead = inputStream.read(buf, 0, (length > 0 && remaining < buf.length ? (int)remaining : buf.length));
            }
        }
        catch (IOException se)
        {
            throw new PSQLException(GT.tr("Unexpected error writing large object to database."), PSQLState.UNEXPECTED_ERROR, se);
        }
        finally
        {
            try
            {
                outputStream.close();
            }
            catch ( Exception e )
            {
            }
        }
        return oid;
    }

    public void setBlob(int i, Blob x) throws SQLException
    {
        checkClosed();

        if (x == null)
        {
            setNull(i, Types.BLOB);
            return;
        }

        InputStream inStream = x.getBinaryStream();
        try
        {
            long oid = createBlob(i, inStream, x.length());
            setLong(i, oid);
        }
        finally
        {
            try
            {
                inStream.close();
            }
            catch ( Exception e )
            {
            }
        }
    }

    public void setCharacterStream(int i, java.io.Reader x, int length) throws SQLException
    {
        checkClosed();

        if (x == null) {
            if (connection.haveMinimumServerVersion(ServerVersion.v7_2)) {
                setNull(i, Types.VARCHAR);
            } else {
                setNull(i, Types.CLOB);
            }
            return;
        }

        if (length < 0)
            throw new PSQLException(GT.tr("Invalid stream length {0}.", length),
                                    PSQLState.INVALID_PARAMETER_VALUE);

        if (connection.haveMinimumCompatibleVersion(ServerVersion.v7_2))
        {
            //Version 7.2 supports CharacterStream for for the PG text types
            //As the spec/javadoc for this method indicate this is to be used for
            //large text values (i.e. LONGVARCHAR) PG doesn't have a separate
            //long varchar datatype, but with toast all the text datatypes are capable of
            //handling very large values.  Thus the implementation ends up calling
            //setString() since there is no current way to stream the value to the server
            char[] l_chars = new char[length];
            int l_charsRead = 0;
            try
            {
                while (true)
                {
                    int n = x.read(l_chars, l_charsRead, length - l_charsRead);
                    if (n == -1)
                        break;

                    l_charsRead += n;

                    if (l_charsRead == length)
                        break;
                }
            }
            catch (IOException l_ioe)
            {
                throw new PSQLException(GT.tr("Provided Reader failed."), PSQLState.UNEXPECTED_ERROR, l_ioe);
            }
            setString(i, new String(l_chars, 0, l_charsRead));
        }
        else
        {
            //Version 7.1 only supported streams for LargeObjects
            //but the jdbc spec indicates that streams should be
            //available for LONGVARCHAR instead
            LargeObjectManager lom = connection.getLargeObjectAPI();
            long oid = lom.createLO();
            LargeObject lob = lom.open(oid);
            OutputStream los = lob.getOutputStream();
            try
            {
                // could be buffered, but then the OutputStream returned by LargeObject
                // is buffered internally anyhow, so there would be no performance
                // boost gained, if anything it would be worse!
                int c = x.read();
                int p = 0;
                while (c > -1 && p < length)
                {
                    los.write(c);
                    c = x.read();
                    p++;
                }
                los.close();
            }
            catch (IOException se)
            {
                throw new PSQLException(GT.tr("Unexpected error writing large object to database."), PSQLState.UNEXPECTED_ERROR, se);
            }
            // lob is closed by the stream so don't call lob.close()
            setLong(i, oid);
        }
    }

    public void setClob(int i, Clob x) throws SQLException
    {
        checkClosed();

        if (x == null)
        {
            setNull(i, Types.CLOB);
            return;
        }

        Reader l_inStream = x.getCharacterStream();
        int l_length = (int) x.length();
        LargeObjectManager lom = connection.getLargeObjectAPI();
        long oid = lom.createLO();
        LargeObject lob = lom.open(oid);
        Charset connectionCharset = Charset.forName(connection.getEncoding().name());
        OutputStream los = lob.getOutputStream();
        Writer lw = new OutputStreamWriter(los, connectionCharset);
        try
        {
            // could be buffered, but then the OutputStream returned by LargeObject
            // is buffered internally anyhow, so there would be no performance
            // boost gained, if anything it would be worse!
            int c = l_inStream.read();
            int p = 0;
            while (c > -1 && p < l_length)
            {
                lw.write(c);
                c = l_inStream.read();
                p++;
            }
            lw.close();
        }
        catch (IOException se)
        {
            throw new PSQLException(GT.tr("Unexpected error writing large object to database."), PSQLState.UNEXPECTED_ERROR, se);
        }
        // lob is closed by the stream so don't call lob.close()
        setLong(i, oid);
    }

    public void setNull(int i, int t, String s) throws SQLException
    {
        checkClosed();
        setNull(i, t);
    }

    public void setRef(int i, Ref x) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "setRef(int,Ref)");
    }

    public void setDate(int i, java.sql.Date d, java.util.Calendar cal) throws SQLException
    {
        checkClosed();

        if (d == null)
        {
            setNull(i, Types.DATE);
            return;
        }

        if (connection.binaryTransferSend(Oid.DATE)) {
            byte[] val = new byte[4];
            TimeZone tz = cal != null ? cal.getTimeZone() : null;
            connection.getTimestampUtils().toBinDate(tz, val, d);
            preparedParameters.setBinaryParameter(i, val, Oid.DATE);
            return;
        }
        
        // We must use UNSPECIFIED here, or inserting a Date-with-timezone into a
        // timestamptz field does an unexpected rotation by the server's TimeZone:
        //
        // We want to interpret 2005/01/01 with calendar +0100 as
        // "local midnight in +0100", but if we go via date it interprets it
        // as local midnight in the server's timezone:

        // template1=# select '2005-01-01+0100'::timestamptz;
        //       timestamptz       
        // ------------------------
        //  2005-01-01 02:00:00+03
        // (1 row)

        // template1=# select '2005-01-01+0100'::date::timestamptz;
        //       timestamptz       
        // ------------------------
        //  2005-01-01 00:00:00+03
        // (1 row)

        bindString(i, connection.getTimestampUtils().toString(cal, d), Oid.UNSPECIFIED);
    }

    public void setTime(int i, Time t, java.util.Calendar cal) throws SQLException
    {
        checkClosed();

        if (t == null)
        {
            setNull(i, Types.TIME);
            return;
        }

        int oid = Oid.UNSPECIFIED;

        // If a PGTime is used, we can define the OID explicitly.
        if (t instanceof PGTime) {
            PGTime pgTime = (PGTime)t;
            if (pgTime.getCalendar() == null) {
                oid = Oid.TIME;
            } else {
                oid = Oid.TIMETZ;
                cal = pgTime.getCalendar();
            }
        }

        bindString(i, connection.getTimestampUtils().toString(cal, t), oid);
    }

    public void setTimestamp(int i, Timestamp t, java.util.Calendar cal) throws SQLException
    {
        checkClosed();

        if (t == null) {
            setNull(i, Types.TIMESTAMP);
            return;
        }

        int oid = Oid.UNSPECIFIED;

        // Use UNSPECIFIED as a compromise to get both TIMESTAMP and TIMESTAMPTZ working.
        // This is because you get this in a +1300 timezone:
        // 
        // template1=# select '2005-01-01 15:00:00 +1000'::timestamptz;
        //       timestamptz       
        // ------------------------
        //  2005-01-01 18:00:00+13
        // (1 row)

        // template1=# select '2005-01-01 15:00:00 +1000'::timestamp;
        //       timestamp      
        // ---------------------
        //  2005-01-01 15:00:00
        // (1 row)        

        // template1=# select '2005-01-01 15:00:00 +1000'::timestamptz::timestamp;
        //       timestamp      
        // ---------------------
        //  2005-01-01 18:00:00
        // (1 row)
        
        // So we want to avoid doing a timestamptz -> timestamp conversion, as that
        // will first convert the timestamptz to an equivalent time in the server's
        // timezone (+1300, above), then turn it into a timestamp with the "wrong"
        // time compared to the string we originally provided. But going straight
        // to timestamp is OK as the input parser for timestamp just throws away
        // the timezone part entirely. Since we don't know ahead of time what type
        // we're actually dealing with, UNSPECIFIED seems the lesser evil, even if it
        // does give more scope for type-mismatch errors being silently hidden.

        // If a PGTimestamp is used, we can define the OID explicitly.
        if (t instanceof PGTimestamp) {
            PGTimestamp pgTimestamp = (PGTimestamp)t;
            if (pgTimestamp.getCalendar() == null) {
                oid = Oid.TIMESTAMP;
            } else {
                oid = Oid.TIMESTAMPTZ;
                cal = pgTimestamp.getCalendar();
            }
        }

        bindString(i, connection.getTimestampUtils().toString(cal, t), oid);
    }

    // ** JDBC 2 Extensions for CallableStatement**

    public java.sql.Array getArray(int i) throws SQLException
    {
        checkClosed();
        checkIndex(i, Types.ARRAY, "Array");
        return (Array)callResult[i-1];
    }

    public java.math.BigDecimal getBigDecimal(int parameterIndex) throws SQLException
    {
        checkClosed();
        checkIndex (parameterIndex, Types.NUMERIC, "BigDecimal");
        return ((BigDecimal)callResult[parameterIndex-1]);
    }

    public Blob getBlob(int i) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "getBlob(int)");
    }

    public Clob getClob(int i) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "getClob(int)");
    }

    public Object getObjectImpl(int i, java.util.Map map) throws SQLException
    {
        if (map == null || map.isEmpty()) {
            return getObject(i);
        }
        throw Driver.notImplemented(this.getClass(), "getObjectImpl(int,Map)");
    }

    public Ref getRef(int i) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "getRef(int)");
    }

    public java.sql.Date getDate(int i, java.util.Calendar cal) throws SQLException
    {
        checkClosed();
        checkIndex(i, Types.DATE, "Date");

        if (callResult[i-1] == null)
            return null;

        String value = callResult[i-1].toString();
        return connection.getTimestampUtils().toDate(cal, value);
    }

    public Time getTime(int i, java.util.Calendar cal) throws SQLException
    {
        checkClosed();
        checkIndex(i, Types.TIME, "Time");

        if (callResult[i-1] == null)
            return null;

        String value = callResult[i-1].toString();
        return connection.getTimestampUtils().toTime(cal, value);
    }

    public Timestamp getTimestamp(int i, java.util.Calendar cal) throws SQLException
    {
        checkClosed();
        checkIndex(i, Types.TIMESTAMP, "Timestamp");

        if (callResult[i-1] == null)
            return null;

        String value = callResult[i-1].toString();
        return connection.getTimestampUtils().toTimestamp(cal, value);
    }

    // no custom types allowed yet..
    public void registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "registerOutParameter(int,int,String)");
    }

    private void startTimer()
    {
        /*
         * there shouldn't be any previous timer active, but better safe than
         * sorry.
         */
        cleanupTimer();

        STATE_UPDATER.set(this, STATE_IN_QUERY);

        if (timeout == 0)
        {
            return;
        }

        TimerTask cancelTask = new TimerTask() {
            public void run()
            {
                try
                {
                    if (!CANCEL_TIMER_UPDATER.compareAndSet(AbstractJdbc2Statement.this, this, null))
                    {
                        return; // Nothing to do here, statement has already finished and cleared cancelTimerTask reference
                    }
                    AbstractJdbc2Statement.this.cancel();
                } catch (SQLException e)
                {
                }
            }
        };

        CANCEL_TIMER_UPDATER.set(this, cancelTask);
        connection.addTimerTask(cancelTask, timeout);
    }

    /*
     * Clears {@link #cancelTimerTask} if any.
     * Returns true if and only if "cancel" timer task would never invoke {@link #cancel()}.
     */
    private boolean cleanupTimer()
    {
        TimerTask timerTask = CANCEL_TIMER_UPDATER.get(this);
        if (timerTask == null)
        {
            // If timeout is zero, then timer task did not exist, so we safely report "all clear"
            return timeout == 0;
        }
        if (!CANCEL_TIMER_UPDATER.compareAndSet(this, timerTask, null))
        {
            // Failed to update reference -> timer has just fired, so we must wait for the query state to become "cancelling".
            return false;
        }
        timerTask.cancel();
        connection.purgeTimerTasks();
        // All clear
        return true;
    }

    private void killTimerTask()
    {
        boolean timerTaskIsClear = cleanupTimer();
        // The order is important here: in case we need to wait for the cancel task, the state must be
        // kept STATE_IN_QUERY, so cancelTask would be able to cancel the query.
        // It is believed that this case is very rare, so "additional cancel and wait below" would not harm it.
        if (timerTaskIsClear && STATE_UPDATER.compareAndSet(this, STATE_IN_QUERY, STATE_IDLE))
        {
            return;
        }

        // Being here means someone managed to call .cancel() and our connection did not receive "timeout error"
        // We wait till state becomes "cancelled"
        boolean interrupted = false;
        while (!STATE_UPDATER.compareAndSet(this, STATE_CANCELLED, STATE_IDLE))
        {
            synchronized (connection)
            {
                try
                {
                    // Note: wait timeout here is irrelevant since synchronized(connection) would block until .cancel finishes
                    connection.wait(10);
                } catch (InterruptedException e)
                {
                    interrupted = true;
                }
            }
        }
        if (interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }

    protected boolean getForceBinaryTransfer()
    {
        return forceBinaryTransfers;        
    }
}
