package org.postgresql.jdbc2;


import java.io.*;
import java.math.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Vector;
import org.postgresql.Driver;
import org.postgresql.largeobject.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.core.*;

/* $PostgreSQL: /cvsroot/pgsql-server/src/interfaces/jdbc/org/postgresql/jdbc2/AbstractJdbc2Statement.java,v 1.17 2003/09/09 10:49:16 barry Exp $
 * This class defines methods of the jdbc2 specification.  This class extends
 * org.postgresql.jdbc1.AbstractJdbc1Statement which provides the jdbc1
 * methods.  The real Statement class (for jdbc2) is org.postgresql.jdbc2.Jdbc2Statement
 */
public abstract class AbstractJdbc2Statement extends org.postgresql.jdbc1.AbstractJdbc1Statement
{
	protected ArrayList batchStatements = null;
	protected ArrayList batchParameters = null;
	protected final int resultsettype;		 // the resultset type to return (ResultSet.TYPE_xxx)
	protected final int concurrency;		 // is it updateable or not?     (ResultSet.CONCUR_xxx)
	protected int fetchdirection = ResultSet.FETCH_FORWARD;	 // fetch direction hint (currently ignored)

	public AbstractJdbc2Statement (AbstractJdbc2Connection c, int rsType, int rsConcurrency) throws SQLException
	{
		super(c);
		resultsettype = rsType;
		concurrency = rsConcurrency;
	}

	public AbstractJdbc2Statement(AbstractJdbc2Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency) throws SQLException
	{
		super(connection, sql, isCallable);
		resultsettype = rsType;
		concurrency = rsConcurrency;
	}

	// Overriddes JDBC1 implementation.
	protected boolean wantsScrollableResultSet() {
		return resultsettype != ResultSet.TYPE_FORWARD_ONLY;
	}

	// ** JDBC 2 Extensions **

	public void addBatch(String p_sql) throws SQLException
	{
		checkClosed();

		if (preparedQuery != null)
			throw new PSQLException("postgresql.stmt.wrongstatementtype");

		if (batchStatements == null) {
			batchStatements = new ArrayList();
			batchParameters = new ArrayList();
		}

		batchStatements.add(connection.getQueryExecutor().createSimpleQuery(p_sql));
		batchParameters.add(null);
	}

	public void clearBatch() throws SQLException
	{
		batchStatements.clear();
		batchParameters.clear();
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
			handleError(new PSQLException("postgresql.stat.result"));
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
				
				batchException = new PBatchUpdateException("postgresql.stat.batch.error",
														   new Integer(resultIndex), 
														   queryString,
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
			throw new PSQLException("postgresql.res.badfetchdirection", null, new Integer(direction));
		}
	}

	public void setFetchSize(int rows) throws SQLException
	{
		checkClosed();
		if (rows<0) throw new PSQLException("postgresql.input.fetch.gt0");
		super.fetchSize = rows;
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
		setString(i, x.toString());
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
			throw new PSQLException("postgresql.unusual", PSQLState.UNEXPECTED_ERROR, se);
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
		if (connection.haveMinimumCompatibleVersion("7.2"))
		{
			//Version 7.2 supports CharacterStream for for the PG text types
			//As the spec/javadoc for this method indicate this is to be used for
			//large text values (i.e. LONGVARCHAR)	PG doesn't have a separate
			//long varchar datatype, but with toast all the text datatypes are capable of
			//handling very large values.  Thus the implementation ends up calling
			//setString() since there is no current way to stream the value to the server
			char[] l_chars = new char[length];
			int l_charsRead;
			try
			{
				l_charsRead = x.read(l_chars, 0, length);
			}
			catch (IOException l_ioe)
			{
				throw new PSQLException("postgresql.unusual", PSQLState.UNEXPECTED_ERROR, l_ioe);
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
				throw new PSQLException("postgresql.unusual", PSQLState.UNEXPECTED_ERROR, se);
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
			throw new PSQLException("postgresql.unusual", PSQLState.UNEXPECTED_ERROR, se);
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

	public Object getObject(int i, java.util.Map map) throws SQLException
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
