/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.core.v3;

import org.postgresql.PGNotification;
import org.postgresql.core.Encoding;
import org.postgresql.core.Logger;
import org.postgresql.core.PGStream;
import org.postgresql.core.ProtocolConnection;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.Utils;
import org.postgresql.util.HostSpec;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;


/**
 * ProtocolConnection implementation for the V3 protocol.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class ProtocolConnectionImpl implements ProtocolConnection {
  ProtocolConnectionImpl(PGStream pgStream, String user, String database, Properties info,
      Logger logger, int connectTimeout) {
    this.pgStream = pgStream;
    this.user = user;
    this.database = database;
    this.logger = logger;
    this.executor = new QueryExecutorImpl(this, pgStream, info, logger);
    // default value for server versions that don't report standard_conforming_strings
    this.standardConformingStrings = false;
    this.connectTimeout = connectTimeout;
  }

  public HostSpec getHostSpec() {
    return pgStream.getHostSpec();
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

  public int getServerVersionNum() {
    if (serverVersionNum != 0) {
      return serverVersionNum;
    }
    return serverVersionNum = Utils.parseServerVersionStr(serverVersion);
  }

  public synchronized boolean getStandardConformingStrings() {
    return standardConformingStrings;
  }

  public synchronized int getTransactionState() {
    return transactionState;
  }

  public synchronized PGNotification[] getNotifications() throws SQLException {
    PGNotification[] array = notifications.toArray(new PGNotification[notifications.size()]);
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
    try {
      if (logger.logDebug()) {
        logger.debug(" FE=> CancelRequest(pid=" + cancelPid + ",ckey=" + cancelKey + ")");
      }

      cancelStream =
          new PGStream(pgStream.getSocketFactory(), pgStream.getHostSpec(), connectTimeout);
      cancelStream.SendInteger4(16);
      cancelStream.SendInteger2(1234);
      cancelStream.SendInteger2(5678);
      cancelStream.SendInteger4(cancelPid);
      cancelStream.SendInteger4(cancelKey);
      cancelStream.flush();
      cancelStream.ReceiveEOF();
      cancelStream.close();
      cancelStream = null;
    } catch (IOException e) {
      // Safe to ignore.
      if (logger.logDebug()) {
        logger.debug("Ignoring exception on cancel request:", e);
      }
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
    if (closed) {
      return;
    }

    try {
      if (logger.logDebug()) {
        logger.debug(" FE=> Terminate");
      }

      pgStream.SendChar('X');
      pgStream.SendInteger4(4);
      pgStream.flush();
      pgStream.close();
    } catch (IOException ioe) {
      // Forget it.
      if (logger.logDebug()) {
        logger.debug("Discarding IOException on close:", ioe);
      }
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

  void setServerVersionNum(int serverVersionNum) {
    this.serverVersionNum = serverVersionNum;
  }

  void setBackendKeyData(int cancelPid, int cancelKey) {
    this.cancelPid = cancelPid;
    this.cancelKey = cancelKey;
  }

  //
  // Package-private accessors called by the query executor
  //

  synchronized void addWarning(SQLWarning newWarning) {
    if (warnings == null) {
      warnings = newWarning;
    } else {
      warnings.setNextWarning(newWarning);
    }
  }

  synchronized void addNotification(PGNotification notification) {
    notifications.add(notification);
  }

  synchronized void setTransactionState(int state) {
    transactionState = state;
  }

  synchronized void setStandardConformingStrings(boolean value) {
    standardConformingStrings = value;
  }

  public int getProtocolVersion() {
    return 3;
  }

  public int getBackendPID() {
    return cancelPid;
  }

  public boolean useBinaryForReceive(int oid) {
    return useBinaryForOids.contains(oid);
  }

  public void setBinaryReceiveOids(Set<Integer> oids) {
    useBinaryForOids.clear();
    useBinaryForOids.addAll(oids);
  }

  public void setIntegerDateTimes(boolean state) {
    integerDateTimes = state;
  }

  public boolean getIntegerDateTimes() {
    return integerDateTimes;
  }

  public void abort() {
    try {
      pgStream.getSocket().close();
    } catch (IOException e) {
      // ignore
    }
    closed = true;
  }

  public void setTimeZone(TimeZone timeZone) {
    this.timeZone = timeZone;
  }

  @Override
  public TimeZone getTimeZone() {
    return timeZone;
  }

  /**
   * True if server uses integers for date and time fields. False if server uses double.
   */
  private boolean integerDateTimes;

  /**
   * Bit set that has a bit set for each oid which should be received using binary format.
   */
  private final Set<Integer> useBinaryForOids = new HashSet<Integer>();
  private String serverVersion;
  private int serverVersionNum = 0;
  private int cancelPid;
  private int cancelKey;

  private boolean standardConformingStrings;
  private int transactionState;
  private SQLWarning warnings;

  private boolean closed = false;

  private final ArrayList<PGNotification> notifications = new ArrayList<PGNotification>();

  private final PGStream pgStream;
  private final String user;
  private final String database;
  private final QueryExecutorImpl executor;
  private final Logger logger;

  private final int connectTimeout;

  /**
   * TimeZone of the current connection (TimeZone backend parameter)
   */
  private TimeZone timeZone;
}
