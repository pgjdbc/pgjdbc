package org.postgresql.jdbc2;


import java.sql.*;
import java.util.Vector;
import org.postgresql.PGRefCursorResultSet;
import org.postgresql.core.BaseResultSet;
import org.postgresql.core.Field;

/* $Header$
 * This class implements the java.sql.Statement interface for JDBC2.
 * However most of the implementation is really done in
 * org.postgresql.jdbc2.AbstractJdbc2Statement or one of it's parents
 */
public class Jdbc2Statement extends org.postgresql.jdbc2.AbstractJdbc2Statement implements java.sql.Statement
{

	public Jdbc2Statement (Jdbc2Connection c)
	{
		super(c);
	}

	public BaseResultSet createResultSet (Field[] fields, Vector tuples, String status, int updateCount, long insertOID, boolean binaryCursor) throws SQLException
	{
		return new Jdbc2ResultSet(this, fields, tuples, status, updateCount, insertOID, binaryCursor);
	}

  	public PGRefCursorResultSet createRefCursorResultSet (String cursorName) throws SQLException
	{
                return new Jdbc2RefCursorResultSet(this, cursorName);
	}
}
