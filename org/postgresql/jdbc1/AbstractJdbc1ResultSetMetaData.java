package org.postgresql.jdbc1;

import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.*;
import java.util.*;

public abstract class AbstractJdbc1ResultSetMetaData
{
	protected final BaseConnection connection;
	protected final Field[] fields;

	private Hashtable tableNameCache;
	private Hashtable schemaNameCache;

	/*
	 *	Initialise for a result with a tuple set and
	 *	a field descriptor set
	 *
	 * @param fields the array of field descriptors
	 */
	public AbstractJdbc1ResultSetMetaData(BaseConnection connection, Field[] fields)
	{
		this.connection = connection;
		this.fields = fields;
	}

	/*
	 * Whats the number of columns in the ResultSet?
	 *
	 * @return the number
	 * @exception SQLException if a database access error occurs
	 */
	public int getColumnCount() throws SQLException
	{
		return fields.length;
	}

	/*
	 * Is the column automatically numbered (and thus read-only)
	 * I believe that PostgreSQL does not support this feature.
	 *
	 * @param column the first column is 1, the second is 2...
	 * @return true if so
	 * @exception SQLException if a database access error occurs
	 */
	public boolean isAutoIncrement(int column) throws SQLException
	{
		return false;
	}

	/*
	 * Does a column's case matter? ASSUMPTION: Any field that is
	 * not obviously case insensitive is assumed to be case sensitive
	 *
	 * @param column the first column is 1, the second is 2...
	 * @return true if so
	 * @exception SQLException if a database access error occurs
	 */
	public boolean isCaseSensitive(int column) throws SQLException
	{
		int sql_type = getSQLType(column);

		switch (sql_type)
		{
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.FLOAT:
			case Types.REAL:
			case Types.DOUBLE:
			case Types.DATE:
			case Types.TIME:
			case Types.TIMESTAMP:
				return false;
			default:
				return true;
		}
	}

	/*
	 * Can the column be used in a WHERE clause?  Basically for
	 * this, I split the functions into two types: recognised
	 * types (which are always useable), and OTHER types (which
	 * may or may not be useable).	The OTHER types, for now, I
	 * will assume they are useable.  We should really query the
	 * catalog to see if they are useable.
	 *
	 * @param column the first column is 1, the second is 2...
	 * @return true if they can be used in a WHERE clause
	 * @exception SQLException if a database access error occurs
	 */
	public boolean isSearchable(int column) throws SQLException
	{
		int sql_type = getSQLType(column);

		// This switch is pointless, I know - but it is a set-up
		// for further expansion.
		switch (sql_type)
		{
			case Types.OTHER:
				return true;
			default:
				return true;
		}
	}

	/*
	 * Is the column a cash value?	6.1 introduced the cash/money
	 * type, which haven't been incorporated as of 970414, so I
	 * just check the type name for both 'cash' and 'money'
	 *
	 * @param column the first column is 1, the second is 2...
	 * @return true if its a cash column
	 * @exception SQLException if a database access error occurs
	 */
	public boolean isCurrency(int column) throws SQLException
	{
		String type_name = getPGType(column);

		return type_name.equals("cash") || type_name.equals("money");
	}

	/*
	 * Indicates the nullability of values in the designated column.
	 *
	 * @param column the first column is 1, the second is 2...
	 * @return one of the columnNullable values
	 * @exception SQLException if a database access error occurs
	 */
	public int isNullable(int column) throws SQLException
	{
		Field field = getField(column);
		return field.getNullable(connection);
  	}

	/*
	 * Is the column a signed number? In PostgreSQL, all numbers
	 * are signed, so this is trivial.	However, strings are not
	 * signed (duh!)
	 *
	 * @param column the first column is 1, the second is 2...
	 * @return true if so
	 * @exception SQLException if a database access error occurs
	 */
	public boolean isSigned(int column) throws SQLException
	{
		int sql_type = getSQLType(column);

		switch (sql_type)
		{
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.FLOAT:
			case Types.REAL:
			case Types.DOUBLE:
				return true;
			case Types.DATE:
			case Types.TIME:
			case Types.TIMESTAMP:
				return false;	// I don't know about these?
			default:
				return false;
		}
	}

