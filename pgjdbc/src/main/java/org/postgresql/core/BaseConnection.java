/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.PGConnection;
import org.postgresql.jdbc.FieldMetadata;
import org.postgresql.jdbc.PgResultSet;
import org.postgresql.jdbc.TimestampUtils;
import org.postgresql.util.LruCache;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;

/**
 * Driver-internal connection interface. Application code should not use this interface.
 */
public interface BaseConnection extends PGConnection, Connection {
  /**
   * Cancel the current query executing on this connection.
   *
   * @throws SQLException if something goes wrong.
   */
  void cancelQuery() throws SQLException;

  /**
   * Execute a SQL query that returns a single resultset. Never causes a new transaction to be
   * started regardless of the autocommit setting.
   *
   * @param s the query to execute
   * @return the (non-null) returned resultset
   * @throws SQLException if something goes wrong.
   */
  ResultSet execSQLQuery(String s) throws SQLException;

  ResultSet execSQLQuery(String s, int resultSetType, int resultSetConcurrency)
      throws SQLException;

  /**
   * Execute a SQL query that does not return results. Never causes a new transaction to be started
   * regardless of the autocommit setting.
   *
   * @param s the query to execute
   * @throws SQLException if something goes wrong.
   */
  void execSQLUpdate(String s) throws SQLException;

  /**
   * Get the QueryExecutor implementation for this connection.
   *
   * @return the (non-null) executor
   */
  QueryExecutor getQueryExecutor();

  /**
   * Internal protocol for work with physical and logical replication. Physical replication available
   * only since PostgreSQL version 9.1. Logical replication available only since PostgreSQL version 9.4.
   *
   * @return not null replication protocol
   */
  ReplicationProtocol getReplicationProtocol();

  /**
   * {@inheritDoc}
   *
   * @implSpec  Performs a defensive copy, per specification that returned map should be
   *            modifiable.  Use {@link #getTypeMapNoCopy()} to avoid this defensive copy.
   *
   * @see  #getTypeMapNoCopy()
   */
  @Override
  Map<String, Class<?>> getTypeMap() throws SQLException;

  /**
   * Gets the unmodifiable type map for this connection.
   * This will likely perform better than {@link #getTypeMap()}
   * because no defensive copy is made.
   */
  Map<String, Class<?>> getTypeMapNoCopy();

  /**
   * Gets the unmodifiable map used for type inference of custom types.
   * This is required because the server returns base types for domains.
   *
   * <p>
   * The indirect (inherited) inference is used to decouple type from implementation.
   * This allows the API user to select an interface, while the implementation has registered
   * the direct type implementation.
   * </p>
   *
   * @param  directOnly  Should only exact matches be returned (exactly match types in {@link #getTypeMap()},
   *                     versus including those inherited from all classes and interfaces?
   *                     It is expected to generally check for direct inference first, then fall-back to inherited.
   */
  Map<Class<?>, Set<String>> getInferenceMap(boolean directOnly);

  /**
   * Implementation of {@link #getObject(java.util.Map, java.lang.String, java.lang.String, byte[])} for custom types
   * once the custom type is known.
   *
   * <p>
   * This is present for type inference implemented by {@code PgResultSet.getObject(..., Class<T> type)}, which is
   * a consequence of the server sending base types for domains.
   * </p>
   *
   * @see  #getObject(java.util.Map, java.lang.String, java.lang.String, byte[])
   * @see  PgResultSet#getObject(java.lang.String, java.lang.Class)
   * @see  PgResultSet#getObject(int, java.lang.Class)
   */
  <T> T getObjectCustomType(Map<String, Class<?>> map, String type, Class<? extends T> customType, String value, byte[] byteValue) throws SQLException;

  /**
   * <p>Construct and return an appropriate object for the given type and value. This considers
   * the given type map, then the types registered via {@link org.postgresql.PGConnection#addDataType(String, Class)}
   * and {@link org.postgresql.PGConnection#addDataType(String, String)}.
   * </p>
   *
   * <p>If no class is registered as handling the given type, then a generic
   * {@link org.postgresql.util.PGobject} instance is returned.</p>
   *
   * @param map The type map in effect, which would come from one of:
   *            <ul>
   *            <li>{@link #getTypeMapNoCopy()}</li>
   *            <li>{@link ResultSet#getObject(java.lang.String, java.util.Map)}</li>
   *            <li>{@link ResultSet#getObject(int, java.util.Map)}</li>
   *            </ul>
   * @param type the backend typename
   * @param value the type-specific string representation of the value
   * @param byteValue the type-specific binary representation of the value
   * @return an appropriate object; never null.
   * @throws SQLException if something goes wrong
   */
  Object getObject(Map<String,Class<?>> map, String type, String value, byte[] byteValue) throws SQLException;

