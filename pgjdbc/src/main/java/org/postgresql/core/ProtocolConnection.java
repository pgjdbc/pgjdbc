/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.core;

import org.postgresql.PGNotification;
import org.postgresql.util.HostSpec;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Set;
import java.util.TimeZone;

/**
 * Provides access to protocol-level connection operations.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
public interface ProtocolConnection {
  /**
   * Constant returned by {@link #getTransactionState} indicating that no transaction is currently
   * open.
   */
  static final int TRANSACTION_IDLE = 0;

  /**
   * Constant returned by {@link #getTransactionState} indicating that a transaction is currently
   * open.
   */
  static final int TRANSACTION_OPEN = 1;

  /**
   * Constant returned by {@link #getTransactionState} indicating that a transaction is currently
   * open, but it has seen errors and will refuse subsequent queries until a ROLLBACK.
   */
  static final int TRANSACTION_FAILED = 2;

  /**
   * @return the host and port this connection is connected to.
   */
  HostSpec getHostSpec();

  /**
   * @return the user this connection authenticated as.
   */
  String getUser();

  /**
   * @return the database this connection is connected to.
   */
  String getDatabase();

  /**
   * Return the server version from the server_version GUC.
   *
   * Note that there's no requirement for this to be numeric or of the form x.y.z. PostgreSQL
   * development releases usually have the format x.ydevel e.g. 9.4devel; betas usually x.ybetan
   * e.g. 9.4beta1. The --with-extra-version configure option may add an arbitrary string to this.
   *
   * Don't use this string for logic, only use it when displaying the server version to the user.
   * Prefer getServerVersionNum() for all logic purposes.
   *
   * @return the server version string from the server_version guc
   */
  String getServerVersion();

  /**
   * Get a machine-readable server version.
   *
   * This returns the value of the server_version_num GUC. If no such GUC exists, it falls back on
   * attempting to parse the text server version for the major version. If there's no minor version
   * (e.g. a devel or beta release) then the minor version is set to zero. If the version could not
   * be parsed, zero is returned.
   *
   * @return the server version in numeric XXYYZZ form, eg 090401, from server_version_num
   */
  int getServerVersionNum();

  /**
   * @return the current encoding in use by this connection
   */
  Encoding getEncoding();

  /**
   * Returns whether the server treats string-literals according to the SQL standard or if it uses
   * traditional PostgreSQL escaping rules. Versions up to 8.1 always treated backslashes as escape
   * characters in string-literals. Since 8.2, this depends on the value of the
   * <tt>standard_conforming_strings</tt> server variable.
   *
   * @return true if the server treats string literals according to the SQL standard
   */
  boolean getStandardConformingStrings();

  /**
   * Get the current transaction state of this connection.
   *
   * @return a ProtocolConnection.TRANSACTION_* constant.
   */
  int getTransactionState();

  /**
   * Retrieve and clear the set of asynchronous notifications pending on this connection.
   *
   * @return an array of notifications; if there are no notifications, an empty array is returned.
   * @throws SQLException if and error occurs while fetching notifications
   */
  PGNotification[] getNotifications() throws SQLException;

  /**
   * Retrieve and clear the chain of warnings accumulated on this connection.
   *
   * @return the first SQLWarning in the chain; subsequent warnings can be found via
   *         SQLWarning.getNextWarning().
   */
  SQLWarning getWarnings();

  /**
   * @return the QueryExecutor instance for this connection.
   */
  QueryExecutor getQueryExecutor();

  /**
   * Sends a query cancellation for this connection.
   *
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

  /**
   * @return the version of the implementation
   */
  public int getProtocolVersion();

  /**
   * Sets the oids that should be received using binary encoding.
   *
   * @param useBinaryForOids The oids to request with binary encoding.
   */
  public void setBinaryReceiveOids(Set<Integer> useBinaryForOids);

  /**
   * Returns true if server uses integer instead of double for binary date and time encodings.
   *
   * @return the server integer_datetime setting.
   */
  public boolean getIntegerDateTimes();

  /**
   * Return the process ID (PID) of the backend server process handling this connection.
   *
   * @return process ID (PID) of the backend server process handling this connection
   */
  public int getBackendPID();

  /**
   * Abort at network level without sending the Terminate message to the backend.
   */
  public void abort();

  /**
   * Return TimestampUtils that is aware of connection-specific {@code TimeZone} value.
   *
   * @return timestampUtils instance
   */

  /**
   * Returns backend timezone in java format.
   * @return backend timezone in java format.
   */
  TimeZone getTimeZone();
}
