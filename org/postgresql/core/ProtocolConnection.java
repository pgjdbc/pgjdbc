/*-------------------------------------------------------------------------
 *
 * ParameterList.java
 *	  Abstraction of protocol-version-specific connections.
 *
 * Copyright (c) 2004, Open Cloud Limited.
 *
 * IDENTIFICATION
 *	  $PostgreSQL$
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.core;

import org.postgresql.PGNotification;
import java.sql.*;

/**
 * Provides access to protocol-level connection operations.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
public interface ProtocolConnection {
	/**
	 * Constant returned by {@link #getTransactionState} indicating that no
	 * transaction is currently open.
	 */
	static final int TRANSACTION_IDLE   = 0;

	/**
	 * Constant returned by {@link #getTransactionState} indicating that a
	 * transaction is currently open.
	 */
	static final int TRANSACTION_OPEN   = 1;

	/**
	 * Constant returned by {@link #getTransactionState} indicating that a
	 * transaction is currently open, but it has seen errors and will
	 * refuse subsequent queries until a ROLLBACK.
	 */
	static final int TRANSACTION_FAILED = 2;

	/**
	 * @return the hostname this connection is connected to.
	 */
	String getHost();

	/**
	 * @return the port number this connection is connected to.
	 */
	int getPort();

	/**
	 * @return the user this connection authenticated as.
	 */
	String getUser();

	/**
	 * @return the database this connection is connected to.
	 */
	String getDatabase();

	/**
	 * @return the server version of the connected server, formatted as X.Y.Z.
	 */
	String getServerVersion();

	/**
	 * @return the current encoding in use by this connection
	 */
	Encoding getEncoding();

	/**
	 * Get the current transaction state of this connection.
	 * 
	 * @return a ProtocolConnection.TRANSACTION_* constant.
	 */
	int getTransactionState();

	/**
	 * Retrieve and clear the set of asynchronous notifications pending on this
	 * connection.
	 *
	 * @return an array of notifications; if there are no notifications, an empty
	 *   array is returned.
	 */
	PGNotification[] getNotifications();

	/**
	 * Retrieve and clear the chain of warnings accumulated on this connection.
	 *
	 * @return the first SQLWarning in the chain; subsequent warnings can be
	 *   found via SQLWarning.getNextWarning().
	 */
	SQLWarning getWarnings();

	/**
	 * @return the QueryExecutor instance for this connection.
	 */
	QueryExecutor getQueryExecutor();

	/**
	 * Sends a query cancellation for this connection.
	 * @throws SQLException if something goes wrong.
	 */
	void sendQueryCancel() throws SQLException;

	/**
	 * Close this connection cleanly.
	 */
	void close();

	/**
	 * Check if this connection is closed.
	 *
	 * @return true iff the connection is closed.
	 */
	boolean isClosed();
}
