/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL$
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.jdbc2;

import java.sql.*;

class Jdbc2PreparedStatement extends Jdbc2Statement implements PreparedStatement
{
	Jdbc2PreparedStatement(Jdbc2Connection connection, String sql, int rsType, int rsConcurrency) throws SQLException
	{
		this(connection, sql, false, rsType, rsConcurrency);
	}

	protected Jdbc2PreparedStatement(Jdbc2Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency) throws SQLException
	{
		super(connection, sql, isCallable, rsType, rsConcurrency);
	}
}