	/*
	 * What is the column's normal maximum width in characters?
	 *
	 * @param column the first column is 1, the second is 2, etc.
	 * @return the maximum width
	 * @exception SQLException if a database access error occurs
	 */
	public int getColumnDisplaySize(int column) throws SQLException
	{
		Field f = getField(column);
		String type_name = getPGType(column);
		int typmod = f.getMod();

		// I looked at other JDBC implementations and couldn't find a consistent
		// interpretation of the "display size" for numeric values, so this is our's
		// FIXME: currently, only types with a SQL92 or SQL3 pendant are implemented - jens@jens.de

		// fixed length data types
		if (type_name.equals( "int2" ))
			return 6;  // -32768 to +32768 (5 digits and a sign)
		if (type_name.equals( "int4" )
				|| type_name.equals( "oid" ))
			return 11; // -2147483648 to +2147483647
		if (type_name.equals( "int8" ))
			return 20; // -9223372036854775808 to +9223372036854775807
		if (type_name.equals( "money" ))
			return 12; // MONEY = DECIMAL(9,2)
		if (type_name.equals( "float4" ))
			return 11; // i checked it out ans wasn't able to produce more than 11 digits
		if (type_name.equals( "float8" ))
			return 20; // dito, 20
		if (type_name.equals( "char" ))
			return 1;
		if (type_name.equals( "bool" ))
			return 1;

		int secondSize;
		switch(typmod) {
			case 0:
				secondSize = 0;
				break;
			case -1:
				// six digits plus the decimal point
				secondSize = 7;
				break;
			default:
				// with an odd scale an even number of digits
				// are always show so timestamp(1) will print
				// two fractional digits.
				secondSize = typmod + (typmod%2) + 1;
				break;
		}

		if (type_name.equals( "date" ))
			return 13; // "01/01/4713 BC" - "31/12/32767"

		// If we knew the timezone we could avoid having to possibly
		// account for fractional hour offsets (which adds three chars).
		//
		// Also the range of timestamp types is not exactly clear.
		// 4 digits is the common case for a year, but there are
		// version/compilation dependencies on the exact date ranges, 
		// (notably --enable-integer-datetimes), but for now we'll
		// just ignore them and assume that a year is four digits.
		//
		if (type_name.equals( "time" ))
			return 8 + secondSize;  // 00:00:00 + seconds
		if (type_name.equals( "timetz" ))
			return 8 + secondSize + 6; // 00:00.00 + .000000 + -00:00
		if (type_name.equals( "timestamp" ))
			return 19 + secondSize;	// 0000-00-00 00:00:00 + .000000;
		if (type_name.equals( "timestamptz" ))
			return 19 + secondSize + 6;	// 0000-00-00 00:00:00 + .000000 + -00:00;

		// variable length fields
		typmod -= 4;
		if (type_name.equals( "bpchar" )
				|| type_name.equals( "varchar" ))
			return typmod; // VARHDRSZ=sizeof(int32)=4
		if (type_name.equals( "numeric" ))
			return ( (typmod >> 16) & 0xffff )
				   + 1 + ( typmod & 0xffff ); // DECIMAL(p,s) = (p digits).(s digits)

		// if we don't know better
		return f.getLength();
	}

	/*
	 * @param column the first column is 1, the second is 2, etc.
	 * @return the column label
	 * @exception SQLException if a database access error occurs
	 */
	public String getColumnLabel(int column) throws SQLException
	{
		Field f = getField(column);
		if (f != null)
			return f.getColumnLabel();
		return "field" + column;
	}

	/*
	 * What's a column's name?
	 *
	 * @param column the first column is 1, the second is 2, etc.
	 * @return the column name
	 * @exception SQLException if a database access error occurs
	 */
	public String getColumnName(int column) throws SQLException
	{
		Field field = getField(column);
		return field.getColumnName(connection);
	}

