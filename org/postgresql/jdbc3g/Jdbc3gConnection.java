package org.postgresql.jdbc3g;

import java.util.Map;
import java.util.Properties;
import java.sql.SQLException;

/* $PostgreSQL: /cvsroot/pgsql-server/src/interfaces/jdbc/org/postgresql/jdbc3/Jdbc3Connection.java,v 1.5 2003/05/29 04:39:50 barry Exp $
 * This class implements the java.sql.Connection interface for JDBC3.
 * However most of the implementation is really done in
 * org.postgresql.jdbc3.AbstractJdbc3Connection or one of it's parents
 */
public class Jdbc3gConnection extends org.postgresql.jdbc3.AbstractJdbc3Connection implements java.sql.Connection
{
	public Jdbc3gConnection(String host, int port, String user, String database, Properties info, String url) throws SQLException {
		super(host, port, user, database, info, url);
	}

	public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
	{
		Jdbc3gStatement s = new Jdbc3gStatement(this, resultSetType, resultSetConcurrency, resultSetHoldability);
		s.setPrepareThreshold(getPrepareThreshold());
		return s;
	}


	public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
	{
		Jdbc3gPreparedStatement s = new Jdbc3gPreparedStatement(this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
		s.setPrepareThreshold(getPrepareThreshold());
		return s;
	}

	public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
	{
		Jdbc3gCallableStatement s = new Jdbc3gCallableStatement(this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
		s.setPrepareThreshold(getPrepareThreshold());
		return s;
	}

	public java.sql.DatabaseMetaData getMetaData() throws SQLException
	{
		if (metadata == null)
			metadata = new Jdbc3gDatabaseMetaData(this);
		return metadata;
	}

	public void setTypeMap(Map<String, Class<?>> map) throws SQLException
	{
		setTypeMapImpl(map);
	}

}
