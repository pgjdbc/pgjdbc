package org.postgresql.jdbc2;

import java.sql.*;

class Jdbc2CallableStatement extends Jdbc2PreparedStatement implements CallableStatement
{
	Jdbc2CallableStatement(Jdbc2Connection connection, String sql, int rsType, int rsConcurrency) throws SQLException
	{
		super(connection, sql, true, rsType, rsConcurrency);
	}
}

