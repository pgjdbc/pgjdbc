/*-------------------------------------------------------------------------
 *
 * AbstractJdbc1ResultSet.java
 *     This class defines methods of the jdbc1 specification.  This class is
 *     extended by org.postgresql.jdbc2.AbstractJdbc2ResultSet which adds the
 *     jdbc2 methods.  The real ResultSet class (for jdbc1) is
 *     org.postgresql.jdbc1.Jdbc1ResultSet
 *
 * Copyright (c) 2003, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL: pgsql-server/src/interfaces/jdbc/org/postgresql/jdbc1/AbstractJdbc1ResultSet.java,v 1.24 2003/11/29 19:52:10 pgsql Exp $
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.jdbc1;

import java.math.BigDecimal;
import java.io.*;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Vector;
import org.postgresql.Driver;
import org.postgresql.core.*;
import org.postgresql.largeobject.*;
import org.postgresql.util.PGbytea;
import org.postgresql.util.PGtokenizer;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

public abstract class AbstractJdbc1ResultSet implements BaseResultSet, org.postgresql.PGRefCursorResultSet
{
	protected final BaseConnection connection;  // the connection we belong to
	protected final BaseStatement statement;    // the statement we belong to
	protected final Field fields[];		        // Field metadata for this resultset.
	protected final Query originalQuery;        // Query we originated from

	protected final int maxRows;            // Maximum rows in this resultset (might be 0).
	protected final int maxFieldSize;       // Maximum field size in this resultset (might be 0).

	protected Vector rows;			        // Current page of results.
	protected int current_row = -1;         // Index into 'rows' of our currrent row (0-based)
	protected int row_offset;               // Offset of row 0 in the actual resultset
	protected byte[][] this_row;		    // copy of the current result row
	protected SQLWarning warnings = null;	// The warning chain
	protected boolean wasNullFlag = false;	// the flag for wasNull()
	protected boolean onInsertRow = false;  // are we on the insert row (for JDBC2 updatable resultsets)?

	private StringBuffer sbuf = null;
	public byte[][] rowBuffer = null;       // updateable rowbuffer

 	private SimpleDateFormat m_tsFormat = null;
 	private SimpleDateFormat m_tstzFormat = null;
 	private SimpleDateFormat m_dateFormat = null;

	protected int fetchSize;       // Current fetch size (might be 0).
	protected ResultCursor cursor; // Cursor for fetching additional data.

	public abstract ResultSetMetaData getMetaData() throws SQLException;

	public class CursorResultHandler implements ResultHandler {
		private SQLException error;
		
		public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
			AbstractJdbc1ResultSet.this.rows = tuples;
			AbstractJdbc1ResultSet.this.cursor = cursor;
		}
		
		public void handleCommandStatus(String status, int updateCount, long insertOID) {
			handleError(new SQLException("unexpected command status"));
		}
		
		public void handleWarning(SQLWarning warning) {
			AbstractJdbc1ResultSet.this.addWarning(warning);
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
	};	

	public AbstractJdbc1ResultSet(Query originalQuery,
								  BaseStatement statement,
								  Field[] fields,
								  Vector tuples,
								  ResultCursor cursor,
								  int maxRows,
								  int maxFieldSize) throws SQLException
	{
		this.originalQuery = originalQuery;
		this.connection = (BaseConnection) statement.getConnection();
		this.statement = statement;
		this.fields = fields;
		this.rows = tuples;
		this.cursor = cursor;
		this.maxRows = maxRows;
		this.maxFieldSize = maxFieldSize;
	}

    public BaseStatement getPGStatement() {
		return statement;
	}

	public StringBuffer getStringBuffer() {
		return sbuf;
	}

	//
	// Backwards compatibility with PGRefCursorResultSet
	//

	private String refCursorName;

	public String getRefCursor() {
		return refCursorName;
	}

	private void setRefCursor(String refCursorName) {
		this.refCursorName = refCursorName;
	}

	//
	// Part of the JDBC2 support, but convenient to implement here.
	//

	public void setFetchSize(int rows) throws SQLException
	{
		fetchSize = rows;
	}

	public int getFetchSize() throws SQLException
	{
		return fetchSize;
	}

	public boolean next() throws SQLException
	{
		if (rows == null)
			throw new PSQLException("postgresql.con.closed", PSQLState.CONNECTION_DOES_NOT_EXIST);
		
		if (onInsertRow)
			throw new PSQLException("postgresql.res.oninsertrow");

		if (current_row+1 >= rows.size())
		{
			if (cursor == null || (maxRows > 0 && row_offset + rows.size() >= maxRows)) {
				current_row = rows.size();
				this_row = null;
				rowBuffer = null;
				return false;  // End of the resultset.
			}

			// Ask for some more data.
			row_offset += rows.size(); // We are discarding some data.

			int fetchRows = fetchSize;
			if (maxRows != 0) {
				if (fetchRows == 0 || row_offset + fetchRows > maxRows) // Fetch would exceed maxRows, limit it.
					fetchRows = maxRows - row_offset;
			}

			// Execute the fetch and update this resultset.
			connection.getQueryExecutor().fetch(cursor, new CursorResultHandler(), fetchRows);

			current_row = 0;

  			// Test the new rows array.
  			if (rows.size() == 0) {
				this_row = null;
				rowBuffer = null;
  				return false;
			}
		} else {
			current_row++;
		}

		this_row = (byte [][])rows.elementAt(current_row);

		rowBuffer = new byte[this_row.length][];
		System.arraycopy(this_row, 0, rowBuffer, 0, this_row.length);
		return true;
	}

	public void close() throws SQLException
	{
		//release resources held (memory for tuples)
		if (rows != null)
		{
			rows = null;
		}
	}

	public boolean wasNull() throws SQLException
	{
		return wasNullFlag;
	}

	public String getString(int columnIndex) throws SQLException
	{
		checkResultSet( columnIndex );
		wasNullFlag = (this_row[columnIndex - 1] == null);
		if (wasNullFlag)
			return null;

		Encoding encoding = connection.getEncoding();
		try {
			return trimString(columnIndex, encoding.decode(this_row[columnIndex-1]));
		} catch (IOException ioe) {
			throw new PSQLException("postgresql.con.invalidchar", PSQLState.DATA_ERROR, ioe);
		}
	}

	public boolean getBoolean(int columnIndex) throws SQLException
	{
		return toBoolean( getString(columnIndex) );
	}


	public byte getByte(int columnIndex) throws SQLException
	{
		String s = getString(columnIndex);

		if (s != null )
		{
			try
			{
				switch(getSQLType(columnIndex))
				{
					case Types.NUMERIC:
					case Types.REAL:
					case Types.DOUBLE:
					case Types.FLOAT:
					case Types.DECIMAL:
						int loc = s.indexOf(".");
						if (loc!=-1 && Integer.parseInt(s.substring(loc+1,s.length()))==0)
						{
							s = s.substring(0,loc);
						}
						break;
					case Types.CHAR:
						s = s.trim();
						break;
				}
				if ( s.length() == 0 ) return 0;
				return Byte.parseByte(s);
			}
			catch (NumberFormatException e)
			{
				throw new PSQLException("postgresql.res.badbyte", PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, s);
			}
		}
		return 0; // SQL NULL
	}

	public short getShort(int columnIndex) throws SQLException
	{
		String s = getFixedString(columnIndex);

		if (s != null)
		{
			try
			{
				switch(getSQLType(columnIndex))
				{
					case Types.NUMERIC:
					case Types.REAL:
					case Types.DOUBLE:
					case Types.FLOAT:
					case Types.DECIMAL:
						int loc = s.indexOf(".");
						if (loc!=-1 && Integer.parseInt(s.substring(loc+1,s.length()))==0)
						{
							s = s.substring(0,loc);
						}
						break;
					case Types.CHAR:
						s = s.trim();
						break;
				}
				return Short.parseShort(s);
			}
			catch (NumberFormatException e)
			{
				throw new PSQLException("postgresql.res.badshort", PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, s);
			}
		}
		return 0; // SQL NULL
	}

	public int getInt(int columnIndex) throws SQLException
	{
		return toInt( getFixedString(columnIndex) );
	}

	public long getLong(int columnIndex) throws SQLException
	{
		return toLong( getFixedString(columnIndex) );
	}

	public float getFloat(int columnIndex) throws SQLException
	{
		return toFloat( getFixedString(columnIndex) );
	}

	public double getDouble(int columnIndex) throws SQLException
	{
		return toDouble( getFixedString(columnIndex) );
	}

	public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException
	{
		return toBigDecimal( getFixedString(columnIndex), scale );
	}

	/*
	 * Get the value of a column in the current row as a Java byte array.
	 *
	 * <p>In normal use, the bytes represent the raw values returned by the
	 * backend. However, if the column is an OID, then it is assumed to
	 * refer to a Large Object, and that object is returned as a byte array.
	 *
	 * <p><b>Be warned</b> If the large object is huge, then you may run out
	 * of memory.
	 *
	 * @param columnIndex the first column is 1, the second is 2, ...
	 * @return the column value; if the value is SQL NULL, the result
	 *	is null
	 * @exception SQLException if a database access error occurs
	 */
	public byte[] getBytes(int columnIndex) throws SQLException
	{
		checkResultSet( columnIndex );
		wasNullFlag = (this_row[columnIndex - 1] == null);
		if (!wasNullFlag)
		{
			if (fields[columnIndex -1].getFormat() == Field.BINARY_FORMAT)
			{
				//If the data is already binary then just return it
				return this_row[columnIndex - 1];
			}
			else if (connection.haveMinimumCompatibleVersion("7.2"))
			{
				//Version 7.2 supports the bytea datatype for byte arrays
				if (fields[columnIndex - 1].getOID() == Oid.BYTEA)
				{
					return trimBytes(columnIndex, PGbytea.toBytes(this_row[columnIndex - 1]));
				}
				else
				{
					return trimBytes(columnIndex, this_row[columnIndex - 1]);
				}
			}
			else
			{
				//Version 7.1 and earlier supports LargeObjects for byte arrays
				// Handle OID's as BLOBS
				if ( fields[columnIndex - 1].getOID() == 26)
				{
					LargeObjectManager lom = connection.getLargeObjectAPI();
					LargeObject lob = lom.open(getInt(columnIndex));
					byte buf[] = lob.read(lob.size());
					lob.close();
					return trimBytes(columnIndex, buf);
				}
				else
				{
					return trimBytes(columnIndex, this_row[columnIndex - 1]);
				}
			}
		}
		return null;
	}

	public java.sql.Date getDate(int columnIndex) throws SQLException
	{
		return toDate( getString(columnIndex) );
	}

	public Time getTime(int columnIndex) throws SQLException
	{
        checkResultSet(columnIndex);
		return TimestampUtils.toTime( getString(columnIndex), getPGType(columnIndex) );
	}

	public Timestamp getTimestamp(int columnIndex) throws SQLException
	{
        this.checkResultSet(columnIndex);
        int sqlType = getSQLType(columnIndex);

        if ( sqlType == Types.TIME )
        {
            Time time = TimestampUtils.toTime(getString( columnIndex ), getPGType(columnIndex));
            return new Timestamp(time.getTime() );
        }
		return TimestampUtils.toTimestamp(getString(columnIndex), getPGType(columnIndex));
	}

	public InputStream getAsciiStream(int columnIndex) throws SQLException
	{
		checkResultSet( columnIndex );
		wasNullFlag = (this_row[columnIndex - 1] == null);
		if (wasNullFlag)
			return null;

		if (connection.haveMinimumCompatibleVersion("7.2"))
		{
			//Version 7.2 supports AsciiStream for all the PG text types
			//As the spec/javadoc for this method indicate this is to be used for
			//large text values (i.e. LONGVARCHAR)	PG doesn't have a separate
			//long string datatype, but with toast the text datatype is capable of
			//handling very large values.  Thus the implementation ends up calling
			//getString() since there is no current way to stream the value from the server
			try
			{
				return new ByteArrayInputStream(getString(columnIndex).getBytes("ASCII"));
			}
			catch (UnsupportedEncodingException l_uee)
			{
				throw new PSQLException("postgresql.unusual", PSQLState.UNEXPECTED_ERROR, l_uee);
			}
		}
		else
		{
			// In 7.1 Handle as BLOBS so return the LargeObject input stream
			return getBinaryStream(columnIndex);
		}
	}

	public InputStream getUnicodeStream(int columnIndex) throws SQLException
	{
		checkResultSet( columnIndex );
		wasNullFlag = (this_row[columnIndex - 1] == null);
		if (wasNullFlag)
			return null;

		if (connection.haveMinimumCompatibleVersion("7.2"))
		{
			//Version 7.2 supports AsciiStream for all the PG text types
			//As the spec/javadoc for this method indicate this is to be used for
			//large text values (i.e. LONGVARCHAR)	PG doesn't have a separate
			//long string datatype, but with toast the text datatype is capable of
			//handling very large values.  Thus the implementation ends up calling
			//getString() since there is no current way to stream the value from the server
			try
			{
				return new ByteArrayInputStream(getString(columnIndex).getBytes("UTF-8"));
			}
			catch (UnsupportedEncodingException l_uee)
			{
				throw new PSQLException("postgresql.unusual", PSQLState.UNEXPECTED_ERROR, l_uee);
			}
		}
		else
		{
			// In 7.1 Handle as BLOBS so return the LargeObject input stream
			return getBinaryStream(columnIndex);
		}
	}

	public InputStream getBinaryStream(int columnIndex) throws SQLException
	{
		checkResultSet( columnIndex );
		wasNullFlag = (this_row[columnIndex - 1] == null);
		if (wasNullFlag)
			return null;

		if (connection.haveMinimumCompatibleVersion("7.2"))
		{
			//Version 7.2 supports BinaryStream for all PG bytea type
			//As the spec/javadoc for this method indicate this is to be used for
			//large binary values (i.e. LONGVARBINARY)	PG doesn't have a separate
			//long binary datatype, but with toast the bytea datatype is capable of
			//handling very large values.  Thus the implementation ends up calling
			//getBytes() since there is no current way to stream the value from the server
			byte b[] = getBytes(columnIndex);
			if (b != null)
				return new ByteArrayInputStream(b);
		}
		else
		{
			// In 7.1 Handle as BLOBS so return the LargeObject input stream
			if ( fields[columnIndex - 1].getOID() == 26)
			{
				LargeObjectManager lom = connection.getLargeObjectAPI();
				LargeObject lob = lom.open(getInt(columnIndex));
				return lob.getInputStream();
			}
		}
		return null;
	}

	public String getString(String columnName) throws SQLException
	{
		return getString(findColumn(columnName));
	}

	public boolean getBoolean(String columnName) throws SQLException
	{
		return getBoolean(findColumn(columnName));
	}

	public byte getByte(String columnName) throws SQLException
	{

		return getByte(findColumn(columnName));
	}

	public short getShort(String columnName) throws SQLException
	{
		return getShort(findColumn(columnName));
	}

	public int getInt(String columnName) throws SQLException
	{
		return getInt(findColumn(columnName));
	}

	public long getLong(String columnName) throws SQLException
	{
		return getLong(findColumn(columnName));
	}

	public float getFloat(String columnName) throws SQLException
	{
		return getFloat(findColumn(columnName));
	}

	public double getDouble(String columnName) throws SQLException
	{
		return getDouble(findColumn(columnName));
	}

	public BigDecimal getBigDecimal(String columnName, int scale) throws SQLException
	{
		return getBigDecimal(findColumn(columnName), scale);
	}

	public byte[] getBytes(String columnName) throws SQLException
	{
		return getBytes(findColumn(columnName));
	}

	public java.sql.Date getDate(String columnName) throws SQLException
	{
		return getDate(findColumn(columnName));
	}

	public Time getTime(String columnName) throws SQLException
	{
		return getTime(findColumn(columnName));
	}

	public Timestamp getTimestamp(String columnName) throws SQLException
	{
		return getTimestamp(findColumn(columnName));
	}

	public InputStream getAsciiStream(String columnName) throws SQLException
	{
		return getAsciiStream(findColumn(columnName));
	}

	public InputStream getUnicodeStream(String columnName) throws SQLException
	{
		return getUnicodeStream(findColumn(columnName));
	}

	public InputStream getBinaryStream(String columnName) throws SQLException
	{
		return getBinaryStream(findColumn(columnName));
	}

	public SQLWarning getWarnings() throws SQLException
	{
		return warnings;
	}

	public void clearWarnings() throws SQLException
	{
		warnings = null;
	}

	protected void addWarning(SQLWarning warnings)
	{
		if (this.warnings != null)
			this.warnings.setNextWarning(warnings);
		else
			this.warnings = warnings;
	}

	public String getCursorName() throws SQLException
	{
		return null;
	}

	/*
	 * Get the value of a column in the current row as a Java object
	 *
	 * <p>This method will return the value of the given column as a
	 * Java object.  The type of the Java object will be the default
	 * Java Object type corresponding to the column's SQL type, following
	 * the mapping specified in the JDBC specification.
	 *
	 * <p>This method may also be used to read database specific abstract
	 * data types.
	 *
	 * @param columnIndex the first column is 1, the second is 2...
	 * @return a Object holding the column value
	 * @exception SQLException if a database access error occurs
	 */
	public Object getObject(int columnIndex) throws SQLException {
		Field field;

		checkResultSet(columnIndex);

		wasNullFlag = (this_row[columnIndex - 1] == null);
		if (wasNullFlag)
			return null;

		field = fields[columnIndex - 1];

		// some fields can be null, mainly from those returned by MetaData methods
		if (field == null)
		{
			wasNullFlag = true;
			return null;
		}
		
		Object result = internalGetObject(columnIndex, field);
		if (result != null)
			return result;

		return connection.getObject(getPGType(columnIndex), getString(columnIndex));
	}
		
	protected Object internalGetObject(int columnIndex, Field field) throws SQLException
	{
		switch (getSQLType(columnIndex)) {
			case Types.BIT:
				return getBoolean(columnIndex) ? Boolean.TRUE : Boolean.FALSE;
			case Types.SMALLINT:
				return new Short(getShort(columnIndex));
			case Types.INTEGER:
				return new Integer(getInt(columnIndex));
			case Types.BIGINT:
				return new Long(getLong(columnIndex));
			case Types.NUMERIC:
				return getBigDecimal
					   (columnIndex, (field.getMod() == -1) ? -1 : ((field.getMod() - 4) & 0xffff));
			case Types.REAL:
				return new Float(getFloat(columnIndex));
			case Types.DOUBLE:
				return new Double(getDouble(columnIndex));
			case Types.CHAR:
			case Types.VARCHAR:
				return getString(columnIndex);
			case Types.DATE:
				return getDate(columnIndex);
			case Types.TIME:
				return getTime(columnIndex);
			case Types.TIMESTAMP:
				return getTimestamp(columnIndex);
			case Types.BINARY:
			case Types.VARBINARY:
				return getBytes(columnIndex);

			default:
				String type = getPGType(columnIndex);

				// if the backend doesn't know the type then coerce to String
				if (type.equals("unknown"))
					return getString(columnIndex);

				// Specialized support for ref cursors is neater.
				if (type.equals("refcursor")) {
					// Fetch all results.
					String cursorName = getString(columnIndex);
					String fetchSql = "FETCH ALL IN \"" + cursorName + "\"";
					ResultSet rs = connection.execSQLQuery(fetchSql);
					((AbstractJdbc1ResultSet)rs).setRefCursor(cursorName);
					return rs;
				}

				// Caller determines what to do (JDBC2 overrides in this case)
				return null;
		}
	}

	public Object getObject(String columnName) throws SQLException
	{
		return getObject(findColumn(columnName));
	}

	/*
	 * Map a ResultSet column name to a ResultSet column index
	 */
	public int findColumn(String columnName) throws SQLException
	{
		int i;

		final int flen = fields.length;
		for (i = 0 ; i < flen; ++i)
			if (fields[i].getColumnLabel().equalsIgnoreCase(columnName))
				return (i + 1);
		throw new PSQLException ("postgresql.res.colname", null, columnName);
	}

	/*
	 * returns the OID of a field.<p>
	 * It is used internally by the driver.
	 */
	public int getColumnOID(int field)
	{
		return fields[field -1].getOID();
	}

	/*
	 * This is used to fix get*() methods on Money fields. It should only be
	 * used by those methods!
	 *
	 * It converts ($##.##) to -##.## and $##.## to ##.##
	 */
	public String getFixedString(int col) throws SQLException
	{
		String s = getString(col);

		// Handle SQL Null
		wasNullFlag = (this_row[col - 1] == null);
		if (wasNullFlag)
			return null;

		// if we don't have at least 2 characters it can't be money.
 		if (s.length() < 2)
 			return s;

		// Handle Money
		if (s.charAt(0) == '(')
		{
			s = "-" + PGtokenizer.removePara(s).substring(1);
		}
		if (s.charAt(0) == '$')
		{
			s = s.substring(1);
		}
 		else if (s.charAt(0) == '-' && s.charAt(1) == '$')
 		{
 			s = "-" + s.substring(2);
		}

		return s;
	}

	protected String getPGType( int column ) throws SQLException
    {
        return connection.getPGType(fields[column - 1].getOID());
    }

	protected int getSQLType( int column ) throws SQLException
    {
        return connection.getSQLType(fields[column - 1].getOID());
    }

    protected void checkResultSet( int column ) throws SQLException
	{
		if ( this_row == null )
			throw new PSQLException("postgresql.res.nextrequired");
		if ( column < 1 || column > fields.length )
			throw new PSQLException("postgresql.res.colrange", PSQLState.INVALID_PARAMETER_VALUE );
	}

	//----------------- Formatting Methods -------------------

	public static boolean toBoolean(String s)
	{
		if (s != null)
		{
			s = s.trim();

			if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("t"))
				return true;

			try
			{
				if (Double.valueOf(s).doubleValue()==1)
					return true;
			}
			catch (NumberFormatException e)
			{
			}
		}
		return false;		// SQL NULL
	}

	public static int toInt(String s) throws SQLException
	{
		if (s != null)
		{
			try
			{
				s = s.trim();
				return Integer.parseInt(s);
			}
			catch (NumberFormatException e)
			{
				throw new PSQLException ("postgresql.res.badint", PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, s);
			}
		}
		return 0;		// SQL NULL
	}

	public static long toLong(String s) throws SQLException
	{
		if (s != null)
		{
			try
			{
				s = s.trim();
				return Long.parseLong(s);
			}
			catch (NumberFormatException e)
			{
				throw new PSQLException ("postgresql.res.badlong", PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, s);
			}
		}
		return 0;		// SQL NULL
	}

	public static BigDecimal toBigDecimal(String s, int scale) throws SQLException
	{
		BigDecimal val;
		if (s != null)
		{
			try
			{
				s = s.trim();
				val = new BigDecimal(s);
			}
			catch (NumberFormatException e)
			{
				throw new PSQLException ("postgresql.res.badbigdec", PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, s);
			}
			if (scale == -1)
				return val;
			try
			{
				return val.setScale(scale);
			}
			catch (ArithmeticException e)
			{
				throw new PSQLException ("postgresql.res.badbigdec", PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, s);
			}
		}
		return null;		// SQL NULL
	}

	public static float toFloat(String s) throws SQLException
	{
		if (s != null)
		{
			try
			{
				s = s.trim();
				return Float.valueOf(s).floatValue();
			}
			catch (NumberFormatException e)
			{
				throw new PSQLException ("postgresql.res.badfloat", PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, s);
			}
		}
		return 0;		// SQL NULL
	}

	public static double toDouble(String s) throws SQLException
	{
		if (s != null)
		{
			try
			{
				s = s.trim();
				return Double.valueOf(s).doubleValue();
			}
			catch (NumberFormatException e)
			{
				throw new PSQLException ("postgresql.res.baddouble", PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, s);
			}
		}
		return 0;		// SQL NULL
	}

	public static java.sql.Date toDate(String s) throws SQLException
	{
		if (s == null)
			return null;
		// length == 10: SQL Date
		// length >  10: SQL Timestamp, assumes PGDATESTYLE=ISO
		try
		{
			s = s.trim();
			return java.sql.Date.valueOf((s.length() == 10) ? s : s.substring(0, 10));
		}
		catch (NumberFormatException e)
		{
			throw new PSQLException("postgresql.res.baddate",PSQLState.BAD_DATETIME_FORMAT, s);
		}
	}

	private boolean isColumnTrimmable(int columnIndex) throws SQLException
	{
		switch (getSQLType(columnIndex))
		{
			case Types.CHAR:
			case Types.VARCHAR:
		  	case Types.LONGVARCHAR:
		   	case Types.BINARY:
		   	case Types.VARBINARY:
		   	case Types.LONGVARBINARY:
		   		return true;
		}
	   	return false;
	}

	private byte[] trimBytes(int p_columnIndex, byte[] p_bytes) throws SQLException
	{
		//we need to trim if maxsize is set and the length is greater than maxsize and the
		//type of this column is a candidate for trimming
		if (maxFieldSize > 0 && p_bytes.length > maxFieldSize && isColumnTrimmable(p_columnIndex))
		{
			byte[] l_bytes = new byte[maxFieldSize];
			System.arraycopy (p_bytes, 0, l_bytes, 0, maxFieldSize);
			return l_bytes;
		}
		else
		{
			return p_bytes;
		}
	}

	private String trimString(int p_columnIndex, String p_string) throws SQLException
	{
		//we need to trim if maxsize is set and the length is greater than maxsize and the
		//type of this column is a candidate for trimming
		if (maxFieldSize > 0 && p_string.length() > maxFieldSize && isColumnTrimmable(p_columnIndex))
		{
			return p_string.substring(0, maxFieldSize);
		}
		else
		{
			return p_string;
		}
	}
  }

