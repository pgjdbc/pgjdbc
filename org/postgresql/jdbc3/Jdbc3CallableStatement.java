package org.postgresql.jdbc3;

import java.sql.*;

class Jdbc3CallableStatement extends Jdbc3PreparedStatement implements CallableStatement
{
	Jdbc3CallableStatement(Jdbc3Connection connection, String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
	{
		super(connection, sql, true, rsType, rsConcurrency, rsHoldability);
	}
}
