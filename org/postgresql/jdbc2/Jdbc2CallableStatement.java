package org.postgresql.jdbc2;

import java.sql.*;
import java.util.Map;

class Jdbc2CallableStatement extends Jdbc2PreparedStatement implements CallableStatement
{
	Jdbc2CallableStatement(Jdbc2Connection connection, String sql, int rsType, int rsConcurrency) throws SQLException
	{
		super(connection, sql, true, rsType, rsConcurrency);
	}

	public Object getObject(int i, Map map) throws SQLException
	{
		return getObjectImpl(i, map);
	}
}

