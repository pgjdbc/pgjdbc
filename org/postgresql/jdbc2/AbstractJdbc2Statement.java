package org.postgresql.jdbc2;


import java.io.*;
import java.math.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Vector;
import org.postgresql.Driver;
import org.postgresql.largeobject.*;
import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PGobject;
import org.postgresql.util.GT;

/* $PostgreSQL: /cvsroot/pgsql-server/src/interfaces/jdbc/org/postgresql/jdbc2/AbstractJdbc2Statement.java,v 1.17 2003/09/09 10:49:16 barry Exp $
 * This class defines methods of the jdbc2 specification.
 * The real Statement class (for jdbc2) is org.postgresql.jdbc2.Jdbc2Statement
 */
public abstract class AbstractJdbc2Statement implements BaseStatement
{
	protected ArrayList batchStatements = null;
	protected ArrayList batchParameters = null;
	protected final int resultsettype;		 // the resultset type to return (ResultSet.TYPE_xxx)
	protected final int concurrency;		 // is it updateable or not?     (ResultSet.CONCUR_xxx)
	protected int fetchdirection = ResultSet.FETCH_FORWARD;	 // fetch direction hint (currently ignored)

	// The connection who created us
	protected BaseConnection connection;

	/** The warnings chain. */
	protected SQLWarning warnings = null;

	/** Maximum number of rows to return, 0 = unlimited */
	protected int maxrows = 0;

	/** Number of rows to get in a batch. */
	protected int fetchSize = 0;

	/** Timeout (in seconds) for a query (not used) */
	protected int timeout = 0;

	protected boolean replaceProcessingEnabled = true;

	/** The current results. */
	protected ResultWrapper result = null;

	/** The first unclosed result. */
	protected ResultWrapper firstUnclosedResult = null;

	// Static variables for parsing SQL when replaceProcessing is true.
	private static final short IN_SQLCODE = 0;
	private static final short IN_STRING = 1;
	private static final short BACKSLASH = 2;
	private static final short ESC_TIMEDATE = 3;
    private static final short ESC_FUNCTION = 4;
    private static final short ESC_OUTERJOIN = 5;


	// Some performance caches
	private StringBuffer sbuf = new StringBuffer(32);

	protected final Query preparedQuery;              // Query fragments for prepared statement.
	protected final ParameterList preparedParameters; // Parameter values for prepared statement.

	protected int m_prepareThreshold;                // Reuse threshold to enable use of PREPARE
	protected int m_useCount = 0;                    // Number of times this statement has been used

	//Used by the callablestatement style methods
	private boolean isFunction;
	// functionReturnType contains the user supplied value to check
	// testReturn contains a modified version to make it easier to
	// check the getXXX methods..
	private int functionReturnType;
	private int testReturn;
	// returnTypeSet is true when a proper call to registerOutParameter has been made
	private boolean returnTypeSet;
	protected Object callResult;
	protected int maxfieldSize = 0;

	public ResultSet createDriverResultSet(Field[] fields, Vector tuples)
		throws SQLException
	{
		return createResultSet(null,fields,tuples,null);
	}

	public AbstractJdbc2Statement (AbstractJdbc2Connection c, int rsType, int rsConcurrency) throws SQLException
	{
		this.connection = c;
		this.preparedQuery = null;
		this.preparedParameters = null;
		resultsettype = rsType;
		concurrency = rsConcurrency;
	}

	public AbstractJdbc2Statement(AbstractJdbc2Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency) throws SQLException
	{
		this.connection = connection;

		String parsed_sql = replaceProcessing(sql);
		if (isCallable)
			parsed_sql = modifyJdbcCall(parsed_sql);

		this.preparedQuery = connection.getQueryExecutor().createParameterizedQuery(parsed_sql);
		this.preparedParameters = preparedQuery.createParameterList();

		resultsettype = rsType;
		concurrency = rsConcurrency;
	}

	public abstract ResultSet createResultSet(Query originalQuery, Field[] fields, Vector tuples, ResultCursor cursor)
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

	//
	// ResultHandler implementations for updates, queries, and either-or.
	//

	public class StatementResultHandler implements ResultHandler {
		private SQLException error;
		private ResultWrapper results;

		ResultWrapper getResults() { return results; }

		private void append(ResultWrapper newResult) {
			if (results == null)
				results = newResult;
			else
				results.append(newResult);
		}

		public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
			try {
				ResultSet rs = AbstractJdbc2Statement.this.createResultSet(fromQuery, fields, tuples, cursor);
				append(new ResultWrapper(rs));
			} catch (SQLException e) {
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
			throw new PSQLException(GT.tr("Can't use query methods that take a query string on a PreparedStatement."));

		if (!executeWithFlags(p_sql, 0))
			throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

		if (result.getNext() != null)
			throw new PSQLException(GT.tr("Multiple ResultSets were returned by the query."));

		return (ResultSet)result.getResultSet();
	}

	/*
	 * A Prepared SQL query is executed and its ResultSet is returned
	 *
	 * @return a ResultSet that contains the data produced by the
	 *		 *	query - never null
	 * @exception SQLException if a database access error occurs
	 */
	public java.sql.ResultSet executeQuery() throws SQLException
	{
		if (!executeWithFlags(0))
			throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

		if (result.getNext() != null)
			throw new PSQLException(GT.tr("Multiple ResultSets were returned by the query."));

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
			throw new PSQLException(GT.tr("Can't use query methods that take a query string on a PreparedStatement."));

		if (executeWithFlags(p_sql, QueryExecutor.QUERY_NO_RESULTS))
			throw new PSQLException(GT.tr("A result was returned when none was expected."));

		return getUpdateCount();
	}

	/*
	 * Execute a SQL INSERT, UPDATE or DELETE statement.  In addition,
	 * SQL statements that return nothing such as SQL DDL statements can
	 * be executed.
	 *
	 * @return either the row count for INSERT, UPDATE or DELETE; or
	 *		 *	0 for SQL statements that return nothing.
	 * @exception SQLException if a database access error occurs
	 */
	public int executeUpdate() throws SQLException
	{
		if (executeWithFlags(QueryExecutor.QUERY_NO_RESULTS))
			throw new PSQLException(GT.tr("A result was returned when none was expected."));

		return getUpdateCount();
	}

	/*
	 * Execute a SQL statement that may return multiple results. We
	 * don't have to worry about this since we do not support multiple
	 * ResultSets.	 You can use getResultSet or getUpdateCount to
	 * retrieve the result.
	 *
	 * @param sql any SQL statement
	 * @return true if the next result is a ResulSet, false if it is
	 *	an update count or there are no more results
	 * @exception SQLException if a database access error occurs
	 */
	public boolean execute(String p_sql) throws SQLException
	{
		if (preparedQuery != null)
			throw new PSQLException(GT.tr("Can't use query methods that take a query string on a PreparedStatement."));

		return executeWithFlags(p_sql, 0);
	}

	public boolean executeWithFlags(String p_sql, int flags) throws SQLException
	{
        checkClosed();
		p_sql = replaceProcessing(p_sql);
		Query simpleQuery = connection.getQueryExecutor().createSimpleQuery(p_sql);
		execute(simpleQuery, null, QueryExecutor.QUERY_ONESHOT | flags);
		return (result != null && result.getResultSet() != null);
	}

