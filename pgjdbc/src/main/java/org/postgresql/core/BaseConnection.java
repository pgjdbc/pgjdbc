/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.jdbc.FieldMetadata;
import org.postgresql.jdbc.TimestampUtils;
import org.postgresql.util.LruCache;
import org.postgresql.xml.PGXmlFactoryFactory;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.checkerframework.dataflow.qual.Pure;

import javax.sql.PooledConnection;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

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
   * <p>Construct and return an appropriate object for the given type and value. This only considers
   * the types registered via {@link org.postgresql.PGConnection#addDataType(String, Class)} and
   * {@link org.postgresql.PGConnection#addDataType(String, String)}.</p>
   *
   * <p>If no class is registered as handling the given type, then a generic
   * {@link org.postgresql.util.PGobject} instance is returned.</p>
   *
   * <p>value or byteValue must be non-null</p>
   * @param type the backend typename
   * @param value the type-specific string representation of the value
   * @param byteValue the type-specific binary representation of the value
   * @return an appropriate object; never null.
   * @throws SQLException if something goes wrong
   */
  Object getObject(String type, @Nullable String value, byte @Nullable [] byteValue)
      throws SQLException;

  @Pure
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
  byte @PolyNull [] encodeString(@PolyNull String str) throws SQLException;

  /**
   * Escapes a string for use as string-literal within an SQL command. The method chooses the
   * applicable escaping rules based on the value of {@link #getStandardConformingStrings()}.
   *
   * @param str a string value
   * @return the escaped representation of the string
   * @throws SQLException if the string contains a {@code \0} character
   */
  String escapeString(String str) throws SQLException;

  /**
   * Returns whether the server treats string-literals according to the SQL standard or if it uses
   * traditional PostgreSQL escaping rules. Versions up to 8.1 always treated backslashes as escape
   * characters in string-literals. Since 8.2, this depends on the value of the
   * {@code standard_conforming_strings} server variable.
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

  /**
   * Indicates if statements to backend should be hinted as read only.
   *
   * @return Indication if hints to backend (such as when transaction begins)
   *         should be read only.
   * @see PGProperty#READ_ONLY_MODE
   */
  boolean hintReadOnly();

  /**
   * Retrieve the factory to instantiate XML processing factories.
   *
   * @return The factory to use to instantiate XML processing factories
   * @throws SQLException if the class cannot be found or instantiated.
   */
  PGXmlFactoryFactory getXmlFactoryFactory() throws SQLException;

  /**
   * Hints to the driver that a request, an independent unit of work, is beginning
   * on this connection. Each request is independent of all other requests
   * with regard to state local to the connection either on the client or the
   * server. Work done between {@code beginRequest}, {@code endRequest}
   * pairs does not depend on any other work done on the connection either as
   * part of another request or outside of any request. A request may include multiple
   * transactions. There may be dependencies on committed database state as
   * that is not local to the connection.
   * <p>
   * Local state is defined as any state associated with a Connection that is
   * local to the current Connection either in the client or the database that
   * is not transparently reproducible.
   * <p>
   * Calls to {@code beginRequest} and {@code endRequest}  are not nested.
   * Multiple calls to {@code beginRequest} without an intervening call
   * to {@code endRequest} is not an error. The first {@code beginRequest} call
   * marks the start of the request and subsequent calls are treated as
   * a no-op
   * <p>
   * Use of {@code beginRequest} and {@code endRequest} is optional, vendor
   * specific and should largely be transparent. In particular
   * implementations may detect conditions that indicate dependence on
   * other work such as an open transaction. It is recommended though not
   * required that implementations throw a {@code SQLException} if there is an active
   * transaction and {@code beginRequest} is called.
   * Using these methods may improve performance or provide other benefits.
   * Consult your vendors documentation for additional information.
   * <p>
   * It is recommended to
   * enclose each unit of work in {@code beginRequest}, {@code endRequest}
   * pairs such that there is no open transaction at the beginning or end of
   * the request and no dependency on local state that crosses request
   * boundaries. Committed database state is not local.
   *
   * @throws SQLException if an error occurs
   * @implSpec The default implementation is a no-op.
   * @apiNote This method is to be used by Connection pooling managers.
   * <p>
   * The pooling manager should call {@code beginRequest} on the underlying connection
   * prior to returning a connection to the caller.
   * <p>
   * The pooling manager does not need to call {@code beginRequest} if:
   * <ul>
   * <li>The connection pool caches {@code PooledConnection} objects</li>
   * <li>Returns a logical connection handle when {@code getConnection} is
   * called by the application</li>
   * <li>The logical {@code Connection} is closed by calling
   * {@code Connection.close} prior to returning the {@code PooledConnection}
   * to the cache.</li>
   * </ul>
   * @see endRequest
   * @see PooledConnection
   * @since 9
   */
  @Override
  default void beginRequest() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  /**
   * Hints to the driver that a request, an independent unit of work,
   * has completed. Calls to {@code beginRequest}
   * and {@code endRequest} are not nested. Multiple
   * calls to {@code endRequest} without an intervening call to {@code beginRequest}
   * is not an error. The first {@code endRequest} call
   * marks the request completed and subsequent calls are treated as
   * a no-op. If {@code endRequest} is called without an initial call to
   * {@code beginRequest} is a no-op.
   * <p>
   * The exact behavior of this method is vendor specific. In particular
   * implementations may detect conditions that indicate dependence on
   * other work such as an open transaction. It is recommended though not
   * required that implementations throw a {@code SQLException} if there is an active
   * transaction and {@code endRequest} is called.
   *
   * @throws SQLException if an error occurs
   * @implSpec The default implementation is a no-op.
   * @apiNote This method is to be used by Connection pooling managers.
   * <p>
   * The pooling manager should call {@code endRequest} on the underlying connection
   * when the applications returns the connection back to the connection pool.
   * <p>
   * The pooling manager does not need to call {@code endRequest} if:
   * <ul>
   * <li>The connection pool caches {@code PooledConnection} objects</li>
   * <li>Returns a logical connection handle when {@code getConnection} is
   * called by the application</li>
   * <li>The logical {@code Connection} is closed by calling
   * {@code Connection.close} prior to returning the {@code PooledConnection}
   * to the cache.</li>
   * </ul>
   * @see beginRequest
   * @see PooledConnection
   * @since 9
   */
  @Override
  default void endRequest() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  /**
   * Sets and validates the sharding keys for this connection. A {@code null}
   * value may be specified for the sharding Key. The validity
   * of a {@code null} sharding key is vendor-specific. Consult your vendor&#39;s
   * documentation for additional information.
   *
   * @param shardingKey      the sharding key to be validated against this connection.
   *                         The sharding key may be {@code null}
   * @param superShardingKey the super sharding key to be validated against this
   *                         connection. The super sharding key may be {@code null}.
   * @param timeout          time in seconds before which the validation process is expected to
   *                         be completed, otherwise the validation process is aborted. A value
   *                         of 0 indicates
   *                         the validation process will not time out.
   * @return true if the connection is valid and the sharding keys are valid
   * and set on this connection; false if the sharding keys are not valid or
   * the timeout period expires before the operation completes.
   * @throws SQLException                    if an error occurs while performing this validation;
   *                                         a {@code superSharedingKey} is specified
   *                                         without a {@code shardingKey};
   *                                         this method is called on a closed {@code connection}
   *                                         ; or
   *                                         the {@code timeout} value is negative.
   * @throws SQLFeatureNotSupportedException if the driver does not support sharding
   * @implSpec The default implementation will throw a
   * {@code SQLFeatureNotSupportedException}.
   * @apiNote This method validates that the sharding keys are valid for the
   * {@code Connection}. The timeout value indicates how long the driver
   * should wait for the {@code Connection} to verify that the sharding key
   * is valid before {@code setShardingKeyIfValid} returns false.
   * @see ShardingKey
   * @see ShardingKeyBuilder
   * @since 9
   */
  @Override
  default boolean setShardingKeyIfValid(ShardingKey shardingKey, ShardingKey superShardingKey,
      int timeout) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  /**
   * Sets and validates the sharding key for this connection. A {@code null}
   * value may be specified for the sharding Key. The validity
   * of a {@code null} sharding key is vendor-specific. Consult your vendor&#39;s
   * documentation for additional information.
   *
   * @param shardingKey the sharding key to be validated against this connection.
   *                    The sharding key may be {@code null}
   * @param timeout     time in seconds before which the validation process is expected to
   *                    be completed,else the validation process is aborted. A value of 0 indicates
   *                    the validation process will not time out.
   * @return true if the connection is valid and the sharding key is valid to be
   * set on this connection; false if the sharding key is not valid or
   * the timeout period expires before the operation completes.
   * @throws SQLException                    if there is an error while performing this validation;
   *                                         this method is called on a closed {@code connection};
   *                                         or the {@code timeout} value is negative.
   * @throws SQLFeatureNotSupportedException if the driver does not support sharding
   * @implSpec The default implementation will throw a
   * {@code SQLFeatureNotSupportedException}.
   * @apiNote This method validates  that the sharding key is valid for the
   * {@code Connection}. The timeout value indicates how long the driver
   * should wait for the {@code Connection} to verify that the sharding key
   * is valid before {@code setShardingKeyIfValid} returns false.
   * @see ShardingKey
   * @see ShardingKeyBuilder
   * @since 9
   */
  @Override
  default boolean setShardingKeyIfValid(ShardingKey shardingKey, int timeout) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  /**
   * Specifies a shardingKey and superShardingKey to use with this Connection
   *
   * @param shardingKey      the sharding key to set on this connection. The sharding
   *                         key may be {@code null}
   * @param superShardingKey the super sharding key to set on this connection.
   *                         The super sharding key may be {@code null}
   * @throws SQLException                    if an error  occurs setting the sharding keys;
   *                                         this method is called on a closed {@code connection}
   *                                         ; or
   *                                         a {@code superSharedingKey} is specified without a
   *                                         {@code shardingKey}
   * @throws SQLFeatureNotSupportedException if the driver does not support sharding
   * @implSpec The default implementation will throw a
   * {@code SQLFeatureNotSupportedException}.
   * @apiNote This method sets the specified sharding keys but does not require a
   * round trip to the database to validate that the sharding keys are valid
   * for the {@code Connection}.
   * @see ShardingKey
   * @see ShardingKeyBuilder
   * @since 9
   */
  @Override
  default void setShardingKey(ShardingKey shardingKey, ShardingKey superShardingKey) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  /**
   * Specifies a shardingKey to use with this Connection
   *
   * @param shardingKey the sharding key to set on this connection. The sharding
   *                    key may be {@code null}
   * @throws SQLException                    if an error occurs setting the sharding key; or
   *                                         this method is called on a closed {@code connection}
   * @throws SQLFeatureNotSupportedException if the driver does not support sharding
   * @implSpec The default implementation will throw a
   * {@code SQLFeatureNotSupportedException}.
   * @apiNote This method sets the specified sharding key but does not require a
   * round trip to the database to validate that the sharding key is valid
   * for the {@code Connection}.
   * @see ShardingKey
   * @see ShardingKeyBuilder
   * @since 9
   */
  @Override
  default void setShardingKey(ShardingKey shardingKey) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }
}
