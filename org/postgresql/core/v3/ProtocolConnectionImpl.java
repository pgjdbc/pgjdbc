/*-------------------------------------------------------------------------
 *
 * ProtocolConnectionImpl.java
 *	  ProtocolConnection implementation for the V3 protocol.
 *
 * Copyright (c) 2004, Open Cloud Limited.
 *
 * IDENTIFICATION
 *	  $PostgreSQL$
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.core.v3;

import org.postgresql.PGNotification;
import org.postgresql.Driver;
import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.*;
import java.io.IOException;
import java.util.ArrayList;
import java.net.ConnectException;

/**
 * ProtocolConnection implementation for the V3 protocol.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class ProtocolConnectionImpl implements ProtocolConnection {
	ProtocolConnectionImpl(PGStream pgStream, String user, String database) {
		this.pgStream = pgStream;
		this.user = user;
		this.database = database;
		this.executor = new QueryExecutorImpl(this, pgStream);
	}

	public String getHost() {
		return pgStream.getHost();
	}

	public int getPort() {
		return pgStream.getPort();
	}

	public String getUser() {
		return user;
	}

	public String getDatabase() {
		return database;
	}

	public String getServerVersion() {
		return serverVersion;
	}

	public synchronized int getTransactionState() {
		return transactionState;
	}

	public synchronized PGNotification[] getNotifications() {
		PGNotification[] array = (PGNotification[])notifications.toArray(new PGNotification[notifications.size()]);
		notifications.clear();
		return array;
	}

	public synchronized SQLWarning getWarnings() {
		SQLWarning chain = warnings;
		warnings = null;
		return chain;
	}

	public QueryExecutor getQueryExecutor() {
		return executor;
	}

	public void sendQueryCancel() throws SQLException {
		PGStream cancelStream = null;
		
		// Now we need to construct and send a cancel packet
		try
		{
			if (Driver.logDebug)
				Driver.debug(" FE=> CancelRequest(pid=" + cancelPid + ",ckey=" + cancelKey + ")");

			cancelStream = new PGStream(pgStream.getHost(), pgStream.getPort());
			cancelStream.SendInteger4(16);
			cancelStream.SendInteger2(1234);
			cancelStream.SendInteger2(5678);
			cancelStream.SendInteger4(cancelPid);
			cancelStream.SendInteger4(cancelKey);
			cancelStream.flush();
			cancelStream.close();
			cancelStream = null;
		} catch (IOException e) {
			// Safe to ignore.
			if (Driver.logDebug)
				Driver.debug("Ignoring exception on cancel request:", e);
		} finally {
			if (cancelStream != null) {
				try {
					cancelStream.close();
				} catch (IOException e) {
					// Ignored.
				}
			}
		}
	}

	public void close() {
		if (closed)
			return;

		try {
			if (Driver.logDebug)
				Driver.debug(" FE=> Terminate");

			pgStream.SendChar('X');
			pgStream.SendInteger4(4);
			pgStream.flush();
			pgStream.close();
		} catch (IOException ioe) {
			// Forget it.
			if (Driver.logDebug)
				Driver.debug("Discarding IOException on close:", ioe);
		}

		closed = true;
	}

 	public Encoding getEncoding() {
		return pgStream.getEncoding();
	}

	public boolean isClosed() {
		return closed;
	}

	//
	// Package-private accessors called during connection setup
	//

	void setServerVersion(String serverVersion) {
		this.serverVersion = serverVersion;
	}

	void setBackendKeyData(int cancelPid, int cancelKey) {
		this.cancelPid = cancelPid;
		this.cancelKey = cancelKey;
	}

	//
	// Package-private accessors called by the query executor
	//

	synchronized void addWarning(SQLWarning newWarning) {
		if (warnings == null)
			warnings = newWarning;
		else
			warnings.setNextWarning(newWarning);
	}

	synchronized void addNotification(PGNotification notification) {
		notifications.add(notification);
	}

	synchronized void setTransactionState(int state) {
		transactionState = state;
	}

	private String serverVersion;
	private int cancelPid;
	private int cancelKey;

	private int transactionState;
	private SQLWarning warnings;

	private boolean closed = false;

	private final ArrayList notifications = new ArrayList();

	private final PGStream pgStream;
	private final String user;
	private final String database;
	private final QueryExecutorImpl executor;
}