	public boolean execute() throws SQLException
	{
		return executeWithFlags(0);
	}

	public boolean executeWithFlags(int flags) throws SQLException
	{
        checkClosed();
		if (isFunction && !returnTypeSet)
			throw new PSQLException(GT.tr("A CallableStatement was declared, but no call to registerOutParameter(1, <some type>) was made."), PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);

		execute(preparedQuery, preparedParameters, flags);

		// If we are executing a callable statement function set the return data
		if (isFunction)
		{
			if (result == null || result.getResultSet() == null)
				throw new PSQLException(GT.tr("A CallableStatement was executed with nothing returned."), PSQLState.NO_DATA);

			ResultSet rs = result.getResultSet();
			if (!rs.next())
				throw new PSQLException(GT.tr("A CallableStatement was executed with nothing returned."), PSQLState.NO_DATA);

			callResult = rs.getObject(1);
			int columnType = rs.getMetaData().getColumnType(1);
			if (columnType != functionReturnType)
				throw new PSQLException (GT.tr("A CallableStatement function was executed and the return was of type {0} however type {1} was registered.",
										 new Object[]{
											 "java.sql.Types=" + columnType, "java.sql.Types=" + functionReturnType }),
							PSQLState.DATA_TYPE_MISMATCH);

			rs.close();
			result = null;
			return false;
		}

		return (result != null && result.getResultSet() != null);
	}

	protected void execute(Query queryToExecute, ParameterList queryParameters, int flags) throws SQLException {
		// Close any existing resultsets associated with this statement.
		while (firstUnclosedResult != null) {
			if (firstUnclosedResult.getResultSet() != null)
				firstUnclosedResult.getResultSet().close();
			firstUnclosedResult = firstUnclosedResult.getNext();
		}


		// Enable cursor-based resultset if possible.
		if (fetchSize > 0 && !wantsScrollableResultSet() && !connection.getAutoCommit())
			flags |= QueryExecutor.QUERY_FORWARD_CURSOR;

		// Only use named statements after we hit the threshold
		if (preparedQuery != null) {
			++m_useCount; // We used this statement once more.
			if (m_prepareThreshold == 0 || m_useCount < m_prepareThreshold)
				flags |= QueryExecutor.QUERY_ONESHOT;
		}

		if (connection.getAutoCommit())
			flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;

		StatementResultHandler handler = new StatementResultHandler();
		result = null;
		connection.getQueryExecutor().execute(queryToExecute,
											  queryParameters,
											  handler,
											  maxrows,
											  fetchSize,
											  flags);
		result = firstUnclosedResult = handler.getResults();
	}

	/*
	 * setCursorName defines the SQL cursor name that will be used by
	 * subsequent execute methods.	This name can then be used in SQL
	 * positioned update/delete statements to identify the current row
	 * in the ResultSet generated by this statement.  If a database
	 * doesn't support positioned update/delete, this method is a
	 * no-op.
	 *
	 * <p><B>Note:</B> By definition, positioned update/delete execution
	 * must be done by a different Statement than the one which
	 * generated the ResultSet being used for positioning.	Also, cursor
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

	private boolean isClosed = false;

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
		if (result == null)
			return -1;

		if (isFunction)
			return 1;

		if (result.getResultSet() != null)
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
		while (firstUnclosedResult != result) {
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
		if (max<0)
			throw new PSQLException(GT.tr("Maximum number of rows must be a value grater than or equal to 0."));
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
		checkClosed();
		return timeout;
	}

	/*
	 * Sets the queryTimeout limit
	 *
	 * @param seconds - the new query timeout limit in seconds
	 * @exception SQLException if a database access error occurs
	 */
	public void setQueryTimeout(int seconds) throws SQLException
	{
		checkClosed();
		if (seconds<0)
			throw new PSQLException(GT.tr("Query timeout must be a value greater than or equals to 0."));
		timeout = seconds;
	}

