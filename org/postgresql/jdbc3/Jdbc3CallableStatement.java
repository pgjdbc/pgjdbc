package org.postgresql.jdbc3;

import java.sql.*;
import java.util.Map;

class Jdbc3CallableStatement extends Jdbc3PreparedStatement implements CallableStatement
{
	Jdbc3CallableStatement(Jdbc3Connection connection, String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
	{
		super(connection, sql, true, rsType, rsConcurrency, rsHoldability);
	}

	public Object getObject(int i, Map map) throws SQLException
	{
		return getObjectImpl(i, map);
	}

	public Object getObject(String s, Map map) throws SQLException
	{
		return getObjectImpl(s, map);
	}

}
