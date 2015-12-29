/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql;

import org.postgresql.copy.CopyManager;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * This interface defines the public PostgreSQL extensions to java.sql.Connection. All Connections
 * returned by the PostgreSQL driver implement PGConnection.
 */
public interface PGConnection {
  /**
   * This method returns any notifications that have been received since the last call to this
   * method. Returns null if there have been no notifications.
   *
   * @return notifications that have been received
   * @throws SQLException if something wrong happens
   * @since 7.3
   */
  public PGNotification[] getNotifications() throws SQLException;

  /**
   * This returns the COPY API for the current connection.
   *
   * @return COPY API for the current connection
   * @throws SQLException if something wrong happens
   * @since 8.4
   */
  public CopyManager getCopyAPI() throws SQLException;

  /**
   * This returns the LargeObject API for the current connection.
   *
   * @return LargeObject API for the current connection
   * @throws SQLException if something wrong happens
   * @since 7.3
   */
  public LargeObjectManager getLargeObjectAPI() throws SQLException;

  /**
   * This returns the Fastpath API for the current connection.
   *
   * @return Fastpath API for the current connection
   * @throws SQLException if something wrong happens
   * @since 7.3
   */
  public Fastpath getFastpathAPI() throws SQLException;

  /**
   * This allows client code to add a handler for one of org.postgresql's more unique data types. It
   * is approximately equivalent to <code>addDataType(type, Class.forName(name))</code>.
   *
   * @param type      JDBC type name
   * @param className class name
   * @throws RuntimeException if the type cannot be registered (class not found, etc).
   * @deprecated As of 8.0, replaced by {@link #addDataType(String, Class)}. This deprecated method
   * does not work correctly for registering classes that cannot be directly loaded by the JDBC
   * driver's classloader.
   */
  public void addDataType(String type, String className);

  /**
   * This allows client code to add a handler for one of org.postgresql's more unique data types.
   *
   * <p><b>NOTE:</b> This is not part of JDBC, but an extension.
   *
   * <p>The best way to use this is as follows:
   *
   * <pre>
   * ...
   * ((org.postgresql.PGConnection)myconn).addDataType("mytype", my.class.name.class);
   * ...
   * </pre>
   *
   * <p>where myconn is an open Connection to org.postgresql.
   *
   * <p>The handling class must extend org.postgresql.util.PGobject
   *
   * @param type  the PostgreSQL type to register
   * @param klass the class implementing the Java representation of the type; this class must
   *              implement {@link org.postgresql.util.PGobject}).
   * @throws SQLException if <code>klass</code> does not implement {@link org.postgresql.util.PGobject}).
   * @see org.postgresql.util.PGobject
   * @since 8.0
   */
  public void addDataType(String type, Class<? extends PGobject> klass)
      throws SQLException;

  /**
   * Set the default statement reuse threshold before enabling server-side prepare. See {@link
   * org.postgresql.PGStatement#setPrepareThreshold(int)} for details.
   *
   * @param threshold the new threshold
   * @since build 302
   */
  public void setPrepareThreshold(int threshold);

  /**
   * Get the default server-side prepare reuse threshold for statements created from this
   * connection.
   *
   * @return the current threshold
   * @since build 302
   */
  public int getPrepareThreshold();

  /**
   * Set the default fetch size for statements created from this connection
   *
   * @param fetchSize new default fetch size
   * @throws SQLException if specified negative <code>fetchSize</code> parameter
   * @see Statement#setFetchSize(int)
   */
  public void setDefaultFetchSize(int fetchSize) throws SQLException;


  /**
   * Get the default fetch size for statements created from this connection
   *
   * @return current state for default fetch size
   * @see PGProperty#DEFAULT_ROW_FETCH_SIZE
   * @see Statement#getFetchSize()
   */
  public int getDefaultFetchSize();

  /**
   * Return the process ID (PID) of the backend server process handling this connection.
   *
   * @return PID of backend server process.
   */
  public int getBackendPID();

  /**
   * Return the given string suitably quoted to be used as an identifier in an SQL statement string.
   * Quotes are added only if necessary (i.e., if the string contains non-identifier characters or
   * would be case-folded). Embedded quotes are properly doubled.
   *
   * @param identifier input identifier
   * @return the escaped identifier
   * @throws SQLException if something goes wrong
   */
  public String escapeIdentifier(String identifier) throws SQLException;

  /**
   * Return the given string suitably quoted to be used as a string literal in an SQL statement
   * string. Embedded single-quotes and backslashes are properly doubled. Note that quote_literal
   * returns null on null input.
   *
   * @param literal input literal
   * @return the quoted literal
   * @throws SQLException if something goes wrong
   */
  public String escapeLiteral(String literal) throws SQLException;
}