	/*
	 * @param column the first column is 1, the second is 2...
	 * @return the Schema Name
	 * @exception SQLException if a database access error occurs
	 */
	public String getSchemaName(int column) throws SQLException
	{
		Field field = getField(column);
		if (field.getTableOid() == 0)
		{
			return "";
		}
		Integer tableOid = new Integer(field.getTableOid());
		if (schemaNameCache == null)
		{
			schemaNameCache = new Hashtable();
		}
		String schemaName = (String) schemaNameCache.get(tableOid);
		if (schemaName != null)
		{
			return schemaName;
		} else
		{
			ResultSet res = null;
			PreparedStatement ps = null;
			try
			{
				String sql = "SELECT n.nspname FROM pg_catalog.pg_class c, pg_catalog.pg_namespace n WHERE n.oid = c.relnamespace AND c.oid = ?;";
				ps = ((Connection)connection).prepareStatement(sql);
				ps.setInt(1, tableOid.intValue());
				res = ps.executeQuery();
				schemaName = "";
				if (res.next())
				{
					schemaName = res.getString(1);
				}
				schemaNameCache.put(tableOid, schemaName);
				return schemaName;
			} finally
			{
				if (res != null)
					res.close();
				if (ps != null)
					ps.close();
			}
		}
	}

	/*
	 * What is a column's number of decimal digits.
	 *
	 * @param column the first column is 1, the second is 2...
	 * @return the precision
	 * @exception SQLException if a database access error occurs
	 */
	public int getPrecision(int column) throws SQLException
	{
		int sql_type = getSQLType(column);

		switch (sql_type)
		{
			case Types.SMALLINT:
				return 5;
			case Types.INTEGER:
				return 10;
			case Types.REAL:
				return 8;
			case Types.FLOAT:
				return 16;
			case Types.DOUBLE:
				return 16;
			case Types.VARCHAR:
				return 0;
			case Types.NUMERIC:
				Field f = getField(column);
				if (f != null) {
					// no specified precision or scale
					if (f.getMod() == -1) {
						return 1000;
					}
					return ((0xFFFF0000)&f.getMod()) >> 16;
				} else {
					return 0;
				}
			default:
				return 0;
		}
	}

	/*
	 * What is a column's number of digits to the right of the
	 * decimal point?
	 *
	 * @param column the first column is 1, the second is 2...
	 * @return the scale
	 * @exception SQLException if a database access error occurs
	 */
	public int getScale(int column) throws SQLException
	{
		int sql_type = getSQLType(column);

		switch (sql_type)
		{
			case Types.SMALLINT:
				return 0;
			case Types.INTEGER:
				return 0;
			case Types.REAL:
				return 8;
			case Types.FLOAT:
				return 16;
			case Types.DOUBLE:
				return 16;
			case Types.VARCHAR:
				return 0;
			case Types.NUMERIC:
				Field f = getField(column);
				if (f != null) {
					// no specified precision or scale
					if (f.getMod() == -1) {
						return 1000;
					}
					return (((0x0000FFFF)&f.getMod()) - 4);
				} else {
					return 0;
				}
			case Types.TIME:
			case Types.TIMESTAMP:
				int typmod = -1;

				Field fld = getField(column);
				if (fld != null)
					typmod = fld.getMod();

				if (typmod == -1)
					return 6;

				return typmod;
			default:
				return 0;
		}
	}

	/*
	 * @param column the first column is 1, the second is 2...
	 * @return column name, or "" if not applicable
	 * @exception SQLException if a database access error occurs
	 */
	public String getTableName(int column) throws SQLException
	{
		Field field = getField(column);
		if (field.getTableOid() == 0)
		{
			return "";
		}
		Integer tableOid = new Integer(field.getTableOid());
		if (tableNameCache == null)
		{
			tableNameCache = new Hashtable();
		}
		String tableName = (String) tableNameCache.get(tableOid);
		if (tableName != null)
		{
			return tableName;
		} else
		{
			ResultSet res = null;
			PreparedStatement ps = null;
			try
			{
				ps = ((Connection)connection).prepareStatement("SELECT relname FROM pg_catalog.pg_class WHERE oid = ?");
				ps.setInt(1, tableOid.intValue());
				res = ps.executeQuery();
				tableName = "";
				if (res.next())
				{
					tableName = res.getString(1);
				}
				tableNameCache.put(tableOid, tableName);
				return tableName;
			} finally
			{
				if (res != null)
					res.close();
				if (ps != null)
					ps.close();
			}
		}
	}

