package org.postgresql.jdbc2;


import java.sql.*;
import java.util.Vector;
import org.postgresql.Field;

/* $Header$
 * This class implements the java.sql.ResultSet interface for JDBC2.
 * However most of the implementation is really done in 
 * org.postgresql.jdbc2.AbstractJdbc2ResultSet or one of it's parents
 */
public class Jdbc2ResultSet extends org.postgresql.jdbc2.AbstractJdbc2ResultSet implements java.sql.ResultSet
{

	public Jdbc2ResultSet(Jdbc2Connection conn, Statement statement, Field[] fields, Vector tuples, String status, int updateCount, long insertOID, boolean binaryCursor)
	{
		super(conn, statement, fields, tuples, status, updateCount, insertOID, binaryCursor);
	}

	public java.sql.ResultSetMetaData getMetaData() throws SQLException
	{
		return new Jdbc2ResultSetMetaData(rows, fields);
	}

}

