/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.jdbc;


import org.postgresql.Driver;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Field;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandler;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.Utils;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class PgStatement implements Statement, BaseStatement {
  /**
   * Default state for use or not binary transfers. Can use only for testing purposes
   */
  private static final boolean DEFAULT_FORCE_BINARY_TRANSFERS =
      Boolean.getBoolean("org.postgresql.forceBinary");
  // only for testing purposes. even single shot statements will use binary transfers
  private boolean forceBinaryTransfers = DEFAULT_FORCE_BINARY_TRANSFERS;

  protected ArrayList<Query> batchStatements = null;
  protected ArrayList<ParameterList> batchParameters = null;
  protected final int resultsettype;   // the resultset type to return (ResultSet.TYPE_xxx)
  protected final int concurrency;   // is it updateable or not?     (ResultSet.CONCUR_xxx)
  private final int rsHoldability;
  private boolean poolable;
  private boolean closeOnCompletion = false;
  protected int fetchdirection = ResultSet.FETCH_FORWARD;
  // fetch direction hint (currently ignored)

  /**
   * Protects current statement from cancelTask starting, waiting for a bit, and waking up exactly
   * on subsequent query execution. The idea is to atomically compare and swap the reference to the
   * task, so the task can detect that statement executes different query than the one the
   * cancelTask was created. Note: the field must be set/get/compareAndSet via {@link
   * #CANCEL_TIMER_UPDATER} as per {@link AtomicReferenceFieldUpdater} javadoc.
   */
  private volatile TimerTask cancelTimerTask = null;
  private static final AtomicReferenceFieldUpdater<PgStatement, TimerTask> CANCEL_TIMER_UPDATER =
      AtomicReferenceFieldUpdater.newUpdater(PgStatement.class, TimerTask.class, "cancelTimerTask");

  /**
   * Protects statement from out-of-order cancels. It protects from both {@link
   * #setQueryTimeout(int)} and {@link #cancel()} induced ones.
   *
   * .execute() and friends change statementState to STATE_IN_QUERY during execute. .cancel()
   * ignores cancel request if state is IDLE. In case .execute() observes non-IN_QUERY state as it
   * completes the query, it waits till STATE_CANCELLED. Note: the field must be
   * set/get/compareAndSet via {@link #STATE_UPDATER} as per {@link AtomicIntegerFieldUpdater}
   * javadoc.
   */
  private volatile int statementState = STATE_IDLE;
  private final static int STATE_IDLE = 0;
  private final static int STATE_IN_QUERY = 1;
  private final static int STATE_CANCELLING = 2;
  private final static int STATE_CANCELLED = 3;

  private static final AtomicIntegerFieldUpdater<PgStatement> STATE_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(PgStatement.class, "statementState");

  /**
   * Does the caller of execute/executeUpdate want generated keys for this execution?  This is set
   * by Statement methods that have generated keys arguments and cleared after execution is
   * complete.
   */
  protected boolean wantsGeneratedKeysOnce = false;

  /**
   * Was this PreparedStatement created to return generated keys for every execution?  This is set
   * at creation time and never cleared by execution.
   */
  public boolean wantsGeneratedKeysAlways = false;

  // The connection who created us
  protected final BaseConnection connection;

  /**
   * The warnings chain.
   */
  protected SQLWarning warnings = null;
  /**
   * The last warning of the warning chain.
   */
  protected SQLWarning lastWarning = null;

  /**
   * Maximum number of rows to return, 0 = unlimited
   */
  protected int maxrows = 0;

  /**
   * Number of rows to get in a batch.
   */
  protected int fetchSize = 0;

  /**
   * Timeout (in milliseconds) for a query
   */
  protected int timeout = 0;

  protected boolean replaceProcessingEnabled = true;

  /**
   * The current results.
   */
  protected ResultWrapper result = null;

  /**
   * The first unclosed result.
   */
  protected ResultWrapper firstUnclosedResult = null;

  /**
   * Results returned by a statement that wants generated keys.
   */
  protected ResultWrapper generatedKeys = null;

  // Static variables for parsing SQL when replaceProcessing is true.
  private static final short IN_SQLCODE = 0;
  private static final short IN_STRING = 1;
  private static final short IN_IDENTIFIER = 6;
  private static final short BACKSLASH = 2;
  private static final short ESC_TIMEDATE = 3;
  private static final short ESC_FUNCTION = 4;
  private static final short ESC_OUTERJOIN = 5;
  private static final short ESC_ESCAPECHAR = 7;

  protected Query lastSimpleQuery;

  protected int m_prepareThreshold;                // Reuse threshold to enable use of PREPARE

  protected int maxfieldSize = 0;

  PgStatement(PgConnection c, int rsType, int rsConcurrency, int rsHoldability)
      throws SQLException {
    this.connection = c;
    this.lastSimpleQuery = null;
    forceBinaryTransfers |= c.getForceBinary();
    resultsettype = rsType;
    concurrency = rsConcurrency;
    setFetchSize(c.getDefaultFetchSize());
    setPrepareThreshold(c.getPrepareThreshold());
    this.rsHoldability = rsHoldability;
  }

  public ResultSet createResultSet(Query originalQuery, Field[] fields, List<byte[][]> tuples,
      ResultCursor cursor)
      throws SQLException {
    PgResultSet newResult = new PgResultSet(originalQuery,
        this,
        fields,
        tuples,
        cursor,
        getMaxRows(),
        getMaxFieldSize(),
        getResultSetType(),
        getResultSetConcurrency(),
        getResultSetHoldability());
    newResult.setFetchSize(getFetchSize());
    newResult.setFetchDirection(getFetchDirection());
    return newResult;
  }

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
    // FIXME: false if not supported
    return rsHoldability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  /**
   * ResultHandler implementations for updates, queries, and either-or.
   */
  public class StatementResultHandler implements ResultHandler {
    private SQLException error;
    private ResultWrapper results;

    ResultWrapper getResults() {
      return results;
    }

    private void append(ResultWrapper newResult) {
      if (results == null) {
        results = newResult;
      } else {
        results.append(newResult);
      }
    }

    public void handleResultRows(Query fromQuery, Field[] fields, List<byte[][]> tuples,
        ResultCursor cursor) {
      try {
        ResultSet rs = PgStatement.this.createResultSet(fromQuery, fields, tuples, cursor);
        append(new ResultWrapper(rs));
      } catch (SQLException e) {
        handleError(e);
      }
    }

    public void handleCommandStatus(String status, int updateCount, long insertOID) {
      append(new ResultWrapper(updateCount, insertOID));
    }

    public void handleWarning(SQLWarning warning) {
      PgStatement.this.addWarning(warning);
    }

    public void handleError(SQLException newError) {
      if (error == null) {
        error = newError;
      } else {
        error.setNextException(newError);
      }
    }

    public void handleCompletion() throws SQLException {
      if (error != null) {
        throw error;
      }
    }
  }

  public java.sql.ResultSet executeQuery(String p_sql) throws SQLException {
    if (forceBinaryTransfers) {
      clearWarnings();
      // Close any existing resultsets associated with this statement.
      while (firstUnclosedResult != null) {
        if (firstUnclosedResult.getResultSet() != null) {
          firstUnclosedResult.getResultSet().close();
        }
        firstUnclosedResult = firstUnclosedResult.getNext();
      }

      PreparedStatement ps =
          connection.prepareStatement(p_sql, resultsettype, concurrency, getResultSetHoldability());
      ps.setMaxFieldSize(getMaxFieldSize());
      ps.setFetchSize(getFetchSize());
      ps.setFetchDirection(getFetchDirection());
      PgResultSet rs = (PgResultSet) ps.executeQuery();
      rs.registerRealStatement(this);

      result = firstUnclosedResult = new ResultWrapper(rs);
      return rs;
    }

    if (!executeWithFlags(p_sql, 0)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    if (result.getNext() != null) {
      throw new PSQLException(GT.tr("Multiple ResultSets were returned by the query."),
          PSQLState.TOO_MANY_RESULTS);
    }

    return (ResultSet) result.getResultSet();
  }

  public int executeUpdate(String p_sql) throws SQLException {
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

  public boolean execute(String p_sql) throws SQLException {
    return executeWithFlags(p_sql, 0);
  }

  public boolean executeWithFlags(String p_sql, int flags) throws SQLException {
    checkClosed();
    p_sql = replaceProcessing(p_sql, replaceProcessingEnabled,
        connection.getStandardConformingStrings());
    Query simpleQuery = connection.getQueryExecutor().createSimpleQuery(p_sql);
    execute(simpleQuery, null, QueryExecutor.QUERY_ONESHOT | flags);
    this.lastSimpleQuery = simpleQuery;
    return (result != null && result.getResultSet() != null);
  }

  public boolean executeWithFlags(int flags) throws SQLException {
    checkClosed();
    throw new PSQLException(GT.tr("Can''t use executeWithFlags(int) on a Statement."),
        PSQLState.WRONG_OBJECT_TYPE);
  }

  protected void closeForNextExecution() throws SQLException {
    // Every statement execution clears any previous warnings.
    clearWarnings();

    // Close any existing resultsets associated with this statement.
    while (firstUnclosedResult != null) {
      ResultSet rs = firstUnclosedResult.getResultSet();
      if (rs != null) {
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

  /**
   * Returns true if query is unlikely to be reused
   *
   * @param query to check (null if current query)
   * @return true if query is unlikely to be reused
   */
  protected boolean isOneShotQuery(Query query) {
    return true;
  }

  protected void execute(Query queryToExecute, ParameterList queryParameters, int flags)
      throws SQLException {
    closeForNextExecution();

    // Enable cursor-based resultset if possible.
    if (fetchSize > 0 && !wantsScrollableResultSet() && !connection.getAutoCommit()
        && !wantsHoldableResultSet()) {
      flags |= QueryExecutor.QUERY_FORWARD_CURSOR;
    }

    if (wantsGeneratedKeysOnce || wantsGeneratedKeysAlways) {
      flags |= QueryExecutor.QUERY_BOTH_ROWS_AND_STATUS;

      // If the no results flag is set (from executeUpdate)
      // clear it so we get the generated keys results.
      //
      if ((flags & QueryExecutor.QUERY_NO_RESULTS) != 0) {
        flags &= ~(QueryExecutor.QUERY_NO_RESULTS);
      }
    }

    if (isOneShotQuery(queryToExecute)) {
      flags |= QueryExecutor.QUERY_ONESHOT;
    }
    // Only use named statements after we hit the threshold. Note that only
    // named statements can be transferred in binary format.

    if (connection.getAutoCommit()) {
      flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;
    }

    // updateable result sets do not yet support binary updates
    if (concurrency != ResultSet.CONCUR_READ_ONLY) {
      flags |= QueryExecutor.QUERY_NO_BINARY_TRANSFER;
    }

    if (queryToExecute.isEmpty()) {
      flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;
    }

    if (!queryToExecute.isStatementDescribed() && forceBinaryTransfers) {
      int flags2 = flags | QueryExecutor.QUERY_DESCRIBE_ONLY;
      StatementResultHandler handler2 = new StatementResultHandler();
      connection.getQueryExecutor()
          .execute(queryToExecute, queryParameters, handler2, 0, 0, flags2);
      ResultWrapper result2 = handler2.getResults();
      if (result2 != null) {
        result2.getResultSet().close();
      }
    }

    StatementResultHandler handler = new StatementResultHandler();
    result = null;
    try {
      startTimer();
      connection.getQueryExecutor().execute(queryToExecute,
          queryParameters,
          handler,
          maxrows,
          fetchSize,
          flags);
    } finally {
      killTimerTask();
    }
    result = firstUnclosedResult = handler.getResults();

    if (wantsGeneratedKeysOnce || wantsGeneratedKeysAlways) {
      generatedKeys = result;
      result = result.getNext();

      if (wantsGeneratedKeysOnce) {
        wantsGeneratedKeysOnce = false;
      }
    }

  }

  public void setCursorName(String name) throws SQLException {
    checkClosed();
    // No-op.
  }

  // This is intentionally non-volatile to avoid performance hit in isClosed checks
  // see #close()
  protected boolean isClosed = false;

  public int getUpdateCount() throws SQLException {
    checkClosed();
    if (result == null || result.getResultSet() != null) {
      return -1;
    }

    return result.getUpdateCount();
  }

  public boolean getMoreResults() throws SQLException {
    if (result == null) {
      return false;
    }

    result = result.getNext();

    // Close preceding resultsets.
    while (firstUnclosedResult != result) {
      if (firstUnclosedResult.getResultSet() != null) {
        firstUnclosedResult.getResultSet().close();
      }
      firstUnclosedResult = firstUnclosedResult.getNext();
    }

    return (result != null && result.getResultSet() != null);
  }

  public int getMaxRows() throws SQLException {
    checkClosed();
    return maxrows;
  }

  public void setMaxRows(int max) throws SQLException {
    checkClosed();
    if (max < 0) {
      throw new PSQLException(
          GT.tr("Maximum number of rows must be a value grater than or equal to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    maxrows = max;
  }

  public void setEscapeProcessing(boolean enable) throws SQLException {
    checkClosed();
    replaceProcessingEnabled = enable;
  }

  public int getQueryTimeout() throws SQLException {
    return getQueryTimeoutMs() / 1000;
  }

  public void setQueryTimeout(int seconds) throws SQLException {
    setQueryTimeoutMs(seconds * 1000);
  }

  /**
   * The queryTimeout limit is the number of milliseconds the driver will wait for a Statement to
   * execute.  If the limit is exceeded, a SQLException is thrown.
   *
   * @return the current query timeout limit in milliseconds; 0 = unlimited
   * @throws SQLException if a database access error occurs
   */
  public int getQueryTimeoutMs() throws SQLException {
    checkClosed();
    return timeout;
  }

  /**
   * Sets the queryTimeout limit
   *
   * @param millis - the new query timeout limit in milliseconds
   * @throws SQLException if a database access error occurs
   */
  public void setQueryTimeoutMs(int millis) throws SQLException {
    checkClosed();

    if (millis < 0) {
      throw new PSQLException(GT.tr("Query timeout must be a value greater than or equals to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    timeout = millis;
  }

  /**
   * This adds a warning to the warning chain.  We track the tail of the warning chain as well to
   * avoid O(N) behavior for adding a new warning to an existing chain.  Some server functions which
   * RAISE NOTICE (or equivalent) produce a ton of warnings.
   *
   * @param warn warning to add
   */
  public void addWarning(SQLWarning warn) {
    if (warnings == null) {
      warnings = warn;
      lastWarning = warn;
    } else {
      lastWarning.setNextWarning(warn);
      lastWarning = warn;
    }
  }

  public SQLWarning getWarnings() throws SQLException {
    checkClosed();
    return warnings;
  }

  public int getMaxFieldSize() throws SQLException {
    return maxfieldSize;
  }

  public void setMaxFieldSize(int max) throws SQLException {
    checkClosed();
    if (max < 0) {
      throw new PSQLException(
          GT.tr("The maximum field size must be a value greater than or equal to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    maxfieldSize = max;
  }

  public void clearWarnings() throws SQLException {
    warnings = null;
    lastWarning = null;
  }

  public java.sql.ResultSet getResultSet() throws SQLException {
    checkClosed();

    if (result == null) {
      return null;
    }

    return (ResultSet) result.getResultSet();
  }

  /**
   * <B>Note:</B> even though {@code Statement} is automatically closed when it is garbage
   * collected, it is better to close it explicitly to lower resource consumption.
   *
   * {@inheritDoc}
   */
  public void close() throws SQLException {
    // closing an already closed Statement is a no-op.
    if (isClosed) {
      return;
    }

    cleanupTimer();

    closeForNextExecution();

    isClosed = true;
  }

  /**
   * Filter the SQL string of Java SQL Escape clauses.
   *
   * Currently implemented Escape clauses are those mentioned in 11.3 in the specification.
   * Basically we look through the sql string for {d xxx}, {t xxx}, {ts xxx}, {oj xxx} or {fn xxx}
   * in non-string sql code. When we find them, we just strip the escape part leaving only the xxx
   * part. So, something like "select * from x where d={d '2001-10-09'}" would return "select * from
   * x where d= '2001-10-09'".
   *
   * @param p_sql                     the original query text
   * @param replaceProcessingEnabled  whether replace_processing_enabled is on
   * @param standardConformingStrings whether standard_conforming_strings is on
   * @return PostgreSQL-compatible SQL
   */
  static String replaceProcessing(String p_sql, boolean replaceProcessingEnabled,
      boolean standardConformingStrings) throws SQLException {
    if (replaceProcessingEnabled) {
      // Since escape codes can only appear in SQL CODE, we keep track
      // of if we enter a string or not.
      int len = p_sql.length();
      StringBuilder newsql = new StringBuilder(len);
      int i = 0;
      while (i < len) {
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
    } else {
      return p_sql;
    }
  }

  /**
   * parse the given sql from index i, appending it to the gven buffer until we hit an unmatched
   * right parentheses or end of string.  When the stopOnComma flag is set we also stop processing
   * when a comma is found in sql text that isn't inside nested parenthesis.
   *
   * @param p_sql       the original query text
   * @param i           starting position for replacing
   * @param newsql      where to write the replaced output
   * @param stopOnComma should we stop after hitting the first comma in sql text?
   * @param stdStrings  whether standard_conforming_strings is on
   * @return the position we stopped processing at
   * @throws SQLException if given SQL is wrong
   */
  protected static int parseSql(String p_sql, int i, StringBuilder newsql, boolean stopOnComma,
      boolean stdStrings) throws SQLException {
    short state = IN_SQLCODE;
    int len = p_sql.length();
    int nestedParenthesis = 0;
    boolean endOfNested = false;

    // because of the ++i loop
    i--;
    while (!endOfNested && ++i < len) {
      char c = p_sql.charAt(i);
      switch (state) {
        case IN_SQLCODE:
          if (c == '\'') {
            // start of a string?
            state = IN_STRING;
          } else if (c == '"') {
            // start of a identifer?
            state = IN_IDENTIFIER;
          } else if (c == '(') { // begin nested sql
            nestedParenthesis++;
          } else if (c == ')') { // end of nested sql
            nestedParenthesis--;
            if (nestedParenthesis < 0) {
              endOfNested = true;
              break;
            }
          } else if (stopOnComma && c == ',' && nestedParenthesis == 0) {
            endOfNested = true;
            break;
          } else if (c == '{') {     // start of an escape code?
            if (i + 1 < len) {
              char next = p_sql.charAt(i + 1);
              char nextnext = (i + 2 < len) ? p_sql.charAt(i + 2) : '\0';
              if (next == 'd' || next == 'D') {
                state = ESC_TIMEDATE;
                i++;
                newsql.append("DATE ");
                break;
              } else if (next == 't' || next == 'T') {
                state = ESC_TIMEDATE;
                if (nextnext == 's' || nextnext == 'S') {
                  // timestamp constant
                  i += 2;
                  newsql.append("TIMESTAMP ");
                } else {
                  // time constant
                  i++;
                  newsql.append("TIME ");
                }
                break;
              } else if (next == 'f' || next == 'F') {
                state = ESC_FUNCTION;
                i += (nextnext == 'n' || nextnext == 'N') ? 2 : 1;
                break;
              } else if (next == 'o' || next == 'O') {
                state = ESC_OUTERJOIN;
                i += (nextnext == 'j' || nextnext == 'J') ? 2 : 1;
                break;
              } else if (next == 'e' || next
                  == 'E') { // we assume that escape is the only escape sequence beginning with e
                state = ESC_ESCAPECHAR;
                break;
              }
            }
          }
          newsql.append(c);
          break;

        case IN_STRING:
          if (c == '\'') {
            // end of string?
            state = IN_SQLCODE;
          } else if (c == '\\' && !stdStrings) {
            // a backslash?
            state = BACKSLASH;
          }

          newsql.append(c);
          break;

        case IN_IDENTIFIER:
          if (c == '"') {
            // end of identifier
            state = IN_SQLCODE;
          }
          newsql.append(c);
          break;

        case BACKSLASH:
          state = IN_STRING;

          newsql.append(c);
          break;

        case ESC_FUNCTION:
          // extract function name
          String functionName;
          int posArgs = p_sql.indexOf('(', i);
          if (posArgs != -1) {
            functionName = p_sql.substring(i, posArgs).trim();
            // extract arguments
            i = posArgs + 1;// we start the scan after the first (
            StringBuilder args = new StringBuilder();
            i = parseSql(p_sql, i, args, false, stdStrings);
            // translate the function and parse arguments
            newsql.append(escapeFunction(functionName, args.toString(), stdStrings));
          }
          // go to the end of the function copying anything found
          i++;
          while (i < len && p_sql.charAt(i) != '}') {
            newsql.append(p_sql.charAt(i++));
          }
          state = IN_SQLCODE; // end of escaped function (or query)
          break;
        case ESC_TIMEDATE:
        case ESC_OUTERJOIN:
        case ESC_ESCAPECHAR:
          if (c == '}') {
            state = IN_SQLCODE;    // end of escape code.
          } else {
            newsql.append(c);
          }
          break;
      } // end switch
    }
    return i;
  }

  /**
   * generate sql for escaped functions
   *
   * @param functionName the escaped function name
   * @param args         the arguments for this functin
   * @param stdStrings   whether standard_conforming_strings is on
   * @return the right postgreSql sql
   * @throws SQLException if something goes wrong
   */
  protected static String escapeFunction(String functionName, String args, boolean stdStrings)
      throws SQLException {
    // parse function arguments
    int len = args.length();
    int i = 0;
    ArrayList<StringBuilder> parsedArgs = new ArrayList<StringBuilder>();
    while (i < len) {
      StringBuilder arg = new StringBuilder();
      int lastPos = i;
      i = parseSql(args, i, arg, true, stdStrings);
      if (lastPos != i) {
        parsedArgs.add(arg);
      }
      i++;
    }
    // we can now tranlate escape functions
    try {
      Method escapeMethod = EscapedFunctions.getFunction(functionName);
      return (String) escapeMethod.invoke(null, new Object[]{parsedArgs});
    } catch (InvocationTargetException e) {
      if (e.getTargetException() instanceof SQLException) {
        throw (SQLException) e.getTargetException();
      } else {
        throw new PSQLException(e.getTargetException().getMessage(),
            PSQLState.SYSTEM_ERROR);
      }
    } catch (Exception e) {
      // by default the function name is kept unchanged
      StringBuilder buf = new StringBuilder();
      buf.append(functionName).append('(');
      for (int iArg = 0; iArg < parsedArgs.size(); iArg++) {
        buf.append(parsedArgs.get(iArg));
        if (iArg != (parsedArgs.size() - 1)) {
          buf.append(',');
        }
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

  public long getLastOID() throws SQLException {
    checkClosed();
    if (result == null) {
      return 0;
    }
    return result.getInsertOID();
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
    return false;
  }

  protected void checkClosed() throws SQLException {
    if (isClosed) {
      throw new PSQLException(GT.tr("This statement has been closed."),
          PSQLState.OBJECT_NOT_IN_STATE);
    }
  }

  // ** JDBC 2 Extensions **

  public void addBatch(String p_sql) throws SQLException {
    checkClosed();

    if (batchStatements == null) {
      batchStatements = new ArrayList<Query>();
      batchParameters = new ArrayList<ParameterList>();
    }

    p_sql = replaceProcessing(p_sql, replaceProcessingEnabled,
        connection.getStandardConformingStrings());

    batchStatements.add(connection.getQueryExecutor().createSimpleQuery(p_sql));
    batchParameters.add(null);
  }

  public void clearBatch() throws SQLException {
    if (batchStatements != null) {
      batchStatements.clear();
      batchParameters.clear();
    }
  }

  protected ResultHandler createBatchHandler(int[] updateCounts, Query[] queries,
      ParameterList[] parameterLists) {
    return new BatchResultHandler(this, queries, parameterLists, updateCounts,
        wantsGeneratedKeysAlways);
  }

  public int[] executeBatch() throws SQLException {
    checkClosed();

    closeForNextExecution();

    if (batchStatements == null || batchStatements.isEmpty()) {
      return new int[0];
    }

    int size = batchStatements.size();
    int[] updateCounts = new int[size];

    // Construct query/parameter arrays.
    Query[] queries = batchStatements.toArray(new Query[batchStatements.size()]);
    ParameterList[] parameterLists =
        batchParameters.toArray(new ParameterList[batchParameters.size()]);
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
    if (isOneShotQuery(null)) {
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

    if (connection.getAutoCommit()) {
      flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;
    }

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
    handler = createBatchHandler(updateCounts, queries, parameterLists);

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
      generatedKeys = new ResultWrapper(((BatchResultHandler) handler).getGeneratedKeys());
    }

    return updateCounts;
  }

  public void cancel() throws SQLException {
    if (!STATE_UPDATER.compareAndSet(this, STATE_IN_QUERY, STATE_CANCELLING)) {
      // Not in query, there's nothing to cancel
      return;
    }
    try {
      // Synchronize on connection to avoid spinning in killTimerTask
      synchronized (connection) {
        connection.cancelQuery();
      }
    } finally {
      STATE_UPDATER.set(this, STATE_CANCELLED);
      synchronized (connection) {
        connection.notifyAll(); // wake-up killTimerTask
      }
    }
  }

  public Connection getConnection() throws SQLException {
    return (Connection) connection;
  }

  public int getFetchDirection() {
    return fetchdirection;
  }

  public int getResultSetConcurrency() {
    return concurrency;
  }

  public int getResultSetType() {
    return resultsettype;
  }

  public void setFetchDirection(int direction) throws SQLException {
    switch (direction) {
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

  public void setFetchSize(int rows) throws SQLException {
    checkClosed();
    if (rows < 0) {
      throw new PSQLException(GT.tr("Fetch size must be a value greater to or equal to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    fetchSize = rows;
  }

  private void startTimer() {
    /*
     * there shouldn't be any previous timer active, but better safe than
     * sorry.
     */
    cleanupTimer();

    STATE_UPDATER.set(this, STATE_IN_QUERY);

    if (timeout == 0) {
      return;
    }

    TimerTask cancelTask = new TimerTask() {
      public void run() {
        try {
          if (!CANCEL_TIMER_UPDATER.compareAndSet(PgStatement.this, this, null)) {
            return; // Nothing to do here, statement has already finished and cleared cancelTimerTask reference
          }
          PgStatement.this.cancel();
        } catch (SQLException e) {
        }
      }
    };

    CANCEL_TIMER_UPDATER.set(this, cancelTask);
    connection.addTimerTask(cancelTask, timeout);
  }

  /**
   * Clears {@link #cancelTimerTask} if any. Returns true if and only if "cancel" timer task would
   * never invoke {@link #cancel()}.
   */
  private boolean cleanupTimer() {
    TimerTask timerTask = CANCEL_TIMER_UPDATER.get(this);
    if (timerTask == null) {
      // If timeout is zero, then timer task did not exist, so we safely report "all clear"
      return timeout == 0;
    }
    if (!CANCEL_TIMER_UPDATER.compareAndSet(this, timerTask, null)) {
      // Failed to update reference -> timer has just fired, so we must wait for the query state to become "cancelling".
      return false;
    }
    timerTask.cancel();
    connection.purgeTimerTasks();
    // All clear
    return true;
  }

  private void killTimerTask() {
    boolean timerTaskIsClear = cleanupTimer();
    // The order is important here: in case we need to wait for the cancel task, the state must be
    // kept STATE_IN_QUERY, so cancelTask would be able to cancel the query.
    // It is believed that this case is very rare, so "additional cancel and wait below" would not harm it.
    if (timerTaskIsClear && STATE_UPDATER.compareAndSet(this, STATE_IN_QUERY, STATE_IDLE)) {
      return;
    }

    // Being here means someone managed to call .cancel() and our connection did not receive "timeout error"
    // We wait till state becomes "cancelled"
    boolean interrupted = false;
    while (!STATE_UPDATER.compareAndSet(this, STATE_CANCELLED, STATE_IDLE)) {
      synchronized (connection) {
        try {
          // Note: wait timeout here is irrelevant since synchronized(connection) would block until .cancel finishes
          connection.wait(10);
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  protected boolean getForceBinaryTransfer() {
    return forceBinaryTransfers;
  }

  static String addReturning(BaseConnection connection, String sql, String columns[],
      boolean escape) throws SQLException {
    if (!connection.haveMinimumServerVersion(ServerVersion.v8_2)) {
      throw new PSQLException(
          GT.tr("Returning autogenerated keys is only supported for 8.2 and later servers."),
          PSQLState.NOT_IMPLEMENTED);
    }

    sql = sql.trim();
    if (sql.endsWith(";")) {
      sql = sql.substring(0, sql.length() - 1);
    }

    StringBuilder sb = new StringBuilder(sql);
    sb.append(" RETURNING ");
    for (int i = 0; i < columns.length; i++) {
      if (i != 0) {
        sb.append(", ");
      }
      // If given user provided column names, quote and escape them.
      // This isn't likely to be popular as it enforces case sensitivity,
      // but it does match up with our handling of things like
      // DatabaseMetaData.getColumns and is necessary for the same
      // reasons.
      if (escape) {
        Utils.escapeIdentifier(sb, columns[i]);
      } else {
        sb.append(columns[i]);
      }
    }

    return sb.toString();
  }

  public long getLargeUpdateCount() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getLargeUpdateCount");
  }

  public void setLargeMaxRows(long max) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "setLargeMaxRows");
  }

  public long getLargeMaxRows() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "getLargeMaxRows");
  }

  public long[] executeLargeBatch() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "executeLargeBatch");
  }

  public long executeLargeUpdate(String sql) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "executeLargeUpdate");
  }

  public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "executeLargeUpdate");
  }

  public long executeLargeUpdate(String sql, int columnIndexes[]) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "executeLargeUpdate");
  }

  public long executeLargeUpdate(String sql, String columnNames[]) throws SQLException {
    throw Driver.notImplemented(this.getClass(), "executeLargeUpdate");
  }

  public long executeLargeUpdate() throws SQLException {
    throw Driver.notImplemented(this.getClass(), "executeLargeUpdate");
  }

  public boolean isClosed() throws SQLException {
    return isClosed;
  }

  public void setPoolable(boolean poolable) throws SQLException {
    checkClosed();
    this.poolable = poolable;
  }

  public boolean isPoolable() throws SQLException {
    checkClosed();
    return poolable;
  }

  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }

  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw Driver.notImplemented(this.getClass(), "getParentLogger()");
  }

  public void closeOnCompletion() throws SQLException {
    closeOnCompletion = true;
  }

  public boolean isCloseOnCompletion() throws SQLException {
    return closeOnCompletion;
  }

  protected void checkCompletion() throws SQLException {
    if (!closeOnCompletion) {
      return;
    }

    ResultWrapper result = firstUnclosedResult;
    while (result != null) {
      if (result.getResultSet() != null && !result.getResultSet().isClosed()) {
        return;
      }
      result = result.getNext();
    }

    // prevent all ResultSet.close arising from Statement.close to loop here
    closeOnCompletion = false;
    try {
      close();
    } finally {
      // restore the status if one rely on isCloseOnCompletion
      closeOnCompletion = true;
    }
  }

  public boolean getMoreResults(int current) throws SQLException {
    // CLOSE_CURRENT_RESULT
    if (current == Statement.CLOSE_CURRENT_RESULT && result != null
        && result.getResultSet() != null) {
      result.getResultSet().close();
    }

    // Advance resultset.
    if (result != null) {
      result = result.getNext();
    }

    // CLOSE_ALL_RESULTS
    if (current == Statement.CLOSE_ALL_RESULTS) {
      // Close preceding resultsets.
      while (firstUnclosedResult != result) {
        if (firstUnclosedResult.getResultSet() != null) {
          firstUnclosedResult.getResultSet().close();
        }
        firstUnclosedResult = firstUnclosedResult.getNext();
      }
    }

    // Done.
    return (result != null && result.getResultSet() != null);
  }

  public ResultSet getGeneratedKeys() throws SQLException {
    checkClosed();
    if (generatedKeys == null || generatedKeys.getResultSet() == null) {
      return createDriverResultSet(new Field[0], new ArrayList<byte[][]>());
    }

    return generatedKeys.getResultSet();
  }

  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    if (autoGeneratedKeys == Statement.NO_GENERATED_KEYS) {
      return executeUpdate(sql);
    }

    sql = addReturning(connection, sql, new String[]{"*"}, false);
    wantsGeneratedKeysOnce = true;

    return executeUpdate(sql);
  }

  public int executeUpdate(String sql, int columnIndexes[]) throws SQLException {
    if (columnIndexes == null || columnIndexes.length == 0) {
      return executeUpdate(sql);
    }

    throw new PSQLException(GT.tr("Returning autogenerated keys by column index is not supported."),
        PSQLState.NOT_IMPLEMENTED);
  }

  public int executeUpdate(String sql, String columnNames[]) throws SQLException {
    if (columnNames == null || columnNames.length == 0) {
      return executeUpdate(sql);
    }

    sql = PgStatement.addReturning(connection, sql, columnNames, true);
    wantsGeneratedKeysOnce = true;

    return executeUpdate(sql);
  }

  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    if (autoGeneratedKeys == Statement.NO_GENERATED_KEYS) {
      return execute(sql);
    }

    sql = PgStatement.addReturning(connection, sql, new String[]{"*"}, false);
    wantsGeneratedKeysOnce = true;

    return execute(sql);
  }

  public boolean execute(String sql, int columnIndexes[]) throws SQLException {
    if (columnIndexes == null || columnIndexes.length == 0) {
      return execute(sql);
    }

    throw new PSQLException(GT.tr("Returning autogenerated keys by column index is not supported."),
        PSQLState.NOT_IMPLEMENTED);
  }

  public boolean execute(String sql, String columnNames[]) throws SQLException {
    if (columnNames == null || columnNames.length == 0) {
      return execute(sql);
    }

    sql = PgStatement.addReturning(connection, sql, columnNames, true);
    wantsGeneratedKeysOnce = true;

    return execute(sql);
  }

  public int getResultSetHoldability() throws SQLException {
    return rsHoldability;
  }

  public ResultSet createDriverResultSet(Field[] fields, List<byte[][]> tuples)
      throws SQLException {
    return createResultSet(null, fields, tuples, null);
  }
}
