/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v2;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.Set;

import org.postgresql.PGNotification;
import org.postgresql.core.Encoding;
import org.postgresql.core.Logger;
import org.postgresql.core.PGStream;
import org.postgresql.core.ProtocolConnection;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.Utils;
import org.postgresql.util.HostSpec;

/**
 * V2 implementation of ProtocolConnection.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class ProtocolConnectionImpl implements ProtocolConnection {
    ProtocolConnectionImpl(PGStream pgStream, String user, String database, Logger logger, int connectTimeout) {
        this.pgStream = pgStream;
        this.user = user;
        this.database = database;
        this.logger = logger;
        this.executor = new QueryExecutorImpl(this, pgStream, logger);
        this.connectTimeout = connectTimeout;
    }

    @Override
	public HostSpec getHostSpec() {
        return pgStream.getHostSpec();
    }

    @Override
	public String getUser() {
        return user;
    }

    @Override
	public String getDatabase() {
        return database;
    }

    @Override
	public String getServerVersion() {
        return serverVersion;
    }

    @Override
	public int getServerVersionNum() {
        if (serverVersionNum != 0) {
			return serverVersionNum;
		}
        return Utils.parseServerVersionStr(serverVersion);
    }

    @Override
	public synchronized boolean getStandardConformingStrings()
    {
        return standardConformingStrings;
    }

    @Override
	public synchronized int getTransactionState()
    {
        return transactionState;
    }

    @Override
	public synchronized PGNotification[] getNotifications() throws SQLException {
        PGNotification[] array = (PGNotification[])notifications.toArray(new PGNotification[notifications.size()]);
        notifications.clear();
        return array;
    }

    @Override
	public synchronized SQLWarning getWarnings()
    {
        SQLWarning chain = warnings;
        warnings = null;
        return chain;
    }

    @Override
	public QueryExecutor getQueryExecutor() {
        return executor;
    }

    @Override
	public void sendQueryCancel() throws SQLException {
        if (cancelPid <= 0) {
			return ;
		}

        PGStream cancelStream = null;

        // Now we need to construct and send a cancel packet
        try
        {
            if (logger.logDebug()) {
				logger.debug(" FE=> CancelRequest(pid=" + cancelPid + ",ckey=" + cancelKey + ")");
			}

            cancelStream = new PGStream(pgStream.getHostSpec(), connectTimeout, pgStream.getSocketFactory());
            cancelStream.SendInteger4(16);
            cancelStream.SendInteger2(1234);
            cancelStream.SendInteger2(5678);
            cancelStream.SendInteger4(cancelPid);
            cancelStream.SendInteger4(cancelKey);
            cancelStream.flush();
            cancelStream.ReceiveEOF();
            cancelStream.close();
            cancelStream = null;
        }
        catch (IOException e)
        {
            // Safe to ignore.
            if (logger.logDebug()) {
				logger.debug("Ignoring exception on cancel request:", e);
			}
        }
        finally
        {
            if (cancelStream != null)
            {
                try
                {
                    cancelStream.close();
                }
                catch (IOException e)
                {
                    // Ignored.
                }
            }
        }
    }

    @Override
	public void close() {
        if (closed) {
			return ;
		}

        try
        {
            if (logger.logDebug()) {
				logger.debug(" FE=> Terminate");
			}
            pgStream.SendChar('X');
            pgStream.flush();
            pgStream.close();
        }
        catch (IOException ioe)
        {
            // Forget it.
            if (logger.logDebug()) {
				logger.debug("Discarding IOException on close:", ioe);
			}
        }

        closed = true;
    }

    @Override
	public Encoding getEncoding() {
        return pgStream.getEncoding();
    }

    @Override
	public boolean isClosed() {
        return closed;
    }

    //
    // Package-private accessors called during connection setup
    //

    void setEncoding(Encoding encoding) throws IOException {
        pgStream.setEncoding(encoding);
    }

    void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    void setServerVersionNum(int serverVersionNum) {
        this.serverVersionNum = serverVersionNum;
    }

    void setBackendKeyData(int cancelPid, int cancelKey) {
        this.cancelPid = cancelPid;
        this.cancelKey = cancelKey;
    }

    synchronized void setStandardConformingStrings(boolean value) {
        standardConformingStrings = value;
    }

    //
    // Package-private accessors called by the query executor
    //

    synchronized void addWarning(SQLWarning newWarning)
    {
        if (warnings == null) {
			warnings = newWarning;
		} else {
			warnings.setNextWarning(newWarning);
		}
    }

    synchronized void addNotification(PGNotification notification)
    {
        notifications.add(notification);
    }

    synchronized void setTransactionState(int state)
    {
        transactionState = state;
    }

    @Override
	public int getProtocolVersion()
    {
        return 2;
    }

    @Override
	public void setBinaryReceiveOids(Set<Integer> ignored) {
        // ignored for v2 connections
    }

    @Override
	public boolean getIntegerDateTimes() {
        // not supported in v2 protocol
        return false;
    }

    @Override
	public int getBackendPID()
    {
    	return cancelPid;
    }

    @Override
	public void abort() {
        try
        {
            pgStream.getSocket().close();
        }
        catch (IOException e)
        {
            // ignore
        }
        closed = true;
    }

    private String serverVersion;
    private int serverVersionNum = 0;
    private int cancelPid;
    private int cancelKey;

    private boolean standardConformingStrings;
    private int transactionState;
    private SQLWarning warnings;

    private boolean closed = false;

    private final ArrayList notifications = new ArrayList();

    private final PGStream pgStream;
    private final String user;
    private final String database;
    private final QueryExecutorImpl executor;
    private final Logger logger;

    private final int connectTimeout;
}
