/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL$
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.jdbc3g;

import java.sql.*;
import java.util.Map;

class Jdbc3gCallableStatement extends Jdbc3gPreparedStatement implements CallableStatement
{
	Jdbc3gCallableStatement(Jdbc3gConnection connection, String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
	{
		super(connection, sql, true, rsType, rsConcurrency, rsHoldability);
	}

	public Object getObject(int i, Map<String, Class<?>> map) throws SQLException
	{
		return getObjectImpl(i, map);
	}

	public Object getObject(String s, Map<String, Class<?>> map) throws SQLException
	{
		return getObjectImpl(s, map);
	}

}