  /**
   * Calls {@link #getObject(java.util.Map, java.lang.String, java.lang.String, byte[])} with the connection's
   * current {@link #getTypeMapNoCopy() type map}.
   *
   * @see  #getTypeMapNoCopy()
   * @see  #getObject(java.util.Map, java.lang.String, java.lang.String, byte[])
   */
  Object getObject(String type, String value, byte[] byteValue) throws SQLException;

  Encoding getEncoding() throws SQLException;

  TypeInfo getTypeInfo();

  /**
   * <p>Check if we have at least a particular server version.</p>
   *
   * <p>The input version is of the form xxyyzz, matching a PostgreSQL version like xx.yy.zz. So 9.0.12
   * is 90012.</p>
   *
   * @param ver the server version to check, of the form xxyyzz eg 90401
   * @return true if the server version is at least "ver".
   */
  boolean haveMinimumServerVersion(int ver);

  /**
   * <p>Check if we have at least a particular server version.</p>
   *
   * <p>The input version is of the form xxyyzz, matching a PostgreSQL version like xx.yy.zz. So 9.0.12
   * is 90012.</p>
   *
   * @param ver the server version to check
   * @return true if the server version is at least "ver".
   */
  boolean haveMinimumServerVersion(Version ver);

  /**
   * Encode a string using the database's client_encoding (usually UTF8, but can vary on older
   * server versions). This is used when constructing synthetic resultsets (for example, in metadata
   * methods).
   *
   * @param str the string to encode
   * @return an encoded representation of the string
   * @throws SQLException if something goes wrong.
   */
  byte[] encodeString(String str) throws SQLException;

  /**
   * Escapes a string for use as string-literal within an SQL command. The method chooses the
   * applicable escaping rules based on the value of {@link #getStandardConformingStrings()}.
   *
   * @param str a string value
   * @return the escaped representation of the string
   * @throws SQLException if the string contains a <tt>\0</tt> character
   */
  String escapeString(String str) throws SQLException;

  /**
   * Returns whether the server treats string-literals according to the SQL standard or if it uses
   * traditional PostgreSQL escaping rules. Versions up to 8.1 always treated backslashes as escape
   * characters in string-literals. Since 8.2, this depends on the value of the
   * <tt>standard_conforming_strings</tt> server variable.
   *
   * @return true if the server treats string literals according to the SQL standard
   * @see QueryExecutor#getStandardConformingStrings()
   */
  boolean getStandardConformingStrings();

  // Ew. Quick hack to give access to the connection-specific utils implementation.
  TimestampUtils getTimestampUtils();

  // Get the per-connection logger.
  java.util.logging.Logger getLogger();

  // Get the bind-string-as-varchar config flag
  boolean getStringVarcharFlag();

  /**
   * Get the current transaction state of this connection.
   *
   * @return current transaction state of this connection
   */
  TransactionState getTransactionState();

  /**
   * Returns true if value for the given oid should be sent using binary transfer. False if value
   * should be sent using text transfer.
   *
   * @param oid The oid to check.
   * @return True for binary transfer, false for text transfer.
   */
  boolean binaryTransferSend(int oid);

  /**
   * Return whether to disable column name sanitation.
   *
   * @return true column sanitizer is disabled
   */
  boolean isColumnSanitiserDisabled();

  /**
   * Schedule a TimerTask for later execution. The task will be scheduled with the shared Timer for
   * this connection.
   *
   * @param timerTask timer task to schedule
   * @param milliSeconds delay in milliseconds
   */
  void addTimerTask(TimerTask timerTask, long milliSeconds);

  /**
   * Invoke purge() on the underlying shared Timer so that internal resources will be released.
   */
  void purgeTimerTasks();

  /**
   * Return metadata cache for given connection.
   *
   * @return metadata cache
   */
  LruCache<FieldMetadata.Key, FieldMetadata> getFieldMetadataCache();

  CachedQuery createQuery(String sql, boolean escapeProcessing, boolean isParameterized,
      String... columnNames)
      throws SQLException;

  /**
   * By default, the connection resets statement cache in case deallocate all/discard all
   * message is observed.
   * This API allows to disable that feature for testing purposes.
   *
   * @param flushCacheOnDeallocate true if statement cache should be reset when "deallocate/discard" message observed
   */
  void setFlushCacheOnDeallocate(boolean flushCacheOnDeallocate);
}
