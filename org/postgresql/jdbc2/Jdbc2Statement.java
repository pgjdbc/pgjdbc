package org.postgresql.jdbc2;

import java.sql.*;
import java.util.Vector;
import org.postgresql.core.*;

/* $PostgreSQL: /cvsroot/pgsql-server/src/interfaces/jdbc/org/postgresql/jdbc2/Jdbc2Statement.java,v 1.6 2003/05/03 20:40:45 barry Exp $
 * This class implements the java.sql.Statement interface for JDBC2.
 * However most of the implementation is really done in
 * org.postgresql.jdbc2.AbstractJdbc2Statement or one of it's parents
 */
class Jdbc2Statement extends AbstractJdbc2Statement implements Statement
{
	Jdbc2Statement (Jdbc2Connection c, int rsType, int rsConcurrency) throws SQLException
	{
		super(c, rsType, rsConcurrency);
	}

	protected Jdbc2Statement(Jdbc2Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency) throws SQLException
	{
		super(connection, sql, isCallable, rsType, rsConcurrency);
	}

	public ResultSet createResultSet (Query originalQuery, Field[] fields, Vector tuples, ResultCursor cursor)
		throws SQLException
	{
		Jdbc2ResultSet newResult = new Jdbc2ResultSet(originalQuery, this, fields, tuples, cursor,
													  getMaxRows(), getMaxFieldSize(),
													  getResultSetType(), getResultSetConcurrency());
		newResult.setFetchSize(getFetchSize());
		newResult.setFetchDirection(getFetchDirection());
		return newResult;
	}
}
