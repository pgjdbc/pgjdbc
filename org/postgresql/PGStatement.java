/*-------------------------------------------------------------------------
 *
 * PGStatement.java
 *     This interface defines PostgreSQL extentions to the java.sql.Statement
 *     interface.  Any java.sql.Statement object returned by the driver will 
 *     also implement this interface
 *
 * Copyright (c) 2003, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL: /cvsroot/pgsql-server/src/interfaces/jdbc/org/postgresql/PGStatement.java,v 1.7 2003/03/07 18:39:41 barry Exp $
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql;


import java.sql.*;

public interface PGStatement
{

	/**
	 * Returns the Last inserted/updated oid.
	 * @return OID of last insert
   	 * @since 7.3
	 */
	public long getLastOID() throws SQLException;

	/**
	 * Turn on the use of prepared statements in the server (server side
	 * prepared statements are unrelated to jdbc PreparedStatements)
	 * As of 7.5, this method is equivalent to <code>setPrepareThreshold(1)</code>.
	 *
	 * @deprecated As of 7.5, replaced by {@link #setPrepareThreshold(int)}
   	 * @since 7.3
	 */
	public void setUseServerPrepare(boolean flag) throws SQLException;

	/**
	 * Checks if this statement will be executed as a server-prepared statement.
	 * A return value of <code>true</code> indicates that the next execution of the
	 * statement, <em>with an unchanged query</em>, via a PreparedStatement variant of
	 * execute(), will be done as a server-prepared statement.
	 *
	 * @return true if the next reuse of this statement will use a server-prepared statement
	 */
	public boolean isUseServerPrepare();

	/**
	 * Sets the reuse threshold for using server-prepared statements.
	 *<p>
	 * If <code>threshold</code> is a non-zero value N, the Nth and subsequent
	 * uses of a statement for the same query will use server-side
	 * prepare. A query is currently only considered the "same" if the statement
	 * is a PreparedStatement, and none of the base Statement query execution methods
	 * that take an explicit query string have been called.
	 *<p>
	 * If <code>threshold</code> is zero, server-side prepare will not be used.
	 *
	 * @param threshold the new threshold for this statement
	 * @throws SQLException if an exception occurs while changing the threshold
	 * @since 7.5
	 */
	public void setPrepareThreshold(int threshold) throws SQLException;
	
	/**
	 * Gets the server-side prepare reuse threshold in use for this statement.
	 *
	 * @return the current threshold
	 * @see #setPrepareThreshold(int)
	 * @since 7.5
	 */
	public int getPrepareThreshold();
}