	/*
	 * What's a column's table's catalog name?  As with getSchemaName(),
	 * we can say that if getTableName() returns n/a, then we can too -
	 * otherwise, we need to work on it.
	 *
	 * @param column the first column is 1, the second is 2...
	 * @return catalog name, or "" if not applicable
	 * @exception SQLException if a database access error occurs
	 */
	public String getCatalogName(int column) throws SQLException
	{
		return "";
	}

	/*
	 * What is a column's SQL Type? (java.sql.Type int)
	 *
	 * @param column the first column is 1, the second is 2, etc.
	 * @return the java.sql.Type value
	 * @exception SQLException if a database access error occurs
	 * @see org.postgresql.Field#getSQLType
	 * @see java.sql.Types
	 */
	public int getColumnType(int column) throws SQLException
	{
		return getSQLType(column);
	}

	/*
	 * Whats is the column's data source specific type name?
	 *
	 * @param column the first column is 1, the second is 2, etc.
	 * @return the type name
	 * @exception SQLException if a database access error occurs
	 */
	public String getColumnTypeName(int column) throws SQLException
	{
		return getPGType(column);
	}

	/*
	 * Is the column definitely not writable?  In reality, we would
	 * have to check the GRANT/REVOKE stuff for this to be effective,
	 * and I haven't really looked into that yet, so this will get
	 * re-visited.
	 *
	 * @param column the first column is 1, the second is 2, etc.
	 * @return true if so
	 * @exception SQLException if a database access error occurs
	 */
	public boolean isReadOnly(int column) throws SQLException
	{
		return false;
	}

	/*
	 * Is it possible for a write on the column to succeed?  Again, we
	 * would in reality have to check the GRANT/REVOKE stuff, which
	 * I haven't worked with as yet.  However, if it isn't ReadOnly, then
	 * it is obviously writable.
	 *
	 * @param column the first column is 1, the second is 2, etc.
	 * @return true if so
	 * @exception SQLException if a database access error occurs
	 */
	public boolean isWritable(int column) throws SQLException
	{
		return !isReadOnly(column);
	}

	/*
	 * Will a write on this column definately succeed?	Hmmm...this
	 * is a bad one, since the two preceding functions have not been
	 * really defined.	I cannot tell is the short answer.	I thus
	 * return isWritable() just to give us an idea.
	 *
	 * @param column the first column is 1, the second is 2, etc..
	 * @return true if so
	 * @exception SQLException if a database access error occurs
	 */
	public boolean isDefinitelyWritable(int column) throws SQLException
	{
		return false;
	}

	// ********************************************************
	//	END OF PUBLIC INTERFACE
	// ********************************************************

	/*
	 * For several routines in this package, we need to convert
	 * a columnIndex into a Field[] descriptor.  Rather than do
	 * the same code several times, here it is.
	 *
	 * @param columnIndex the first column is 1, the second is 2...
	 * @return the Field description
	 * @exception SQLException if a database access error occurs
	 */
	protected Field getField(int columnIndex) throws SQLException
	{
		if (columnIndex < 1 || columnIndex > fields.length)
			throw new PSQLException("postgresql.res.colrange", PSQLState.INVALID_PARAMETER_VALUE);
		return fields[columnIndex - 1];
	}

	protected String getPGType(int columnIndex) throws SQLException
	{
		return connection.getPGType(getField(columnIndex).getOID());
	}

	protected int getSQLType(int columnIndex) throws SQLException
	{
		return connection.getSQLType(getField(columnIndex).getOID());
	}
}

