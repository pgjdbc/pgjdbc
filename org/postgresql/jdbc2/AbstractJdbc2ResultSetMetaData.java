package org.postgresql.jdbc2;

import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Vector;

public abstract class AbstractJdbc2ResultSetMetaData extends org.postgresql.jdbc1.AbstractJdbc1ResultSetMetaData
{
	/*
	 *	Initialise for a result with a tuple set and
	 *	a field descriptor set
	 *
	 * @param rows the Vector of rows returned by the ResultSet
	 * @param fields the array of field descriptors
	 */
	public AbstractJdbc2ResultSetMetaData(BaseConnection connection, Field[] fields)
	{
		super(connection, fields);
	}

	// ** JDBC 2 Extensions **

	// This can hook into our PG_Object mechanism
	/**
	 * Returns the fully-qualified name of the Java class whose instances
	 * are manufactured if the method <code>ResultSet.getObject</code>
	 * is called to retrieve a value from the column.
	 *
	 * <code>ResultSet.getObject</code> may return a subclass of the class
	 * returned by this method.
	 *
	 * @param column the first column is 1, the second is 2, ...
	 * @return the fully-qualified name of the class in the Java programming
	 *		   language that would be used by the method
	 *		   <code>ResultSet.getObject</code> to retrieve the value in the specified
	 *		   column. This is the class name used for custom mapping.
	 * @exception SQLException if a database access error occurs
	 */
	public String getColumnClassName(int column) throws SQLException
	{
		/*
			The following data type mapping came from ../Field.java.

			"int2",
			"int4","oid",
			"int8",
			"cash","money",
			"numeric",
			"float4",
			"float8",
			"bpchar","char","char2","char4","char8","char16",
			"varchar","text","name","filename",
			"bool",
			"date",
			"time",
			"abstime","timestamp"

			Types.SMALLINT,
			Types.INTEGER,Types.INTEGER,
			Types.BIGINT,
			Types.DOUBLE,Types.DOUBLE,
			Types.NUMERIC,
			Types.REAL,
			Types.DOUBLE,
			Types.CHAR,Types.CHAR,Types.CHAR,Types.CHAR,Types.CHAR,Types.CHAR,
			Types.VARCHAR,Types.VARCHAR,Types.VARCHAR,Types.VARCHAR,
			Types.BIT,
			Types.DATE,
			Types.TIME,
			Types.TIMESTAMP,Types.TIMESTAMP
		*/

		Field field = getField(column);
		int sql_type = getSQLType(column);

		switch (sql_type)
		{
			case Types.BIT:
				return ("java.lang.Boolean");
			case Types.SMALLINT:
				return ("java.lang.Short");
			case Types.INTEGER:
				return ("java.lang.Integer");
			case Types.BIGINT:
				return ("java.lang.Long");
			case Types.NUMERIC:
				return ("java.math.BigDecimal");
			case Types.REAL:
				return ("java.lang.Float");
			case Types.DOUBLE:
				return ("java.lang.Double");
			case Types.CHAR:
			case Types.VARCHAR:
				return ("java.lang.String");
			case Types.DATE:
				return ("java.sql.Date");
			case Types.TIME:
				return ("java.sql.Time");
			case Types.TIMESTAMP:
				return ("java.sql.Timestamp");
			case Types.BINARY:
			case Types.VARBINARY:
				return ("[B");
			case Types.ARRAY:
				return ("java.sql.Array");
			default:
				String type = getPGType(column);
				if ("unknown".equals(type))
				{
					return ("java.lang.String");
				}
				return ("java.lang.Object");
		}
	}
}

