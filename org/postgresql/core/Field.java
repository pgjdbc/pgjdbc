/*-------------------------------------------------------------------------
 *
 * Field.java
 *     Field is a class used to describe fields in a PostgreSQL ResultSet
 *
 * Copyright (c) 2003, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL: /cvsroot/pgsql-server/src/interfaces/jdbc/org/postgresql/core/Field.java,v 1.2 2003/05/29 03:21:32 barry Exp $
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.core;

import java.sql.*;

import org.postgresql.core.BaseConnection;

/*
 */
public class Field
{
	//The V3 protocol defines two constants for the format of data
	public static final int TEXT_FORMAT = 0;
	public static final int BINARY_FORMAT = 1;

	private int length;		// Internal Length of this field
	private int oid;		// OID of the type
	private int mod;		// type modifier of this field
	private String name;		// Name of this field (the column label)
	private int format = TEXT_FORMAT;   // In the V3 protocol each field has a format
					// 0 = text, 1 = binary
					// In the V2 protocol all fields in a
					// binary cursor are binary and all 
					// others are text

	private int tableOid; // OID of table ( zero if no table )
	private int positionInTable;
	private boolean fromServer;	// Did this field come from a query?

	// cache-fields
	private Integer nullable;
	private String columnName;

	private BaseConnection conn;	// Connection Instantation


	/*
	 * Construct a field based on the information fed to it.
	 *
	 * @param conn the connection this field came from
	 * @param name the name of the field
	 * @param oid the OID of the field
	 * @param len the length of the field
	 */
	public Field(BaseConnection conn, String name, int oid, int length, int mod)
	{
		this(conn, name, oid, length, mod, 0, 0);
	}

	/*
	 * Constructor without mod parameter.
	 *
	 * @param conn the connection this field came from
	 * @param name the name of the field
	 * @param oid the OID of the field
	 * @param len the length of the field
	 */
	public Field(BaseConnection conn, String name, int oid, int length)
	{
		this(conn, name, oid, length, 0);
	}

	/*
	 * Construct a field based on the information fed to it.
	 *
	 * @param conn the connection this field came from
	 * @param name the name of the field
	 * @param oid the OID of the field
	 * @param length the length of the field
	 * @param tableOid the OID of the columns' table
	 * @param positionInTable the position of column in the table (first column is 1, second column is 2, etc...)
	 */
	public Field(BaseConnection conn, String name, int oid, int length, int mod, int tableOid, int positionInTable)
	{
		this.conn = conn;
		this.name = name;
		this.oid = oid;
		this.length = length;
		this.mod = mod;
		this.tableOid = tableOid;
		this.positionInTable = positionInTable;
		this.fromServer = true;
	}

	/*
	 * @return the oid of this Field's data type
	 */
	public int getOID()
	{
		return oid;
	}

	/*
	 * @return the mod of this Field's data type
	 */
	public int getMod()
	{
		return mod;
	}

	/*
	 * @return the name of this Field's data type
	 */
	public String getName()
	{
		return name;
	}

	/*
	 * @return the length of this Field's data type
	 */
	public int getLength()
	{
		return length;
	}

	/*
	 * @return the format of this Field's data (text=0, binary=1)
	 */
	public int getFormat()
	{
		return format;
	}

	/*
	 * @param format the format of this Field's data (text=0, binary=1)
	 */
	public void setFormat(int format)
	{
		this.format = format;
	}

	/*
	 * We also need to get the PG type name as returned by the back end.
	 *
	 * @return the String representation of the PG type of this field
	 * @exception SQLException if a database access error occurs
	 */
	public String getPGType() throws SQLException
	{
		return conn.getPGType(oid);
	}

	/*
	 * We also need to get the java.sql.Types type.
	 *
	 * @return the int representation of the java.sql.Types type of this field
	 * @exception SQLException if a database access error occurs
	 */
	public int getSQLType() throws SQLException
	{
		return conn.getSQLType(oid);
	}

	/**
	 * Specify if this field was created from a server query
	 * or the driver manually creating a ResultSet.
	 */
	public void setFromServer(boolean fromServer)
	{
		this.fromServer = fromServer;
	}

	/*
	 * @return the columns' table oid, zero if no oid available
	 */
	public int getTableOid()
	{
		return tableOid;
	}

	/*
	 * @return instantiated connection
	 */
	public BaseConnection getConn()
	{
		return conn;
	}

	public int getPositionInTable()
	{
		return positionInTable;
	}

	public int getNullable() throws SQLException
	{
		if (nullable != null)
		{
			return nullable.intValue();
		}
		if (tableOid == 0)
		{
			nullable = new Integer(ResultSetMetaData.columnNullableUnknown);
			return nullable.intValue();
		}
		Connection con = (Connection) conn;
		ResultSet res = null;
		PreparedStatement ps = null;
		try
		{
			ps = con.prepareStatement("SELECT attnotnull FROM pg_catalog.pg_attribute WHERE attrelid = ? AND attnum = ?;");
			ps.setInt(1, tableOid);
			ps.setInt(2, positionInTable);
			res = ps.executeQuery();
			int nullResult = ResultSetMetaData.columnNullableUnknown;
			if (res.next())
			{
				nullResult = res.getBoolean(1) ? ResultSetMetaData.columnNoNulls : ResultSetMetaData.columnNullable;
			}
			nullable = new Integer(nullResult);
			return nullResult;
		} finally
		{
			if (res != null)
				res.close();
			if (ps != null)
				ps.close();
		}
	}

	public String getColumnName() throws SQLException
	{
		if (conn.getPGProtocolVersionMajor() < 3 || !fromServer) {
			return name;
		}
		if (columnName != null)
		{
			return columnName;
		}
		if (tableOid == 0)
		{
			return columnName = "";
		}
		Connection con = (Connection) conn;
		ResultSet res = null;
		PreparedStatement ps = null;
		try
		{
			ps = con.prepareStatement("SELECT attname FROM pg_catalog.pg_attribute WHERE attrelid = ? AND attnum = ?");
			ps.setInt(1, tableOid);
			ps.setInt(2, positionInTable);
			res = ps.executeQuery();
			String columnName = "";
			if (res.next())
			{
				columnName = res.getString(1);
			}
			return columnName;
		} finally
		{
			if (res != null)
				res.close();
			if (ps != null)
				ps.close();
		}
	}

}