	/**
	 * This adds a warning to the warning chain.
	 * @param warn warning to add
	 */
	public void addWarning(SQLWarning warn)
	{
		if (warnings != null)
			warnings.setNextWarning(warn);
		else
			warnings = warn;
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
	 * <p><B>Note:</B>	If you are processing a ResultSet then any warnings
	 * associated with ResultSet reads will be chained on the ResultSet
	 * object.
	 *
	 * @return the first SQLWarning on null
	 * @exception SQLException if a database access error occurs
	 */
	public SQLWarning getWarnings() throws SQLException
	{
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
			throw new PSQLException(GT.tr("The maximum field size must be a value greater than or equal to 0."));
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
	}

	/*
	 * getResultSet returns the current result as a ResultSet.	It
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
	 * for this to happen when it is automatically closed.	The
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
			return;

		// Force the ResultSet(s) to close
		while (firstUnclosedResult != null) {
			if (firstUnclosedResult.getResultSet() != null)
				firstUnclosedResult.getResultSet().close();
			firstUnclosedResult = firstUnclosedResult.getNext();
		}


		if (preparedQuery != null)
			preparedQuery.close();

		// Disasociate it from us
		result = firstUnclosedResult = null;
		isClosed = true;
	}

 	/**
 	 * This finalizer ensures that statements that have allocated server-side
 	 * resources free them when they become unreferenced.
 	 */
 	protected void finalize() {
 		try { close(); }
 		catch (SQLException e) {}
 	}

	/*
	 * Filter the SQL string of Java SQL Escape clauses.
	 *
	 * Currently implemented Escape clauses are those mentioned in 11.3
	 * in the specification. Basically we look through the sql string for
	 * {d xxx}, {t xxx} or {ts xxx} in non-string sql code. When we find
	 * them, we just strip the escape part leaving only the xxx part.
	 * So, something like "select * from x where d={d '2001-10-09'}" would
	 * return "select * from x where d= '2001-10-09'".
	 */
	protected String replaceProcessing(String p_sql)
	{
		if (replaceProcessingEnabled)
		{
			// Since escape codes can only appear in SQL CODE, we keep track
			// of if we enter a string or not.
			StringBuffer newsql = new StringBuffer(p_sql.length());
			short state = IN_SQLCODE;

			int i = -1;
			int len = p_sql.length();
			while (++i < len)
			{
				char c = p_sql.charAt(i);
				switch (state)
				{
					case IN_SQLCODE:
						if (c == '\'')				  // start of a string?
							state = IN_STRING;
						else if (c == '{')			  // start of an escape code?
							if (i + 1 < len)
							{
								char next = p_sql.charAt(i + 1);
								if (next == 'd')
								{
									state = ESC_TIMEDATE;
									i++;
									break;
								}
								else if (next == 't')
								{
									state = ESC_TIMEDATE;
									i += (i + 2 < len && p_sql.charAt(i + 2) == 's') ? 2 : 1;
									break;
								}
                                else if ( next == 'f')
                                {
                                    state = ESC_FUNCTION;
                                    i +=
                                        (i + 2 < len && p_sql.charAt(i + 2) == 'n') ?
                                        2 : 1;
                                    break;
                                }
                                else if ( next == 'o' )
                                {
                                    state = ESC_OUTERJOIN;
                                    i +=
                                        (i + 2 < len && p_sql.charAt(i + 2) == 'j') ?
                                        2 : 1;
                                    break;
                                }

							}
						newsql.append(c);
						break;

					case IN_STRING:
						if (c == '\'')				   // end of string?
							state = IN_SQLCODE;
						else if (c == '\\')			   // a backslash?
							state = BACKSLASH;

						newsql.append(c);
						break;

					case BACKSLASH:
						state = IN_STRING;

						newsql.append(c);
						break;

					case ESC_TIMEDATE:
                    case ESC_FUNCTION:
                    case ESC_OUTERJOIN:
						if (c == '}')
							state = IN_SQLCODE;		  // end of escape code.
						else
							newsql.append(c);
						break;
				} // end switch
			}

			return newsql.toString();
		}
		else
		{
			return p_sql;
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
	 * <p><B>Note:</B> You must specify the parameters SQL type (although
	 * PostgreSQL ignores it)
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
			case Types.FLOAT:
				oid = Oid.FLOAT4;
				break;
			case Types.DOUBLE:
				oid = Oid.FLOAT8;
				break;
			case Types.DECIMAL:
			case Types.NUMERIC:
				oid = Oid.NUMERIC;
				break;
			case Types.CHAR:
			case Types.VARCHAR:
			case Types.LONGVARCHAR:
				oid = Oid.TEXT;
				break;
			case Types.DATE:
				oid = Oid.DATE;
				break;
			case Types.TIME:
				oid = Oid.TIME;
				break;
			case Types.TIMESTAMP:
				oid = Oid.TIMESTAMPTZ;
				break;
			case Types.BIT:
				oid = Oid.BOOL;
				break;
			case Types.BINARY:
			case Types.VARBINARY:
			case Types.LONGVARBINARY:
				oid = Oid.BYTEA;
				break;
			case Types.OTHER:
			default:
				oid = 0;
				break;
		}

		preparedParameters.setNull(adjustParamIndex(parameterIndex), oid);
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
        checkClosed();
		bindLiteral(parameterIndex, Integer.toString(x), Oid.INT2);
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
		bindLiteral(parameterIndex, Float.toString(x), Oid.FLOAT4);
	}

	/*
	 * Set a parameter to a Java double value.	The driver converts this
	 * to a SQL DOUBLE value when it sends it to the database
	 *
	 * @param parameterIndex the first parameter is 1...
	 * @param x the parameter value
	 * @exception SQLException if a database access error occurs
	 */
	public void setDouble(int parameterIndex, double x) throws SQLException
	{
		checkClosed();
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
		{
			bindLiteral(parameterIndex, x.toString(), Oid.NUMERIC);
		}
	}

	/*
	 * Set a parameter to a Java String value.	The driver converts this
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
		setString(parameterIndex, x, Oid.TEXT);
	}

	protected void setString(int parameterIndex, String x, int oid) throws SQLException
	{
		// if the passed string is null, then set this column to null
		checkClosed();
		if (x == null)
			preparedParameters.setNull(adjustParamIndex(parameterIndex), oid);
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

		if (null == x) {
			setNull(parameterIndex, Types.VARBINARY);
			return;
		}

		if (connection.haveMinimumCompatibleVersion("7.2"))
		{
			//Version 7.2 supports the bytea datatype for byte arrays
			byte[] copy = new byte[x.length];
			System.arraycopy(x, 0, copy, 0, x.length);
			preparedParameters.setBytea(adjustParamIndex(parameterIndex), copy, 0, x.length);
		}
		else
		{
			//Version 7.1 and earlier support done as LargeObjects
			LargeObjectManager lom = connection.getLargeObjectAPI();
			int oid = lom.create();
			LargeObject lob = lom.open(oid);
			lob.write(x);
			lob.close();
			setInt(parameterIndex, oid);
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
		checkClosed();
		if (null == x)
		{
			setNull(parameterIndex, Types.DATE);
		}
		else
		{
			bindString(parameterIndex, x.toString(), Oid.DATE);
		}
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
		checkClosed();
		if (null == x)
		{
			setNull(parameterIndex, Types.TIME);
		}
		else
		{
			bindString(parameterIndex, x.toString(), Oid.TIME);
		}
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
		checkClosed();
		if (null == x)
		{
			setNull(parameterIndex, Types.TIMESTAMP);
		}
		else
		{
			// Use the shared StringBuffer
			synchronized (sbuf)
			{
				sbuf.setLength(0);
				sbuf.ensureCapacity(32);
				//format the timestamp
				//we do our own formating so that we can get a format
				//that works with both timestamp with time zone and
				//timestamp without time zone datatypes.
				//The format is '2002-01-01 23:59:59.123456-0130'
				//we need to include the local time and timezone offset
				//so that timestamp without time zone works correctly
				int l_year = x.getYear() + 1900;

				// always use four digits for the year so very
				// early years, like 2, don't get misinterpreted
				int l_yearlen = String.valueOf(l_year).length();
				for (int i=4; i>l_yearlen; i--) {
					sbuf.append("0");
				}

				sbuf.append(l_year);
				sbuf.append('-');
				int l_month = x.getMonth() + 1;
				if (l_month < 10)
					sbuf.append('0');
				sbuf.append(l_month);
				sbuf.append('-');
				int l_day = x.getDate();
				if (l_day < 10)
					sbuf.append('0');
				sbuf.append(l_day);
				sbuf.append(' ');
				int l_hours = x.getHours();
				if (l_hours < 10)
					sbuf.append('0');
				sbuf.append(l_hours);
				sbuf.append(':');
				int l_minutes = x.getMinutes();
				if (l_minutes < 10)
					sbuf.append('0');
				sbuf.append(l_minutes);
				sbuf.append(':');
				int l_seconds = x.getSeconds();
				if (l_seconds < 10)
					sbuf.append('0');
				sbuf.append(l_seconds);
				// Make decimal from nanos.
				char[] l_decimal = {'0', '0', '0', '0', '0', '0', '0', '0', '0'};
				char[] l_nanos = Integer.toString(x.getNanos()).toCharArray();
				System.arraycopy(l_nanos, 0, l_decimal, l_decimal.length - l_nanos.length, l_nanos.length);
				sbuf.append('.');
				if (connection.haveMinimumServerVersion("7.2"))
				{
					sbuf.append(l_decimal, 0, 6);
				}
				else
				{
					// Because 7.1 include bug that "hh:mm:59.999" becomes "hh:mm:60.00".
					sbuf.append(l_decimal, 0, 2);
				}
				//add timezone offset
				int l_offset = -(x.getTimezoneOffset());
				int l_houros = l_offset / 60;
				if (l_houros >= 0)
				{
					sbuf.append('+');
				}
				else
				{
					sbuf.append('-');
				}
				if (l_houros > -10 && l_houros < 10)
					sbuf.append('0');
				if (l_houros >= 0)
				{
					sbuf.append(l_houros);
				}
				else
				{
					sbuf.append(-l_houros);
				}
				int l_minos = l_offset - (l_houros * 60);
				if (l_minos != 0)
				{
					if (l_minos > -10 && l_minos < 10)
						sbuf.append('0');
					if (l_minos >= 0)
					{
						sbuf.append(l_minos);
					}
					else
					{
						sbuf.append(-l_minos);
					}
				}
				bindString(parameterIndex, sbuf.toString(), Oid.TIMESTAMPTZ);
			}

		}
	}

	private void setCharacterStreamPost71(int parameterIndex, InputStream x, int length, String encoding) throws SQLException
	{

		if (x == null)
		{
			setNull(parameterIndex, Types.VARCHAR);
				return;
		}
		if (length < 0)
			throw new PSQLException(GT.tr("Invalid stream length {0}.", new Integer(length)));


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

			setString(parameterIndex, new String(l_chars, 0, l_charsRead), Oid.TEXT);
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
		if (connection.haveMinimumCompatibleVersion("7.2"))
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
		if (connection.haveMinimumCompatibleVersion("7.2"))
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

		if (x == null) {
			setNull(parameterIndex, Types.VARBINARY);
			return;
		}

		if (length < 0)
			throw new PSQLException(GT.tr("Invalid stream length {0}.", new Integer(length)));

		if (connection.haveMinimumCompatibleVersion("7.2"))
		{
			//Version 7.2 supports BinaryStream for for the PG bytea type
			//As the spec/javadoc for this method indicate this is to be used for
			//large binary values (i.e. LONGVARBINARY)	PG doesn't have a separate
			//long binary datatype, but with toast the bytea datatype is capable of
			//handling very large values.

			preparedParameters.setBytea(adjustParamIndex(parameterIndex), x, length);
		}
		else
		{
			//Version 7.1 only supported streams for LargeObjects
			//but the jdbc spec indicates that streams should be
			//available for LONGVARBINARY instead
			LargeObjectManager lom = connection.getLargeObjectAPI();
			int oid = lom.create();
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
			setInt(parameterIndex, oid);
		}
	}


	/*
	 * In general, parameter values remain in force for repeated used of a
	 * Statement.  Setting a parameter value automatically clears its
	 * previous value.	However, in coms cases, it is useful to immediately
	 * release the resources used by the current parameter values; this
	 * can be done by calling clearParameters
	 *
	 * @exception SQLException if a database access error occurs
	 */
	public void clearParameters() throws SQLException
	{
		preparedParameters.clear();
	}

	// Helper method that extracts numeric values from an arbitary Object.
	private String numericValueOf(Object x)
	{
		if (x instanceof Boolean)
			return ((Boolean)x).booleanValue() ? "1" :"0";
		else if (x instanceof Integer || x instanceof Long ||
				 x instanceof Double || x instanceof Short ||
				 x instanceof Number || x instanceof Float)
			return x.toString();
		else
			//ensure the value is a valid numeric value to avoid
			//sql injection attacks
			return new BigDecimal(x.toString()).toString();
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
	 *		 *	types this is the number of digits after the decimal.  For
	 *		 *	all other types this value will be ignored.
	 * @exception SQLException if a database access error occurs
	 */
	public void setObject(int parameterIndex, Object x, int targetSqlType, int scale) throws SQLException
	{
		checkClosed();
		if (x == null)
		{
			setNull(parameterIndex, targetSqlType);
			return ;
		}
		switch (targetSqlType)
		{
			case Types.INTEGER:
				bindLiteral(parameterIndex, numericValueOf(x), Oid.INT4);
				break;
			case Types.TINYINT:
			case Types.SMALLINT:
				bindLiteral(parameterIndex, numericValueOf(x), Oid.INT2);
				break;
			case Types.BIGINT:
				bindLiteral(parameterIndex, numericValueOf(x), Oid.INT8);
				break;
			case Types.REAL:
			case Types.FLOAT:
				bindLiteral(parameterIndex, numericValueOf(x), Oid.FLOAT4);
				break;
			case Types.DOUBLE:
				bindLiteral(parameterIndex, numericValueOf(x), Oid.FLOAT8);
				break;
			case Types.DECIMAL:
			case Types.NUMERIC:
				bindLiteral(parameterIndex, numericValueOf(x), Oid.NUMERIC);
				break;
			case Types.CHAR:
			case Types.VARCHAR:
			case Types.LONGVARCHAR:
				setString(parameterIndex, x.toString());
				break;
			case Types.DATE:
				if (x instanceof java.sql.Date)
					setDate(parameterIndex, (java.sql.Date)x);
				else
				{
					java.sql.Date tmpd = (x instanceof java.util.Date) ? new java.sql.Date(((java.util.Date)x).getTime()) : dateFromString(x.toString());
					setDate(parameterIndex, tmpd);
				}
				break;
			case Types.TIME:
				if (x instanceof java.sql.Time)
					setTime(parameterIndex, (java.sql.Time)x);
				else
				{
					java.sql.Time tmpt = (x instanceof java.util.Date) ? new java.sql.Time(((java.util.Date)x).getTime()) : timeFromString(x.toString());
					setTime(parameterIndex, tmpt);
				}
				break;
			case Types.TIMESTAMP:
				if (x instanceof java.sql.Timestamp)
					setTimestamp(parameterIndex ,(java.sql.Timestamp)x);
				else
				{
					java.sql.Timestamp tmpts = (x instanceof java.util.Date) ? new java.sql.Timestamp(((java.util.Date)x).getTime()) : timestampFromString(x.toString());
					setTimestamp(parameterIndex, tmpts);
				}
				break;
			case Types.BIT:
				if (x instanceof Boolean)
				{
					bindString(parameterIndex, ((Boolean)x).booleanValue() ? "1" : "0", Oid.BOOL);
				}
				else if (x instanceof String)
				{
					bindString(parameterIndex, Boolean.valueOf(x.toString()).booleanValue() ? "1" : "0", Oid.BOOL);
				}
				else if (x instanceof Number)
				{
					bindString(parameterIndex, ((Number)x).intValue()!=0 ? "1" : "0", Oid.BOOL);
				}
				else
				{
					throw new PSQLException(GT.tr("Unknown Types value."), PSQLState.INVALID_PARAMETER_TYPE);
				}
				break;
			case Types.BINARY:
			case Types.VARBINARY:
			case Types.LONGVARBINARY:
				setObject(parameterIndex, x);
				break;
			case Types.OTHER:
				if (x instanceof PGobject)
					setString(parameterIndex, ((PGobject)x).getValue(), connection.getPGType( ((PGobject)x).getType() ));
				else
					throw new PSQLException(GT.tr("Unknown Types value."), PSQLState.INVALID_PARAMETER_TYPE);
				break;
			default:
				throw new PSQLException(GT.tr("Unknown Types value."), PSQLState.INVALID_PARAMETER_TYPE);
		}
	}

	public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException
	{
		setObject(parameterIndex, x, targetSqlType, 0);
	}

	/*
	 * This stores an Object into a parameter.
	 */
	public void setObject(int parameterIndex, Object x) throws SQLException
	{
		checkClosed();
		if (x == null)
		{
			setNull(parameterIndex, Types.OTHER);
			return;
		}
		if (x instanceof String)
			setString(parameterIndex, (String)x);
		else if (x instanceof BigDecimal)
			setBigDecimal(parameterIndex, (BigDecimal)x);
		else if (x instanceof Short)
			setShort(parameterIndex, ((Short)x).shortValue());
		else if (x instanceof Integer)
			setInt(parameterIndex, ((Integer)x).intValue());
		else if (x instanceof Long)
			setLong(parameterIndex, ((Long)x).longValue());
		else if (x instanceof Float)
			setFloat(parameterIndex, ((Float)x).floatValue());
		else if (x instanceof Double)
			setDouble(parameterIndex, ((Double)x).doubleValue());
		else if (x instanceof byte[])
			setBytes(parameterIndex, (byte[])x);
		else if (x instanceof java.sql.Date)
			setDate(parameterIndex, (java.sql.Date)x);
		else if (x instanceof Time)
			setTime(parameterIndex, (Time)x);
		else if (x instanceof Timestamp)
			setTimestamp(parameterIndex, (Timestamp)x);
		else if (x instanceof Boolean)
			setBoolean(parameterIndex, ((Boolean)x).booleanValue());
		else if (x instanceof PGobject)
			setString(parameterIndex, ((PGobject)x).getValue(), connection.getPGType(((PGobject)x).getType()));
		else
			// Try to store as a string in database
			setString(parameterIndex, x.toString(), 0);
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
	public void registerOutParameter(int parameterIndex, int sqlType) throws SQLException
	{
		checkClosed();
		if (!isFunction)
			throw new PSQLException (GT.tr("This statement does not declare an OUT parameter.  Use '{' ?= call ... '}' to declare one."), PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
		checkIndex(parameterIndex);

		// functionReturnType contains the user supplied value to check
		// testReturn contains a modified version to make it easier to
		// check the getXXX methods..
		functionReturnType = sqlType;
		testReturn = sqlType;
		if (functionReturnType == Types.CHAR ||
				functionReturnType == Types.LONGVARCHAR)
			testReturn = Types.VARCHAR;
		else if (functionReturnType == Types.FLOAT)
			testReturn = Types.REAL; // changes to streamline later error checking
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
									 int scale) throws SQLException
	{
		registerOutParameter (parameterIndex, sqlType); // ignore for now..
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
		// check to see if the last access threw an exception
		return (callResult == null);
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
		return (String)callResult;
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
		if (callResult == null)
			return false;
		return ((Boolean)callResult).booleanValue ();
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
		checkIndex (parameterIndex, Types.TINYINT, "Byte");
		// We expect the above checkIndex call to fail because
		// we don't have an equivalent pg type for TINYINT.
		// Possibly "char" (not char(N)), could be used, but
		// for the moment we just bail out.
		throw new PSQLException(GT.tr("Something unusual has occured to cause the driver to fail. Please report this exception."), PSQLState.UNEXPECTED_ERROR);
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
		if (callResult == null)
			return 0;
		return (short)((Short)callResult).intValue ();
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
		if (callResult == null)
			return 0;
		return ((Integer)callResult).intValue ();
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
		if (callResult == null)
			return 0;
		return ((Long)callResult).longValue ();
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
		if (callResult == null)
			return 0;
		return ((Float)callResult).floatValue ();
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
		if (callResult == null)
			return 0;
		return ((Double)callResult).doubleValue ();
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
		return ((BigDecimal)callResult);
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
		return ((byte [])callResult);
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
		return (java.sql.Date)callResult;
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
		return (java.sql.Time)callResult;
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
		return (java.sql.Timestamp)callResult;
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
		return callResult;
	}

	/*
	 * Returns the SQL statement with the current template values
	 * substituted.
	 */
	public String toString()
	{
		if (preparedQuery == null)
			return toString();

		return preparedQuery.toString(preparedParameters);
	}

	private int adjustParamIndex(int paramIndex) throws SQLException {
		if (!isFunction)
			return paramIndex;

		if (paramIndex == 1) // need to registerOut instead
			throw new PSQLException (GT.tr("Cannot call setXXX(1, ..) on a CallableStatement.  This is an output that must be configured with registerOutParameter instead."));

		return paramIndex - 1;
	}

	/*
     * Note if s is a String it should be escaped by the caller to avoid SQL
     * injection attacks.  It is not done here for efficency reasons as
     * most calls to this method do not require escaping as the source
     * of the string is known safe (i.e. Integer.toString())
	 */
	private void bindLiteral(int paramIndex, String s, int oid) throws SQLException
	{
		preparedParameters.setLiteralParameter(adjustParamIndex(paramIndex), s, oid);
	}

	/*
	 * This version is for values that should turn into strings
	 * e.g. setString directly calls bindString with no escaping;
	 * the per-protocol ParameterList does escaping as needed.
	 */
	private void bindString(int paramIndex, String s, int oid) throws SQLException
	{
		preparedParameters.setStringParameter(adjustParamIndex(paramIndex), s, oid);
	}

	/**
	 * this method will turn a string of the form
	 * { [? =] call <some_function> [(?, [?,..])] }
	 * into the PostgreSQL format which is
	 * select <some_function> (?, [?, ...]) as result
	 * or select * from <some_function> (?, [?, ...]) as result (7.3)
	 */
	private String modifyJdbcCall(String p_sql) throws SQLException
	{
		checkClosed();

		// Mini-parser for JDBC function-call syntax (only)
		// TODO: Merge with escape processing (and parameter parsing?)
		// so we only parse each query once.

		isFunction = false;

		int len = p_sql.length();
		int state = 1;
		boolean inQuotes = false, inEscape = false;

		int startIndex = -1, endIndex = -1;
		boolean syntaxError = false;
		int i = 0;

		while (i < len && !syntaxError) {
			char ch = p_sql.charAt(i);

			switch (state) {
			case 1: // Looking for { at start of query
				if (ch == '{') {
					++i;
					++state;
				} else if (Character.isWhitespace(ch)) {
					++i;
				} else {
					// Not function-call syntax. Skip the rest of the string.
					i = len;
				}
				break;

			case 2: // After {, looking for ? or =, skipping whitespace
				if (ch == '?') {
					isFunction = true;   // { ? = call ... }  -- function with one out parameter
					++i;
					++state;
				} else if (ch == 'c') {  // { call ... }      -- proc with no out parameters
					state += 3; // Don't increase 'i'
				} else if (Character.isWhitespace(ch)) {
					++i;
				} else {
					// "{ foo ...", doesn't make sense, complain.
					syntaxError = true;
				}
				break;

			case 3: // Looking for = after ?, skipping whitespace
				if (ch == '=') {
					++i;
					++state;
				} else if (Character.isWhitespace(ch)) {
					++i;
				} else {
					syntaxError = true;
				}
				break;

			case 4: // Looking for 'call' after '? =' skipping whitespace
				if (ch == 'c' || ch == 'C') {
					++state; // Don't increase 'i'.
				} else if (Character.isWhitespace(ch)) {
					++i;
				} else {
					syntaxError = true;
				}
				break;

			case 5: // Should be at 'call ' either at start of string or after ?=
				if ((ch == 'c' || ch == 'C') && i+4 <= len && p_sql.substring(i, i+4).equalsIgnoreCase("call")) {
					i += 4;
					++state;
				} else if (Character.isWhitespace(ch)) {
					++i;
				} else {
					syntaxError = true;
				}
				break;

			case 6: // Looking for whitespace char after 'call'
				if (Character.isWhitespace(ch)) {
					// Ok, we found the start of the real call.
					++i;
					++state;
					startIndex = i;
				} else {
					syntaxError = true;
				}
				break;

			case 7: // In "body" of the query (after "{ [? =] call ")
				if (ch == '\'') {
					inQuotes = !inQuotes;
					++i;
				} else if (inQuotes && ch == '\\') {
					// Backslash in string constant, skip next character.
					i += 2;
				} else if (!inQuotes && ch == '{') {
					inEscape = !inEscape;
					++i;
				} else if (!inQuotes && ch == '}') {
					if (!inEscape) {
						// Should be end of string.
						endIndex = i;
						++i;
						++state;
					} else {
						inEscape = false;
					}
				} else if (!inQuotes && ch == ';') {
					syntaxError = true;
				} else {
					// Everything else is ok.
					++i;
				}
				break;

			case 8: // At trailing end of query, eating whitespace
				if (Character.isWhitespace(ch)) {
					++i;
				} else {
					syntaxError = true;
				}
				break;

			default:
				throw new IllegalStateException("somehow got into bad state " + state);
			}
		}

		// We can only legally end in a couple of states here.
		if (i == len && !syntaxError) {
			if (state == 1)
				return p_sql; // Not an escaped syntax.
			if (state != 8)
				syntaxError = true; // Ran out of query while still parsing
		}

		if (syntaxError)
			throw new PSQLException (GT.tr("Malformed function or procedure escape syntax at offset {0}.", new Integer(i)),
					PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);

		if (connection.haveMinimumServerVersion("7.3")) {
			return "select * from " + p_sql.substring(startIndex, endIndex) + " as result";
		} else {
			return "select " + p_sql.substring(startIndex, endIndex) + " as result";
		}
	}

	/** helperfunction for the getXXX calls to check isFunction and index == 1
	 * Compare BOTH type fields against the return type.
	 */
	protected void checkIndex (int parameterIndex, int type1, int type2, String getName)
	throws SQLException
	{
		checkIndex (parameterIndex);
		if (type1 != this.testReturn && type2 != this.testReturn)
			throw new PSQLException(GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
						new Object[]{"java.sql.Types=" + testReturn,
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
		if (type != this.testReturn)
			throw new PSQLException(GT.tr("Parameter of type {0} was registered, but call to get{1} (sqltype={2}) was made.",
						new Object[]{"java.sql.Types=" + testReturn,
							     getName,
							     "java.sql.Types=" + type}),
						PSQLState.MOST_SPECIFIC_TYPE_DOES_NOT_MATCH);
	}

	/** helperfunction for the getXXX calls to check isFunction and index == 1
	 * @param parameterIndex index of getXXX (index)
	 * check to make sure is a function and index == 1
	 */
	private void checkIndex (int parameterIndex) throws SQLException
	{
		if (!isFunction)
			throw new PSQLException(GT.tr("A CallableStatement was declared, but no call to registerOutParameter(1, <some type>) was made."), PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
		if (parameterIndex != 1)
			throw new PSQLException (GT.tr("PostgreSQL only supports a single OUT function return value at index 1."), PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
	}

	public void setPrepareThreshold(int newThreshold) throws SQLException {
		checkClosed();

		if (newThreshold < 0)
			newThreshold = 0;

		this.m_prepareThreshold = newThreshold;
	}

	public int getPrepareThreshold() {
		return m_prepareThreshold;
	}

	public void setUseServerPrepare(boolean flag) throws SQLException {
		setPrepareThreshold(flag ? 1 : 0);
	}

	public boolean isUseServerPrepare() {
		return (preparedQuery != null && m_prepareThreshold != 0 && m_useCount+1 >= m_prepareThreshold);
	}

	protected void checkClosed() throws SQLException
	{
		//need to add to errors.properties.
		if (isClosed)
			throw new PSQLException(GT.tr("This statement has been closed."));
	}

	private java.sql.Date dateFromString (String s) throws SQLException
	{
		int timezone = 0;
		long millis = 0;
		long localoffset = 0;
		int timezoneLocation = (s.indexOf('+') == -1) ? s.lastIndexOf("-") : s.indexOf('+');
		//if the last index of '-' or '+' is past 8. we are guaranteed that it is a timezone marker
		//shortest = yyyy-m-d
		//longest = yyyy-mm-dd
		try
		{
			timezone = (timezoneLocation>7) ? timezoneLocation : s.length();
			millis = java.sql.Date.valueOf(s.substring(0,timezone)).getTime();
		}
		catch (Exception e)
		{
			throw new PSQLException(GT.tr("The given date {0} does not match the format required: {1}.",
						new Object[] { s , "yyyy-MM-dd[-tz]" }),
						PSQLState.BAD_DATETIME_FORMAT, e);
		}
		timezone = 0;
		if (timezoneLocation>7 && timezoneLocation+3 == s.length())
		{
			timezone = Integer.parseInt(s.substring(timezoneLocation+1,s.length()));
			localoffset = java.util.Calendar.getInstance().getTimeZone().getRawOffset();
			if (java.util.Calendar.getInstance().getTimeZone().inDaylightTime(new java.sql.Date(millis)))
				localoffset += 60*60*1000;
			if (s.charAt(timezoneLocation)=='+')
				timezone*=-1;
		}
		millis = millis + timezone*60*60*1000 + localoffset;
		return new java.sql.Date(millis);
	}

	private java.sql.Time timeFromString (String s) throws SQLException
	{
		int timezone = 0;
		long millis = 0;
		long localoffset = 0;
		int timezoneLocation = (s.indexOf('+') == -1) ? s.lastIndexOf("-") : s.indexOf('+');
		//if the index of the last '-' or '+' is greater than 0 that means this time has a timezone.
		//everything earlier than that position, we treat as the time and parse it as such.
		try
		{
			timezone = (timezoneLocation==-1) ? s.length() : timezoneLocation;
			millis = java.sql.Time.valueOf(s.substring(0,timezone)).getTime();
		}
		catch (Exception e)
		{
			throw new PSQLException(GT.tr("The time given {0} does not match the format required: {1}.",
						new Object[] { s, "HH:mm:ss[-tz]" }),
						PSQLState.BAD_DATETIME_FORMAT, e);
		}
		timezone = 0;
		if (timezoneLocation != -1 && timezoneLocation+3 == s.length())
		{
			timezone = Integer.parseInt(s.substring(timezoneLocation+1,s.length()));
			localoffset = java.util.Calendar.getInstance().getTimeZone().getRawOffset();
			if (java.util.Calendar.getInstance().getTimeZone().inDaylightTime(new java.sql.Date(millis)))
				localoffset += 60*60*1000;
			if (s.charAt(timezoneLocation)=='+')
				timezone*=-1;
		}
		millis = millis + timezone*60*60*1000 + localoffset;
		return new java.sql.Time(millis);
	}

	private java.sql.Timestamp timestampFromString (String s) throws SQLException
	{
		int timezone = 0;
		long millis = 0;
		long localoffset = 0;
		int nanosVal = 0;
		int timezoneLocation = (s.indexOf('+') == -1) ? s.lastIndexOf("-") : s.indexOf('+');
		int nanospos = s.indexOf(".");
		//if there is a '.', that means there are nanos info, and we take the timestamp up to that point
		//if not, then we check to see if the last +/- (to indicate a timezone) is greater than 8
		//8 is because the shortest date, will have last '-' at position 7. e.g yyyy-x-x
		try
		{
			if (nanospos != -1)
				timezone = nanospos;
			else if (timezoneLocation > 8)
				timezone = timezoneLocation;
			else
				timezone = s.length();
			millis = java.sql.Timestamp.valueOf(s.substring(0,timezone)).getTime();
		}
		catch (Exception e)
		{
			throw new PSQLException(GT.tr("The timestamp given {0} does not match the format required: {1}.",
						new Object[] { s, "yyyy-MM-dd HH:mm:ss[.xxxxxx][-tz]" }),
						PSQLState.BAD_DATETIME_FORMAT, e);
		}
		timezone = 0;
		if (nanospos != -1)
		{
			int tmploc = (timezoneLocation > 8) ? timezoneLocation : s.length();
			nanosVal = Integer.parseInt(s.substring(nanospos+1,tmploc));
			int diff = 8-((tmploc-1)-(nanospos+1));
			for (int i=0;i<diff;i++)
				nanosVal*=10;
		}
		if (timezoneLocation>8 && timezoneLocation+3 == s.length())
		{
			timezone = Integer.parseInt(s.substring(timezoneLocation+1,s.length()));
			localoffset = java.util.Calendar.getInstance().getTimeZone().getRawOffset();
			if (java.util.Calendar.getInstance().getTimeZone().inDaylightTime(new java.sql.Date(millis)))
				localoffset += 60*60*1000;
			if (s.charAt(timezoneLocation)=='+')
				timezone*=-1;
		}
		millis = millis + timezone*60*60*1000 + localoffset;
		java.sql.Timestamp tmpts = new java.sql.Timestamp(millis);
		tmpts.setNanos(nanosVal);
		return tmpts;
	}

	// ** JDBC 2 Extensions **

	public void addBatch(String p_sql) throws SQLException
	{
		checkClosed();

		if (preparedQuery != null)
			throw new PSQLException(GT.tr("Can't use query methods that take a query string on a PreparedStatement."));

		if (batchStatements == null) {
			batchStatements = new ArrayList();
			batchParameters = new ArrayList();
		}

		batchStatements.add(connection.getQueryExecutor().createSimpleQuery(p_sql));
		batchParameters.add(null);
	}

	public void clearBatch() throws SQLException
	{
		if (batchStatements != null) {
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

		BatchResultHandler(Query[] queries, ParameterList[] parameterLists, int[] updateCounts) {
			this.queries = queries;
			this.parameterLists = parameterLists;
			this.updateCounts = updateCounts;
		}

		public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
			handleError(new PSQLException(GT.tr("A result was returned when none was expected.")));
		}

		public void handleCommandStatus(String status, int updateCount, long insertOID) {
			if (resultIndex >= updateCounts.length) {
				handleError(new SQLException("Too many update results were returned"));
				return;
			}

			updateCounts[resultIndex++] = updateCount;
		}

		public void handleWarning(SQLWarning warning) {
			AbstractJdbc2Statement.this.addWarning(warning);
		}

		public void handleError(SQLException newError) {
			if (batchException == null) {
				int[] successCounts;

				if (resultIndex >= updateCounts.length)
					successCounts = updateCounts;
				else {
					successCounts = new int[resultIndex];
					System.arraycopy(updateCounts, 0, successCounts, 0, resultIndex);
				}

				String queryString = "<unknown>";
				if (resultIndex <= queries.length)
					queryString = queries[resultIndex].toString(parameterLists[resultIndex]);

				batchException = new BatchUpdateException(GT.tr("Batch entry {0} {1} was aborted.  Call getNextException to see the cause.",
							new Object[]{ new Integer(resultIndex),
								queryString}),
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

		if (batchStatements == null || batchStatements.isEmpty())
			return new int[0];

		int size = batchStatements.size();
		int[] updateCounts = new int[size];

		// Construct query/parameter arrays.
		Query[] queries = (Query[])batchStatements.toArray(new Query[batchStatements.size()]);
		ParameterList[] parameterLists = (ParameterList[])batchParameters.toArray(new ParameterList[batchParameters.size()]);
		batchStatements.clear();
		batchParameters.clear();

		// Close any existing resultsets associated with this statement.
		while (firstUnclosedResult != null) {
			if (firstUnclosedResult.getResultSet() != null)
				firstUnclosedResult.getResultSet().close();
			firstUnclosedResult = firstUnclosedResult.getNext();
		}

		int flags = QueryExecutor.QUERY_NO_RESULTS;

		// Only use named statements after we hit the threshold
		if (preparedQuery != null) {
			m_useCount += queries.length;
			if (m_prepareThreshold == 0 || m_useCount < m_prepareThreshold)
				flags |= QueryExecutor.QUERY_ONESHOT;
		}

		if (connection.getAutoCommit())
			flags |= QueryExecutor.QUERY_SUPPRESS_BEGIN;

		result = null;
		BatchResultHandler handler = new BatchResultHandler(queries, parameterLists, updateCounts);
		connection.getQueryExecutor().execute(queries,
											  parameterLists,
											  handler,
											  maxrows,
											  fetchSize,
											  flags);

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
		connection.cancelQuery();
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
		switch(direction) {
		case ResultSet.FETCH_FORWARD:
		case ResultSet.FETCH_REVERSE:
		case ResultSet.FETCH_UNKNOWN:
			fetchdirection = direction;
			break;
		default:
			throw new PSQLException(GT.tr("Invalid fetch direction constant: {0}.", new Integer(direction)));
		}
	}

	public void setFetchSize(int rows) throws SQLException
	{
		checkClosed();
		if (rows<0)
			throw new PSQLException(GT.tr("Fetch size must be a value greater to or equal to 0."));
		fetchSize = rows;
	}

	public void addBatch() throws SQLException
	{
		checkClosed();

		if (batchStatements == null) {
			batchStatements = new ArrayList();
			batchParameters = new ArrayList();
		}

		// we need to create copies of our parameters, otherwise the values can be changed
		batchStatements.add(preparedQuery);
		batchParameters.add(preparedParameters.copy());
	}

	public ResultSetMetaData getMetaData() throws SQLException
	{
		checkClosed();
		ResultSet rs = getResultSet();
		if (rs != null)
			return rs.getMetaData();

		// Does anyone really know what this method does?
		return null;
	}

	public void setArray(int i, java.sql.Array x) throws SQLException
	{
		checkClosed();

		// This only works for Array implementations that return a valid array
		// literal from Array.toString(), such as the implementation we return
		// from ResultSet.getArray(). Eventually we need a proper implementation
		// here that works for any Array implementation.

		// Use a typename that is "_" plus the base type; this matches how the
		// backend looks for array types.
		String typename = "_" + x.getBaseTypeName();
		int oid = connection.getPGType(typename);
		if (oid == Oid.INVALID)
			throw new PSQLException(GT.tr("Unknown type {0}.", typename), PSQLState.INVALID_PARAMETER_TYPE);

		setString(i, x.toString(), oid);
	}

	public void setBlob(int i, Blob x) throws SQLException
	{
		checkClosed();
		InputStream l_inStream = x.getBinaryStream();
		LargeObjectManager lom = connection.getLargeObjectAPI();
		int oid = lom.create();
		LargeObject lob = lom.open(oid);
		OutputStream los = lob.getOutputStream();
		byte[] buf = new byte[4096];
		try
		{
			// could be buffered, but then the OutputStream returned by LargeObject
			// is buffered internally anyhow, so there would be no performance
			// boost gained, if anything it would be worse!
			int bytesRemaining = (int)x.length();
			int numRead = l_inStream.read(buf, 0, Math.min(buf.length, bytesRemaining));
			while (numRead != -1 && bytesRemaining > 0)
			{
				bytesRemaining -= numRead;
				if ( numRead == buf.length )
					los.write(buf); // saves a buffer creation and copy in LargeObject since it's full
				else
					los.write(buf,0,numRead);
				numRead = l_inStream.read(buf, 0, Math.min(buf.length, bytesRemaining));
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
				los.close();
                l_inStream.close();
            }
            catch( Exception e ) {}
		}
		setInt(i, oid);
	}

	public void setCharacterStream(int i, java.io.Reader x, int length) throws SQLException
	{
		checkClosed();
		if (length < 0)
			throw new PSQLException(GT.tr("Invalid stream length {0}.", new Integer(length)));

		if (connection.haveMinimumCompatibleVersion("7.2"))
		{
			//Version 7.2 supports CharacterStream for for the PG text types
			//As the spec/javadoc for this method indicate this is to be used for
			//large text values (i.e. LONGVARCHAR)	PG doesn't have a separate
			//long varchar datatype, but with toast all the text datatypes are capable of
			//handling very large values.  Thus the implementation ends up calling
			//setString() since there is no current way to stream the value to the server
			char[] l_chars = new char[length];
			int l_charsRead = 0;
			try
			{
				while(true)
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
			int oid = lom.create();
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
			setInt(i, oid);
		}
	}

	public void setClob(int i, Clob x) throws SQLException
	{
		checkClosed();
		InputStream l_inStream = x.getAsciiStream();
		int l_length = (int) x.length();
		LargeObjectManager lom = connection.getLargeObjectAPI();
		int oid = lom.create();
		LargeObject lob = lom.open(oid);
		OutputStream los = lob.getOutputStream();
		try
		{
			// could be buffered, but then the OutputStream returned by LargeObject
			// is buffered internally anyhow, so there would be no performance
			// boost gained, if anything it would be worse!
			int c = l_inStream.read();
			int p = 0;
			while (c > -1 && p < l_length)
			{
				los.write(c);
				c = l_inStream.read();
				p++;
			}
			los.close();
		}
		catch (IOException se)
		{
			throw new PSQLException(GT.tr("Unexpected error writing large object to database."), PSQLState.UNEXPECTED_ERROR, se);
		}
		// lob is closed by the stream so don't call lob.close()
		setInt(i, oid);
	}

	public void setNull(int i, int t, String s) throws SQLException
	{
		checkClosed();
		setNull(i, t);
	}

	public void setRef(int i, Ref x) throws SQLException
	{
		throw Driver.notImplemented();
	}

	public void setDate(int i, java.sql.Date d, java.util.Calendar cal) throws SQLException
	{
		checkClosed();
		if (cal == null)
			setDate(i, d);
		else
		{
			cal = changeTime(d, cal, true);
			setDate(i, new java.sql.Date(cal.getTime().getTime()));
		}
	}

	public void setTime(int i, Time t, java.util.Calendar cal) throws SQLException
	{
		checkClosed();
		if (cal == null)
			setTime(i, t);
		else
		{
			cal = changeTime(t, cal, true);
			setTime(i, new java.sql.Time(cal.getTime().getTime()));
		}
	}

	public void setTimestamp(int i, Timestamp t, java.util.Calendar cal) throws SQLException
	{
		checkClosed();
		if (cal == null)
			setTimestamp(i, t);
		else
		{
			cal = changeTime(t, cal, true);
			setTimestamp(i, new java.sql.Timestamp(cal.getTime().getTime()));
		}
	}

	// ** JDBC 2 Extensions for CallableStatement**

	public java.sql.Array getArray(int i) throws SQLException
	{
		throw Driver.notImplemented();
	}

	public java.math.BigDecimal getBigDecimal(int parameterIndex) throws SQLException
	{
		checkClosed();
		checkIndex (parameterIndex, Types.NUMERIC, "BigDecimal");
		return ((BigDecimal)callResult);
	}

	public Blob getBlob(int i) throws SQLException
	{
		throw Driver.notImplemented();
	}

	public Clob getClob(int i) throws SQLException
	{
		throw Driver.notImplemented();
	}

	public Object getObjectImpl(int i, java.util.Map map) throws SQLException
	{
		throw Driver.notImplemented();
	}

	public Ref getRef(int i) throws SQLException
	{
		throw Driver.notImplemented();
	}

	public java.sql.Date getDate(int i, java.util.Calendar cal) throws SQLException
	{
		if (cal == null)
			return getDate(i);
		java.util.Date tmp = getDate(i);
		if (tmp == null)
			return null;
		cal = changeTime(tmp, cal, false);
		return new java.sql.Date(cal.getTime().getTime());
	}

	public Time getTime(int i, java.util.Calendar cal) throws SQLException
	{
		if (cal == null)
			return getTime(i);
		java.util.Date tmp = getTime(i);
		if (tmp == null)
			return null;
		cal = changeTime(tmp, cal, false);
		return new java.sql.Time(cal.getTime().getTime());
	}

	public Timestamp getTimestamp(int i, java.util.Calendar cal) throws SQLException
	{
		if (cal == null)
			return getTimestamp(i);
		java.util.Date tmp = getTimestamp(i);
		if (tmp == null)
			return null;
		cal = changeTime(tmp, cal, false);
		return new java.sql.Timestamp(cal.getTime().getTime());
	}

	// no custom types allowed yet..
	public void registerOutParameter(int parameterIndex, int sqlType, String typeName) throws SQLException
	{
		throw Driver.notImplemented();
	}


	static java.util.Calendar changeTime(java.util.Date t, java.util.Calendar cal, boolean Add)
	{
		long millis = t.getTime();
		int localoffset = t.getTimezoneOffset() * 60 * 1000 * -1;
		int caloffset = cal.getTimeZone().getRawOffset();
		if (cal.getTimeZone().inDaylightTime(t))
			millis += 60*60*1000;
		caloffset = (Add) ? (caloffset-localoffset) : -1*(caloffset-localoffset);
		java.util.Date tmpDate = new java.util.Date();
		tmpDate.setTime(millis-caloffset);
		cal.setTime(tmpDate);
//		cal.setTimeInMillis(millis-caloffset);
		tmpDate = null;
		return cal;
	}

}
