/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ds.common;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.util.ExpressionProperties;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.URLCoder;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.NamingException;
import javax.naming.RefAddr;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.StringRefAddr;
import javax.sql.CommonDataSource;

/**
 * Base class for data sources and related classes.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */

public abstract class BaseDataSource implements CommonDataSource, Referenceable {
  private static final Logger LOGGER = Logger.getLogger(BaseDataSource.class.getName());

  // Standard properties, defined in the JDBC 2.0 Optional Package spec
  private String[] serverNames = new String[]{"localhost"};
  private @Nullable String databaseName = "";
  private @Nullable String user;
  private @Nullable String password;
  private int[] portNumbers = new int[]{0};

  // Map for all other properties
  protected Properties properties = new Properties();

  /*
   * Ensure the driver is loaded as JDBC Driver might be invisible to Java's ServiceLoader.
   * Usually, {@code Class.forName(...)} is not required as {@link DriverManager} detects JDBC drivers
   * via {@code META-INF/services/java.sql.Driver} entries. However there might be cases when the driver
   * is located at the application level classloader, thus it might be required to perform manual
   * registration of the driver.
   */
  static {
    try {
      Class.forName("org.postgresql.Driver");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
        "BaseDataSource is unable to load org.postgresql.Driver. Please check if you have proper PostgreSQL JDBC Driver jar on the classpath",
        e);
    }
  }

  /**
   * Gets a connection to the PostgreSQL database. The database is identified by the DataSource
   * properties serverName, databaseName, and portNumber. The user to connect as is identified by
   * the DataSource properties user and password.
   *
   * @return A valid database connection.
   * @throws SQLException Occurs when the database connection cannot be established.
   */
  public Connection getConnection() throws SQLException {
    return getConnection(user, password);
  }

  /**
   * Gets a connection to the PostgreSQL database. The database is identified by the DataSource
   * properties serverName, databaseName, and portNumber. The user to connect as is identified by
   * the arguments user and password, which override the DataSource properties by the same name.
   *
   * @param user     user
   * @param password password
   * @return A valid database connection.
   * @throws SQLException Occurs when the database connection cannot be established.
   */
  public Connection getConnection(@Nullable String user, @Nullable String password)
      throws SQLException {
    try {
      Connection con = DriverManager.getConnection(getUrl(), user, password);
      if (LOGGER.isLoggable(Level.FINE)) {
        LOGGER.log(Level.FINE, "Created a {0} for {1} at {2}",
            new Object[]{getDescription(), user, getUrl()});
      }
      return con;
    } catch (SQLException e) {
      LOGGER.log(Level.FINE, "Failed to create a {0} for {1} at {2}: {3}",
          new Object[]{getDescription(), user, getUrl(), e});
      throw e;
    }
  }

  /**
   * This implementation don't use a LogWriter.
   */
  @Override
  public @Nullable PrintWriter getLogWriter() {
    return null;
  }

  /**
   * This implementation don't use a LogWriter.
   *
   * @param printWriter Not used
   */
  @Override
  public void setLogWriter(@Nullable PrintWriter printWriter) {
    // NOOP
  }

  /**
   * Gets the name of the host the PostgreSQL database is running on.
   *
   * @return name of the host the PostgreSQL database is running on
   * @deprecated use {@link #getServerNames()}
   */
  @Deprecated
  public String getServerName() {
    return serverNames[0];
  }

  /**
   * Gets the name of the host(s) the PostgreSQL database is running on.
   *
   * @return name of the host(s) the PostgreSQL database is running on
   */
  public String[] getServerNames() {
    return serverNames;
  }

  /**
   * Sets the name of the host the PostgreSQL database is running on. If this is changed, it will
   * only affect future calls to getConnection. The default value is {@code localhost}.
   *
   * @param serverName name of the host the PostgreSQL database is running on
   * @deprecated use {@link #setServerNames(String[])}
   */
  @Deprecated
  public void setServerName(String serverName) {
    this.setServerNames(new String[]{serverName});
  }

  /**
   * Sets the name of the host(s) the PostgreSQL database is running on. If this is changed, it will
   * only affect future calls to getConnection. The default value is {@code localhost}.
   *
   * @param serverNames name of the host(s) the PostgreSQL database is running on
   */
  @SuppressWarnings("nullness")
  public void setServerNames(@Nullable String @Nullable [] serverNames) {
    if (serverNames == null || serverNames.length == 0) {
      this.serverNames = new String[]{"localhost"};
    } else {
      serverNames = serverNames.clone();
      for (int i = 0; i < serverNames.length; i++) {
        String serverName = serverNames[i];
        if (serverName == null || "".equals(serverName)) {
          serverNames[i] = "localhost";
        }
      }
      this.serverNames = serverNames;
    }
  }

  /**
   * Gets the name of the PostgreSQL database, running on the server identified by the serverName
   * property.
   *
   * @return name of the PostgreSQL database
   */
  public @Nullable String getDatabaseName() {
    return databaseName;
  }

  /**
   * Sets the name of the PostgreSQL database, running on the server identified by the serverName
   * property. If this is changed, it will only affect future calls to getConnection.
   *
   * @param databaseName name of the PostgreSQL database
   */
  public void setDatabaseName(@Nullable String databaseName) {
    this.databaseName = databaseName;
  }

  /**
   * Gets a description of this DataSource-ish thing. Must be customized by subclasses.
   *
   * @return description of this DataSource-ish thing
   */
  public abstract String getDescription();

  /**
   * Gets the user to connect as by default. If this is not specified, you must use the
   * getConnection method which takes a user and password as parameters.
   *
   * @return user to connect as by default
   */
  public @Nullable String getUser() {
    return user;
  }

  /**
   * Sets the user to connect as by default. If this is not specified, you must use the
   * getConnection method which takes a user and password as parameters. If this is changed, it will
   * only affect future calls to getConnection.
   *
   * @param user user to connect as by default
   */
  public void setUser(@Nullable String user) {
    this.user = user;
  }

  /**
   * Gets the password to connect with by default. If this is not specified but a password is needed
   * to log in, you must use the getConnection method which takes a user and password as parameters.
   *
   * @return password to connect with by default
   */
  public @Nullable String getPassword() {
    return password;
  }

  /**
   * Sets the password to connect with by default. If this is not specified but a password is needed
   * to log in, you must use the getConnection method which takes a user and password as parameters.
   * If this is changed, it will only affect future calls to getConnection.
   *
   * @param password password to connect with by default
   */
  public void setPassword(@Nullable String password) {
    this.password = password;
  }

  /**
   * Gets the port which the PostgreSQL server is listening on for TCP/IP connections.
   *
   * @return The port, or 0 if the default port will be used.
   * @deprecated use {@link #getPortNumbers()}
   */
  @Deprecated
  public int getPortNumber() {
    if (portNumbers == null || portNumbers.length == 0) {
      return 0;
    }
    return portNumbers[0];
  }

  /**
   * Gets the port(s) which the PostgreSQL server is listening on for TCP/IP connections.
   *
   * @return The port(s), or 0 if the default port will be used.
   */
  public int[] getPortNumbers() {
    return portNumbers;
  }

  /**
   * Sets the port which the PostgreSQL server is listening on for TCP/IP connections. Be sure the
   * -i flag is passed to postmaster when PostgreSQL is started. If this is not set, or set to 0,
   * the default port will be used.
   *
   * @param portNumber port which the PostgreSQL server is listening on for TCP/IP
   * @deprecated use {@link #setPortNumbers(int[])}
   */
  @Deprecated
  public void setPortNumber(int portNumber) {
    setPortNumbers(new int[]{portNumber});
  }

  /**
   * Sets the port(s) which the PostgreSQL server is listening on for TCP/IP connections. Be sure the
   * -i flag is passed to postmaster when PostgreSQL is started. If this is not set, or set to 0,
   * the default port will be used.
   *
   * @param portNumbers port(s) which the PostgreSQL server is listening on for TCP/IP
   */
  public void setPortNumbers(int @Nullable [] portNumbers) {
    if (portNumbers == null || portNumbers.length == 0) {
      portNumbers = new int[]{0};
    }
    this.portNumbers = Arrays.copyOf(portNumbers, portNumbers.length);
  }

  /**
   * @return command line options for this connection
   */
  public @Nullable String getOptions() {
    return PGProperty.OPTIONS.getOrDefault(properties);
  }

  /**
   * Set command line options for this connection
   *
   * @param options string to set options to
   */
  public void setOptions(@Nullable String options) {
    PGProperty.OPTIONS.set(properties, options);
  }

  /**
   * @return login timeout
   * @see PGProperty#LOGIN_TIMEOUT
   */
  @Override
  public int getLoginTimeout() {
    return PGProperty.LOGIN_TIMEOUT.getIntNoCheck(properties);
  }

  /**
   * @param loginTimeout login timeout
   * @see PGProperty#LOGIN_TIMEOUT
   */
  @Override
  public void setLoginTimeout(int loginTimeout) {
    PGProperty.LOGIN_TIMEOUT.set(properties, loginTimeout);
  }

  /**
   * @return connect timeout
   * @see PGProperty#CONNECT_TIMEOUT
   */
  public int getConnectTimeout() {
    return PGProperty.CONNECT_TIMEOUT.getIntNoCheck(properties);
  }

  /**
   * @param connectTimeout connect timeout
   * @see PGProperty#CONNECT_TIMEOUT
   */
  public void setConnectTimeout(int connectTimeout) {
    PGProperty.CONNECT_TIMEOUT.set(properties, connectTimeout);
  }

  /**
   *
   * @return GSS ResponseTimeout
   * @see PGProperty#GSS_RESPONSE_TIMEOUT
   */
  public int getGssResponseTimeout() {
    return PGProperty.GSS_RESPONSE_TIMEOUT.getIntNoCheck(properties);
  }

  /**
   *
   * @param gssResponseTimeout gss response timeout
   * @see PGProperty#GSS_RESPONSE_TIMEOUT
   */
  public void setGssResponseTimeout(int gssResponseTimeout) {
    PGProperty.GSS_RESPONSE_TIMEOUT.set(properties, gssResponseTimeout);
  }

  /**
   *
   * @return SSL ResponseTimeout
   * @see PGProperty#SSL_RESPONSE_TIMEOUT
   */
  public int getSslResponseTimeout() {
    return PGProperty.SSL_RESPONSE_TIMEOUT.getIntNoCheck(properties);
  }

  /**
   *
   * @param sslResponseTimeout ssl response timeout
   * @see PGProperty#SSL_RESPONSE_TIMEOUT
   */
  public void setSslResponseTimeout(int sslResponseTimeout) {
    PGProperty.SSL_RESPONSE_TIMEOUT.set(properties, sslResponseTimeout);
  }

  /**
   * @return protocol version
   * @see PGProperty#PROTOCOL_VERSION
   */
  public int getProtocolVersion() {
    if (!PGProperty.PROTOCOL_VERSION.isPresent(properties)) {
      return 0;
    } else {
      return PGProperty.PROTOCOL_VERSION.getIntNoCheck(properties);
    }
  }

  /**
   * @param protocolVersion protocol version
   * @see PGProperty#PROTOCOL_VERSION
   */
  public void setProtocolVersion(int protocolVersion) {
    if (protocolVersion == 0) {
      PGProperty.PROTOCOL_VERSION.set(properties, null);
    } else {
      PGProperty.PROTOCOL_VERSION.set(properties, protocolVersion);
    }
  }

  /**
   * @return quoteReturningIdentifiers
   * @see PGProperty#QUOTE_RETURNING_IDENTIFIERS
   */
  public boolean getQuoteReturningIdentifiers() {
    return PGProperty.QUOTE_RETURNING_IDENTIFIERS.getBoolean(properties);
  }

  /**
   * @param quoteIdentifiers indicate whether to quote identifiers
   * @see PGProperty#QUOTE_RETURNING_IDENTIFIERS
   */
  public void setQuoteReturningIdentifiers(boolean quoteIdentifiers) {
    PGProperty.QUOTE_RETURNING_IDENTIFIERS.set(properties, quoteIdentifiers);
  }

  /**
   * @return receive buffer size
   * @see PGProperty#RECEIVE_BUFFER_SIZE
   */
  public int getReceiveBufferSize() {
    return PGProperty.RECEIVE_BUFFER_SIZE.getIntNoCheck(properties);
  }

  /**
   * @param nbytes receive buffer size
   * @see PGProperty#RECEIVE_BUFFER_SIZE
   */
  public void setReceiveBufferSize(int nbytes) {
    PGProperty.RECEIVE_BUFFER_SIZE.set(properties, nbytes);
  }

  /**
   * @return send buffer size
   * @see PGProperty#SEND_BUFFER_SIZE
   */
  public int getSendBufferSize() {
    return PGProperty.SEND_BUFFER_SIZE.getIntNoCheck(properties);
  }

  /**
   * @param nbytes send buffer size
   * @see PGProperty#SEND_BUFFER_SIZE
   */
  public void setSendBufferSize(int nbytes) {
    PGProperty.SEND_BUFFER_SIZE.set(properties, nbytes);
  }

  /**
   * @return send max buffer size
   * @see PGProperty#MAX_SEND_BUFFER_SIZE
   */
  public int getMaxSendBufferSize() {
    return PGProperty.MAX_SEND_BUFFER_SIZE.getIntNoCheck(properties);
  }

  /**
   * @param nbytes send max buffer size
   * @see PGProperty#MAX_SEND_BUFFER_SIZE
   */
  public void setMaxSendBufferSize(int nbytes) {
    PGProperty.MAX_SEND_BUFFER_SIZE.set(properties, nbytes);
  }

  /**
   * @param count prepare threshold
   * @see PGProperty#PREPARE_THRESHOLD
   */
  public void setPrepareThreshold(int count) {
    PGProperty.PREPARE_THRESHOLD.set(properties, count);
  }

  /**
   * @return prepare threshold
   * @see PGProperty#PREPARE_THRESHOLD
   */
  public int getPrepareThreshold() {
    return PGProperty.PREPARE_THRESHOLD.getIntNoCheck(properties);
  }

  /**
   * @return prepared statement cache size (number of statements per connection)
   * @see PGProperty#PREPARED_STATEMENT_CACHE_QUERIES
   */
  public int getPreparedStatementCacheQueries() {
    return PGProperty.PREPARED_STATEMENT_CACHE_QUERIES.getIntNoCheck(properties);
  }

  /**
   * @param cacheSize prepared statement cache size (number of statements per connection)
   * @see PGProperty#PREPARED_STATEMENT_CACHE_QUERIES
   */
  public void setPreparedStatementCacheQueries(int cacheSize) {
    PGProperty.PREPARED_STATEMENT_CACHE_QUERIES.set(properties, cacheSize);
  }

  /**
   * @return prepared statement cache size (number of megabytes per connection)
   * @see PGProperty#PREPARED_STATEMENT_CACHE_SIZE_MIB
   */
  public int getPreparedStatementCacheSizeMiB() {
    return PGProperty.PREPARED_STATEMENT_CACHE_SIZE_MIB.getIntNoCheck(properties);
  }

  /**
   * @param cacheSize statement cache size (number of megabytes per connection)
   * @see PGProperty#PREPARED_STATEMENT_CACHE_SIZE_MIB
   */
  public void setPreparedStatementCacheSizeMiB(int cacheSize) {
    PGProperty.PREPARED_STATEMENT_CACHE_SIZE_MIB.set(properties, cacheSize);
  }

  /**
   * @return database metadata cache fields size (number of fields cached per connection)
   * @see PGProperty#DATABASE_METADATA_CACHE_FIELDS
   */
  public int getDatabaseMetadataCacheFields() {
    return PGProperty.DATABASE_METADATA_CACHE_FIELDS.getIntNoCheck(properties);
  }

  /**
   * @param cacheSize database metadata cache fields size (number of fields cached per connection)
   * @see PGProperty#DATABASE_METADATA_CACHE_FIELDS
   */
  public void setDatabaseMetadataCacheFields(int cacheSize) {
    PGProperty.DATABASE_METADATA_CACHE_FIELDS.set(properties, cacheSize);
  }

  /**
   * @return database metadata cache fields size (number of megabytes per connection)
   * @see PGProperty#DATABASE_METADATA_CACHE_FIELDS_MIB
   */
  public int getDatabaseMetadataCacheFieldsMiB() {
    return PGProperty.DATABASE_METADATA_CACHE_FIELDS_MIB.getIntNoCheck(properties);
  }

  /**
   * @param cacheSize database metadata cache fields size (number of megabytes per connection)
   * @see PGProperty#DATABASE_METADATA_CACHE_FIELDS_MIB
   */
  public void setDatabaseMetadataCacheFieldsMiB(int cacheSize) {
    PGProperty.DATABASE_METADATA_CACHE_FIELDS_MIB.set(properties, cacheSize);
  }

  /**
   * @param fetchSize default fetch size
   * @see PGProperty#DEFAULT_ROW_FETCH_SIZE
   */
  public void setDefaultRowFetchSize(int fetchSize) {
    PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, fetchSize);
  }

  /**
   * @return default fetch size
   * @see PGProperty#DEFAULT_ROW_FETCH_SIZE
   */
  public int getDefaultRowFetchSize() {
    return PGProperty.DEFAULT_ROW_FETCH_SIZE.getIntNoCheck(properties);
  }

  /**
   * @param unknownLength unknown length
   * @see PGProperty#UNKNOWN_LENGTH
   */
  public void setUnknownLength(int unknownLength) {
    PGProperty.UNKNOWN_LENGTH.set(properties, unknownLength);
  }

  /**
   * @return unknown length
   * @see PGProperty#UNKNOWN_LENGTH
   */
  public int getUnknownLength() {
    return PGProperty.UNKNOWN_LENGTH.getIntNoCheck(properties);
  }

  /**
   * @param seconds socket timeout
   * @see PGProperty#SOCKET_TIMEOUT
   */
  public void setSocketTimeout(int seconds) {
    PGProperty.SOCKET_TIMEOUT.set(properties, seconds);
  }

  /**
   * @return socket timeout
   * @see PGProperty#SOCKET_TIMEOUT
   */
  public int getSocketTimeout() {
    return PGProperty.SOCKET_TIMEOUT.getIntNoCheck(properties);
  }

  /**
   * @param seconds timeout that is used for sending cancel command
   * @see PGProperty#CANCEL_SIGNAL_TIMEOUT
   */
  public void setCancelSignalTimeout(int seconds) {
    PGProperty.CANCEL_SIGNAL_TIMEOUT.set(properties, seconds);
  }

  /**
   * @return timeout that is used for sending cancel command in seconds
   * @see PGProperty#CANCEL_SIGNAL_TIMEOUT
   */
  public int getCancelSignalTimeout() {
    return PGProperty.CANCEL_SIGNAL_TIMEOUT.getIntNoCheck(properties);
  }

  /**
   * @param enabled if SSL is enabled
   * @see PGProperty#SSL
   */
  public void setSsl(boolean enabled) {
    if (enabled) {
      PGProperty.SSL.set(properties, true);
    } else {
      PGProperty.SSL.set(properties, false);
    }
  }

  /**
   * @return true if SSL is enabled
   * @see PGProperty#SSL
   */
  public boolean getSsl() {
    // "true" if "ssl" is set but empty
    return PGProperty.SSL.getBoolean(properties) || "".equals(PGProperty.SSL.getOrDefault(properties));
  }

  /**
   * @param classname SSL factory class name
   * @see PGProperty#SSL_FACTORY
   */
  public void setSslfactory(String classname) {
    PGProperty.SSL_FACTORY.set(properties, classname);
  }

  /**
   * @return SSL factory class name
   * @see PGProperty#SSL_FACTORY
   */
  public @Nullable String getSslfactory() {
    return PGProperty.SSL_FACTORY.getOrDefault(properties);
  }

  /**
   * @return SSL mode
   * @see PGProperty#SSL_MODE
   */
  public @Nullable String getSslMode() {
    return PGProperty.SSL_MODE.getOrDefault(properties);
  }

  /**
   * @param mode SSL mode
   * @see PGProperty#SSL_MODE
   */
  public void setSslMode(@Nullable String mode) {
    PGProperty.SSL_MODE.set(properties, mode);
  }

  /**
   * @return SSL mode
   * @see PGProperty#SSL_FACTORY_ARG
   */
  @SuppressWarnings("deprecation")
  public @Nullable String getSslFactoryArg() {
    return PGProperty.SSL_FACTORY_ARG.getOrDefault(properties);
  }

  /**
   * @param arg argument forwarded to SSL factory
   * @see PGProperty#SSL_FACTORY_ARG
   */
  @SuppressWarnings("deprecation")
  public void setSslFactoryArg(@Nullable String arg) {
    PGProperty.SSL_FACTORY_ARG.set(properties, arg);
  }

  /**
   * @return argument forwarded to SSL factory
   * @see PGProperty#SSL_HOSTNAME_VERIFIER
   */
  public @Nullable String getSslHostnameVerifier() {
    return PGProperty.SSL_HOSTNAME_VERIFIER.getOrDefault(properties);
  }

  /**
   * @param className SSL hostname verifier
   * @see PGProperty#SSL_HOSTNAME_VERIFIER
   */
  public void setSslHostnameVerifier(@Nullable String className) {
    PGProperty.SSL_HOSTNAME_VERIFIER.set(properties, className);
  }

  /**
   * @return className SSL hostname verifier
   * @see PGProperty#SSL_CERT
   */
  public @Nullable String getSslCert() {
    return PGProperty.SSL_CERT.getOrDefault(properties);
  }

  /**
   * @param file SSL certificate
   * @see PGProperty#SSL_CERT
   */
  public void setSslCert(@Nullable String file) {
    PGProperty.SSL_CERT.set(properties, file);
  }

  /**
   * @return SSL certificate
   * @see PGProperty#SSL_KEY
   */
  public @Nullable String getSslKey() {
    return PGProperty.SSL_KEY.getOrDefault(properties);
  }

  /**
   * @param file SSL key
   * @see PGProperty#SSL_KEY
   */
  public void setSslKey(@Nullable String file) {
    PGProperty.SSL_KEY.set(properties, file);
  }

  /**
   * @return SSL root certificate
   * @see PGProperty#SSL_ROOT_CERT
   */
  public @Nullable String getSslRootCert() {
    return PGProperty.SSL_ROOT_CERT.getOrDefault(properties);
  }

  /**
   * @param file SSL root certificate
   * @see PGProperty#SSL_ROOT_CERT
   */
  public void setSslRootCert(@Nullable String file) {
    PGProperty.SSL_ROOT_CERT.set(properties, file);
  }

  /**
   * @param sslNegotiation one of SSLNegotiation.POSTGRES or SSLNegotiation.DIRECT
   * @see PGProperty#SSL_NEGOTIATION
   */
  public void setSslNegotiation(@Nullable String sslNegotiation) {
    PGProperty.SSL_NEGOTIATION.set(properties, sslNegotiation);
  }

  /**
   * @return SSL Negotiation scheme
   * @see PGProperty#SSL_NEGOTIATION
   */
  public String getSslNegotiation() {
    return castNonNull(PGProperty.SSL_NEGOTIATION.getOrDefault(properties));
  }

  /**
   * @return SSL password
   * @see PGProperty#SSL_PASSWORD
   */
  public @Nullable String getSslPassword() {
    return PGProperty.SSL_PASSWORD.getOrDefault(properties);
  }

  /**
   * @param password SSL password
   * @see PGProperty#SSL_PASSWORD
   */
  public void setSslPassword(@Nullable String password) {
    PGProperty.SSL_PASSWORD.set(properties, password);
  }

  /**
   * @return SSL password callback
   * @see PGProperty#SSL_PASSWORD_CALLBACK
   */
  public @Nullable String getSslPasswordCallback() {
    return PGProperty.SSL_PASSWORD_CALLBACK.getOrDefault(properties);
  }

  /**
   * @param className SSL password callback class name
   * @see PGProperty#SSL_PASSWORD_CALLBACK
   */
  public void setSslPasswordCallback(@Nullable String className) {
    PGProperty.SSL_PASSWORD_CALLBACK.set(properties, className);
  }

  /**
   * @param applicationName application name
   * @see PGProperty#APPLICATION_NAME
   */
  public void setApplicationName(@Nullable String applicationName) {
    PGProperty.APPLICATION_NAME.set(properties, applicationName);
  }

  /**
   * @return application name
   * @see PGProperty#APPLICATION_NAME
   */
  public String getApplicationName() {
    return castNonNull(PGProperty.APPLICATION_NAME.getOrDefault(properties));
  }

  /**
   * @param targetServerType target server type
   * @see PGProperty#TARGET_SERVER_TYPE
   */
  public void setTargetServerType(@Nullable String targetServerType) {
    PGProperty.TARGET_SERVER_TYPE.set(properties, targetServerType);
  }

  /**
   * @return target server type
   * @see PGProperty#TARGET_SERVER_TYPE
   */
  public String getTargetServerType() {
    return castNonNull(PGProperty.TARGET_SERVER_TYPE.getOrDefault(properties));
  }

  /**
   * @param loadBalanceHosts load balance hosts
   * @see PGProperty#LOAD_BALANCE_HOSTS
   */
  public void setLoadBalanceHosts(boolean loadBalanceHosts) {
    PGProperty.LOAD_BALANCE_HOSTS.set(properties, loadBalanceHosts);
  }

  /**
   * @return load balance hosts
   * @see PGProperty#LOAD_BALANCE_HOSTS
   */
  public boolean getLoadBalanceHosts() {
    return PGProperty.LOAD_BALANCE_HOSTS.isPresent(properties);
  }

  /**
   * @param hostRecheckSeconds host recheck seconds
   * @see PGProperty#HOST_RECHECK_SECONDS
   */
  public void setHostRecheckSeconds(int hostRecheckSeconds) {
    PGProperty.HOST_RECHECK_SECONDS.set(properties, hostRecheckSeconds);
  }

  /**
   * @return host recheck seconds
   * @see PGProperty#HOST_RECHECK_SECONDS
   */
  public int getHostRecheckSeconds() {
    return PGProperty.HOST_RECHECK_SECONDS.getIntNoCheck(properties);
  }

  /**
   * @param enabled if TCP keep alive should be enabled
   * @see PGProperty#TCP_KEEP_ALIVE
   */
  public void setTcpKeepAlive(boolean enabled) {
    PGProperty.TCP_KEEP_ALIVE.set(properties, enabled);
  }

  /**
   * @return true if TCP keep alive is enabled
   * @see PGProperty#TCP_KEEP_ALIVE
   */
  public boolean getTcpKeepAlive() {
    return PGProperty.TCP_KEEP_ALIVE.getBoolean(properties);
  }

  /**
   * @param enabled if TCP no delay should be enabled
   * @see PGProperty#TCP_NO_DELAY
   */
  public void setTcpNoDelay(boolean enabled) {
    PGProperty.TCP_NO_DELAY.set(properties, enabled);
  }

  /**
   * @return true if TCP no delay is enabled
   * @see PGProperty#TCP_NO_DELAY
   */
  public boolean getTcpNoDelay() {
    return PGProperty.TCP_NO_DELAY.getBoolean(properties);
  }

  /**
   * @param enabled if binary transfer should be enabled
   * @see PGProperty#BINARY_TRANSFER
   */
  public void setBinaryTransfer(boolean enabled) {
    PGProperty.BINARY_TRANSFER.set(properties, enabled);
  }

  /**
   * @return true if binary transfer is enabled
   * @see PGProperty#BINARY_TRANSFER
   */
  public boolean getBinaryTransfer() {
    return PGProperty.BINARY_TRANSFER.getBoolean(properties);
  }

  /**
   * @param oidList list of OIDs that are allowed to use binary transfer
   * @see PGProperty#BINARY_TRANSFER_ENABLE
   */
  public void setBinaryTransferEnable(@Nullable String oidList) {
    PGProperty.BINARY_TRANSFER_ENABLE.set(properties, oidList);
  }

  /**
   * @return list of OIDs that are allowed to use binary transfer
   * @see PGProperty#BINARY_TRANSFER_ENABLE
   */
  public String getBinaryTransferEnable() {
    return castNonNull(PGProperty.BINARY_TRANSFER_ENABLE.getOrDefault(properties));
  }

  /**
   * @param oidList list of OIDs that are not allowed to use binary transfer
   * @see PGProperty#BINARY_TRANSFER_DISABLE
   */
  public void setBinaryTransferDisable(@Nullable String oidList) {
    PGProperty.BINARY_TRANSFER_DISABLE.set(properties, oidList);
  }

  /**
   * @return list of OIDs that are not allowed to use binary transfer
   * @see PGProperty#BINARY_TRANSFER_DISABLE
   */
  public String getBinaryTransferDisable() {
    return castNonNull(PGProperty.BINARY_TRANSFER_DISABLE.getOrDefault(properties));
  }

  /**
   * @return string type
   * @see PGProperty#STRING_TYPE
   */
  public @Nullable String getStringType() {
    return PGProperty.STRING_TYPE.getOrDefault(properties);
  }

  /**
   * @param stringType string type
   * @see PGProperty#STRING_TYPE
   */
  public void setStringType(@Nullable String stringType) {
    PGProperty.STRING_TYPE.set(properties, stringType);
  }

  /**
   * @return true if column sanitizer is disabled
   * @see PGProperty#DISABLE_COLUMN_SANITISER
   */
  public boolean isColumnSanitiserDisabled() {
    return PGProperty.DISABLE_COLUMN_SANITISER.getBoolean(properties);
  }

  /**
   * @return true if column sanitizer is disabled
   * @see PGProperty#DISABLE_COLUMN_SANITISER
   */
  public boolean getDisableColumnSanitiser() {
    return PGProperty.DISABLE_COLUMN_SANITISER.getBoolean(properties);
  }

  /**
   * @param disableColumnSanitiser if column sanitizer should be disabled
   * @see PGProperty#DISABLE_COLUMN_SANITISER
   */
  public void setDisableColumnSanitiser(boolean disableColumnSanitiser) {
    PGProperty.DISABLE_COLUMN_SANITISER.set(properties, disableColumnSanitiser);
  }

  /**
   * @return current schema
   * @see PGProperty#CURRENT_SCHEMA
   */
  public @Nullable String getCurrentSchema() {
    return PGProperty.CURRENT_SCHEMA.getOrDefault(properties);
  }

  /**
   * @param currentSchema current schema
   * @see PGProperty#CURRENT_SCHEMA
   */
  public void setCurrentSchema(@Nullable String currentSchema) {
    PGProperty.CURRENT_SCHEMA.set(properties, currentSchema);
  }

  /**
   * @return true if connection is readonly
   * @see PGProperty#READ_ONLY
   */
  public boolean getReadOnly() {
    return PGProperty.READ_ONLY.getBoolean(properties);
  }

  /**
   * @param readOnly if connection should be readonly
   * @see PGProperty#READ_ONLY
   */
  public void setReadOnly(boolean readOnly) {
    PGProperty.READ_ONLY.set(properties, readOnly);
  }

  /**
   * @return The behavior when set read only
   * @see PGProperty#READ_ONLY_MODE
   */
  public String getReadOnlyMode() {
    return castNonNull(PGProperty.READ_ONLY_MODE.getOrDefault(properties));
  }

  /**
   * @param mode the behavior when set read only
   * @see PGProperty#READ_ONLY_MODE
   */
  public void setReadOnlyMode(@Nullable String mode) {
    PGProperty.READ_ONLY_MODE.set(properties, mode);
  }

  /**
   * @return true if driver should log unclosed connections
   * @see PGProperty#LOG_UNCLOSED_CONNECTIONS
   */
  public boolean getLogUnclosedConnections() {
    return PGProperty.LOG_UNCLOSED_CONNECTIONS.getBoolean(properties);
  }

  /**
   * @param enabled true if driver should log unclosed connections
   * @see PGProperty#LOG_UNCLOSED_CONNECTIONS
   */
  public void setLogUnclosedConnections(boolean enabled) {
    PGProperty.LOG_UNCLOSED_CONNECTIONS.set(properties, enabled);
  }

  /**
   * @return true if driver should log include detail in server error messages
   * @see PGProperty#LOG_SERVER_ERROR_DETAIL
   */
  public boolean getLogServerErrorDetail() {
    return PGProperty.LOG_SERVER_ERROR_DETAIL.getBoolean(properties);
  }

  /**
   * @param enabled true if driver should include detail in server error messages
   * @see PGProperty#LOG_SERVER_ERROR_DETAIL
   */
  public void setLogServerErrorDetail(boolean enabled) {
    PGProperty.LOG_SERVER_ERROR_DETAIL.set(properties, enabled);
  }

  /**
   * @return assumed minimal server version
   * @see PGProperty#ASSUME_MIN_SERVER_VERSION
   */
  public @Nullable String getAssumeMinServerVersion() {
    return PGProperty.ASSUME_MIN_SERVER_VERSION.getOrDefault(properties);
  }

  /**
   * @param minVersion assumed minimal server version
   * @see PGProperty#ASSUME_MIN_SERVER_VERSION
   */
  public void setAssumeMinServerVersion(@Nullable String minVersion) {
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(properties, minVersion);
  }

  /**
   * This is important in pool-by-transaction scenarios in order to make sure that all the statements
   * reaches the same connection that is being initialized. If set then we will group the startup
   * parameters in a transaction
   * @return whether to group startup parameters or not
   * @see PGProperty#GROUP_STARTUP_PARAMETERS
   */
  public boolean getGroupStartupParameters() {
    return PGProperty.GROUP_STARTUP_PARAMETERS.getBoolean(properties);
  }

  /**
   *
   * @param groupStartupParameters whether to group startup Parameters in a transaction or not
   * @see PGProperty#GROUP_STARTUP_PARAMETERS
   */
  public void setGroupStartupParameters(boolean groupStartupParameters) {
    PGProperty.GROUP_STARTUP_PARAMETERS.set(properties, groupStartupParameters);
  }

  /**
   * @return JAAS application name
   * @see PGProperty#JAAS_APPLICATION_NAME
   */
  public @Nullable String getJaasApplicationName() {
    return PGProperty.JAAS_APPLICATION_NAME.getOrDefault(properties);
  }

  /**
   * @param name JAAS application name
   * @see PGProperty#JAAS_APPLICATION_NAME
   */
  public void setJaasApplicationName(@Nullable String name) {
    PGProperty.JAAS_APPLICATION_NAME.set(properties, name);
  }

  /**
   * @return true if perform JAAS login before GSS authentication
   * @see PGProperty#JAAS_LOGIN
   */
  public boolean getJaasLogin() {
    return PGProperty.JAAS_LOGIN.getBoolean(properties);
  }

  /**
   * @param doLogin true if perform JAAS login before GSS authentication
   * @see PGProperty#JAAS_LOGIN
   */
  public void setJaasLogin(boolean doLogin) {
    PGProperty.JAAS_LOGIN.set(properties, doLogin);
  }

  /**
   * @return Kerberos server name
   * @see PGProperty#KERBEROS_SERVER_NAME
   */
  public @Nullable String getKerberosServerName() {
    return PGProperty.KERBEROS_SERVER_NAME.getOrDefault(properties);
  }

  /**
   * @param serverName Kerberos server name
   * @see PGProperty#KERBEROS_SERVER_NAME
   */
  public void setKerberosServerName(@Nullable String serverName) {
    PGProperty.KERBEROS_SERVER_NAME.set(properties, serverName);
  }

  /**
   * @return true if use SPNEGO
   * @see PGProperty#USE_SPNEGO
   */
  public boolean getUseSpNego() {
    return PGProperty.USE_SPNEGO.getBoolean(properties);
  }

  /**
   * @param use true if use SPNEGO
   * @see PGProperty#USE_SPNEGO
   */
  public void setUseSpNego(boolean use) {
    PGProperty.USE_SPNEGO.set(properties, use);
  }

  /**
   * @return GSS mode: auto, sspi, or gssapi
   * @see PGProperty#GSS_LIB
   */
  public @Nullable String getGssLib() {
    return PGProperty.GSS_LIB.getOrDefault(properties);
  }

  /**
   * @param lib GSS mode: auto, sspi, or gssapi
   * @see PGProperty#GSS_LIB
   */
  public void setGssLib(@Nullable String lib) {
    PGProperty.GSS_LIB.set(properties, lib);
  }

  /**
   *
   * @return GSS encryption mode: disable, prefer or require
   */
  public String getGssEncMode() {
    return castNonNull(PGProperty.GSS_ENC_MODE.getOrDefault(properties));
  }

  /**
   *
   * @param mode encryption mode: disable, prefer or require
   */
  public void setGssEncMode(@Nullable String mode) {
    PGProperty.GSS_ENC_MODE.set(properties, mode);
  }

  /**
   * @return SSPI service class
   * @see PGProperty#SSPI_SERVICE_CLASS
   */
  public @Nullable String getSspiServiceClass() {
    return PGProperty.SSPI_SERVICE_CLASS.getOrDefault(properties);
  }

  /**
   * @param serviceClass SSPI service class
   * @see PGProperty#SSPI_SERVICE_CLASS
   */
  public void setSspiServiceClass(@Nullable String serviceClass) {
    PGProperty.SSPI_SERVICE_CLASS.set(properties, serviceClass);
  }

  /**
   * @return if connection allows encoding changes
   * @see PGProperty#ALLOW_ENCODING_CHANGES
   */
  public boolean getAllowEncodingChanges() {
    return PGProperty.ALLOW_ENCODING_CHANGES.getBoolean(properties);
  }

  /**
   * @param allow if connection allows encoding changes
   * @see PGProperty#ALLOW_ENCODING_CHANGES
   */
  public void setAllowEncodingChanges(boolean allow) {
    PGProperty.ALLOW_ENCODING_CHANGES.set(properties, allow);
  }

  /**
   * @return socket factory class name
   * @see PGProperty#SOCKET_FACTORY
   */
  public @Nullable String getSocketFactory() {
    return PGProperty.SOCKET_FACTORY.getOrDefault(properties);
  }

  /**
   * @param socketFactoryClassName socket factory class name
   * @see PGProperty#SOCKET_FACTORY
   */
  public void setSocketFactory(@Nullable String socketFactoryClassName) {
    PGProperty.SOCKET_FACTORY.set(properties, socketFactoryClassName);
  }

  /**
   * @return socket factory argument
   * @see PGProperty#SOCKET_FACTORY_ARG
   */
  @SuppressWarnings("deprecation")
  public @Nullable String getSocketFactoryArg() {
    return PGProperty.SOCKET_FACTORY_ARG.getOrDefault(properties);
  }

  /**
   * @param socketFactoryArg socket factory argument
   * @see PGProperty#SOCKET_FACTORY_ARG
   */
  @SuppressWarnings("deprecation")
  public void setSocketFactoryArg(@Nullable String socketFactoryArg) {
    PGProperty.SOCKET_FACTORY_ARG.set(properties, socketFactoryArg);
  }

  /**
   * @param replication set to 'database' for logical replication or 'true' for physical replication
   * @see PGProperty#REPLICATION
   */
  public void setReplication(@Nullable String replication) {
    PGProperty.REPLICATION.set(properties, replication);
  }

  /**
   * @return 'select', "callIfNoReturn', or 'call'
   * @see PGProperty#ESCAPE_SYNTAX_CALL_MODE
   */
  public String getEscapeSyntaxCallMode() {
    return castNonNull(PGProperty.ESCAPE_SYNTAX_CALL_MODE.getOrDefault(properties));
  }

  /**
   * @param callMode the call mode to use for JDBC escape call syntax
   * @see PGProperty#ESCAPE_SYNTAX_CALL_MODE
   */
  public void setEscapeSyntaxCallMode(@Nullable String callMode) {
    PGProperty.ESCAPE_SYNTAX_CALL_MODE.set(properties, callMode);
  }

  /**
   * @return null, 'database', or 'true
   * @see PGProperty#REPLICATION
   */
  public @Nullable String getReplication() {
    return PGProperty.REPLICATION.getOrDefault(properties);
  }

  /**
   * @return the localSocketAddress
   * @see PGProperty#LOCAL_SOCKET_ADDRESS
   */
  public @Nullable String getLocalSocketAddress() {
    return PGProperty.LOCAL_SOCKET_ADDRESS.getOrDefault(properties);
  }

  /**
   * @param localSocketAddress local address to bind client side to
   * @see PGProperty#LOCAL_SOCKET_ADDRESS
   */
  public void setLocalSocketAddress(String localSocketAddress) {
    PGProperty.LOCAL_SOCKET_ADDRESS.set(properties, localSocketAddress);
  }

  /**
   * This property is no longer used by the driver and will be ignored.
   * @return loggerLevel in properties
   * @deprecated Configure via java.util.logging
   */
  @Deprecated
  public @Nullable String getLoggerLevel() {
    return PGProperty.LOGGER_LEVEL.getOrDefault(properties);
  }

  /**
   * This property is no longer used by the driver and will be ignored.
   * @param loggerLevel loggerLevel to set, will be ignored
   * @deprecated Configure via java.util.logging
   */
  @Deprecated
  public void setLoggerLevel(@Nullable String loggerLevel) {
    PGProperty.LOGGER_LEVEL.set(properties, loggerLevel);
  }

  /**
   * This property is no longer used by the driver and will be ignored.
   * @return loggerFile in properties
   * @deprecated Configure via java.util.logging
   */
  @Deprecated
  public @Nullable String getLoggerFile() {
    ExpressionProperties exprProps = new ExpressionProperties(properties, System.getProperties());
    return PGProperty.LOGGER_FILE.getOrDefault(exprProps);
  }

  /**
   * This property is no longer used by the driver and will be ignored.
   * @param loggerFile will be ignored
   * @deprecated Configure via java.util.logging
   */
  @Deprecated
  public void setLoggerFile(@Nullable String loggerFile) {
    PGProperty.LOGGER_FILE.set(properties, loggerFile);
  }

  /**
   * @return Channel binding option
   * @see PGProperty#CHANNEL_BINDING
   */
  public @Nullable String getChannelBinding() {
    return PGProperty.CHANNEL_BINDING.getOrDefault(properties);
  }

  /**
   * @param channelBinding Channel binding option
   * @see PGProperty#CHANNEL_BINDING
   */
  public void setChannelBinding(@Nullable String channelBinding) {
    PGProperty.CHANNEL_BINDING.set(properties, channelBinding);
  }

  /**
   * Generates a {@link DriverManager} URL from the other properties supplied.
   *
   * @return {@link DriverManager} URL from the other properties supplied
   */
  public String getUrl() {
    StringBuilder url = new StringBuilder(100);
    url.append("jdbc:postgresql://");
    for (int i = 0; i < serverNames.length; i++) {
      if (i > 0) {
        url.append(",");
      }
      url.append(serverNames[i]);
      if (portNumbers != null) {
        if (serverNames.length != portNumbers.length) {
          throw new IllegalArgumentException(
              String.format("Invalid argument: number of port %s entries must equal number of serverNames %s",
                  Arrays.toString(portNumbers), Arrays.toString(serverNames)));
        }
        if (portNumbers.length >= i && portNumbers[i] != 0) {
          url.append(":").append(portNumbers[i]);
        }

      }
    }
    url.append("/");
    if (databaseName != null) {
      url.append(URLCoder.encode(databaseName));
    }

    StringBuilder query = new StringBuilder(100);
    for (PGProperty property : PGProperty.values()) {
      if (property.isPresent(properties)) {
        if (query.length() != 0) {
          query.append("&");
        }
        query.append(property.getName());
        query.append("=");
        String value = castNonNull(property.getOrDefault(properties));
        query.append(URLCoder.encode(value));
      }
    }

    if (query.length() > 0) {
      url.append("?");
      url.append(query);
    }

    return url.toString();
  }

  /**
   * Generates a {@link DriverManager} URL from the other properties supplied.
   *
   * @return {@link DriverManager} URL from the other properties supplied
   */
  public String getURL() {
    return getUrl();
  }

  /**
   * Sets properties from a {@link DriverManager} URL.
   *
   * @param url properties to set
   */
  public void setUrl(String url) {

    Properties p = Driver.parseURL(url, null);

    if (p == null) {
      throw new IllegalArgumentException("URL invalid " + url);
    }
    for (PGProperty property : PGProperty.values()) {
      if (!this.properties.containsKey(property.getName())) {
        setProperty(property, property.getOrDefault(p));
      }
    }
  }

  /**
   * Sets properties from a {@link DriverManager} URL.
   * Added to follow convention used in other DBMS.
   *
   * @param url properties to set
   */
  public void setURL(String url) {
    setUrl(url);
  }

  /**
   *
   * @return the class name to use for the Authentication Plugin.
   *         This can be null in which case the default password authentication plugin will be used
   */
  public @Nullable String getAuthenticationPluginClassName() {
    return PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME.getOrDefault(properties);
  }

  /**
   *
   * @param className name of a class which implements {@link org.postgresql.plugin.AuthenticationPlugin}
   *                  This class will be used to get the encoded bytes to be sent to the server as the
   *                  password to authenticate the user.
   *
   */
  public void setAuthenticationPluginClassName(String className) {
    PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME.set(properties, className);
  }

  public @Nullable String getProperty(String name) throws SQLException {
    PGProperty pgProperty = PGProperty.forName(name);
    if (pgProperty != null) {
      return getProperty(pgProperty);
    } else {
      throw new PSQLException(GT.tr("Unsupported property name: {0}", name),
        PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  public void setProperty(String name, @Nullable String value) throws SQLException {
    PGProperty pgProperty = PGProperty.forName(name);
    if (pgProperty != null) {
      setProperty(pgProperty, value);
    } else {
      throw new PSQLException(GT.tr("Unsupported property name: {0}", name),
        PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  public @Nullable String getProperty(PGProperty property) {
    return property.getOrDefault(properties);
  }

  public void setProperty(PGProperty property, @Nullable String value) {
    if (value == null) {
      // TODO: this is not consistent with PGProperty.PROPERTY.set(prop, null)
      // PGProperty removes an entry for put(null) call, however here we just ignore null
      return;
    }
    switch (property) {
      case PG_HOST:
        setServerNames(value.split(","));
        break;
      case PG_PORT:
        String[] ps = value.split(",");
        int[] ports = new int[ps.length];
        for (int i = 0; i < ps.length; i++) {
          try {
            ports[i] = Integer.parseInt(ps[i]);
          } catch (NumberFormatException e) {
            ports[i] = 0;
          }
        }
        setPortNumbers(ports);
        break;
      case PG_DBNAME:
        setDatabaseName(value);
        break;
      case USER:
        setUser(value);
        break;
      case PASSWORD:
        setPassword(value);
        break;
      default:
        properties.setProperty(property.getName(), value);
    }
  }

  /**
   * Generates a reference using the appropriate object factory.
   *
   * @return reference using the appropriate object factory
   */
  protected Reference createReference() {
    return new Reference(getClass().getName(), PGObjectFactory.class.getName(), null);
  }

  @Override
  public Reference getReference() throws NamingException {
    Reference ref = createReference();
    StringBuilder serverString = new StringBuilder();
    for (int i = 0; i < serverNames.length; i++) {
      if (i > 0) {
        serverString.append(",");
      }
      String serverName = serverNames[i];
      serverString.append(serverName);
    }
    ref.add(new StringRefAddr("serverName", serverString.toString()));

    StringBuilder portString = new StringBuilder();
    for (int i = 0; i < portNumbers.length; i++) {
      if (i > 0) {
        portString.append(",");
      }
      int p = portNumbers[i];
      portString.append(Integer.toString(p));
    }
    ref.add(new StringRefAddr("portNumber", portString.toString()));
    ref.add(new StringRefAddr("databaseName", databaseName));
    if (user != null) {
      ref.add(new StringRefAddr("user", user));
    }
    if (password != null) {
      ref.add(new StringRefAddr("password", password));
    }

    for (PGProperty property : PGProperty.values()) {
      if (property.isPresent(properties)) {
        String value = castNonNull(property.getOrDefault(properties));
        ref.add(new StringRefAddr(property.getName(), value));
      }
    }

    return ref;
  }

  public void setFromReference(Reference ref) {
    databaseName = getReferenceProperty(ref, "databaseName");
    String portNumberString = getReferenceProperty(ref, "portNumber");
    if (portNumberString != null) {
      String[] ps = portNumberString.split(",");
      int[] ports = new int[ps.length];
      for (int i = 0; i < ps.length; i++) {
        try {
          ports[i] = Integer.parseInt(ps[i]);
        } catch (NumberFormatException e) {
          ports[i] = 0;
        }
      }
      setPortNumbers(ports);
    } else {
      setPortNumbers(null);
    }
    String serverName = castNonNull(getReferenceProperty(ref, "serverName"));
    setServerNames(serverName.split(","));

    for (PGProperty property : PGProperty.values()) {
      setProperty(property, getReferenceProperty(ref, property.getName()));
    }
  }

  private static @Nullable String getReferenceProperty(Reference ref, String propertyName) {
    RefAddr addr = ref.get(propertyName);
    if (addr == null) {
      return null;
    }
    return (String) addr.getContent();
  }

  protected void writeBaseObject(ObjectOutputStream out) throws IOException {
    out.writeObject(serverNames);
    out.writeObject(databaseName);
    out.writeObject(user);
    out.writeObject(password);
    out.writeObject(portNumbers);

    out.writeObject(properties);
  }

  protected void readBaseObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    serverNames = (String[]) in.readObject();
    databaseName = (String) in.readObject();
    user = (String) in.readObject();
    password = (String) in.readObject();
    portNumbers = (int[]) in.readObject();

    properties = (Properties) in.readObject();
  }

  public void initializeFrom(BaseDataSource source) throws IOException, ClassNotFoundException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream oos = new ObjectOutputStream(baos);
    source.writeBaseObject(oos);
    oos.close();
    ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
    ObjectInputStream ois = new ObjectInputStream(bais);
    readBaseObject(ois);
  }

  /**
   * @return preferred query execution mode
   * @see PGProperty#PREFER_QUERY_MODE
   */
  public PreferQueryMode getPreferQueryMode() {
    return PreferQueryMode.of(castNonNull(PGProperty.PREFER_QUERY_MODE.getOrDefault(properties)));
  }

  /**
   * @param preferQueryMode extended, simple, extendedForPrepared, or extendedCacheEverything
   * @see PGProperty#PREFER_QUERY_MODE
   */
  public void setPreferQueryMode(PreferQueryMode preferQueryMode) {
    PGProperty.PREFER_QUERY_MODE.set(properties, preferQueryMode.value());
  }

  /**
   * @return connection configuration regarding automatic per-query savepoints
   * @see PGProperty#AUTOSAVE
   */
  public AutoSave getAutosave() {
    return AutoSave.of(castNonNull(PGProperty.AUTOSAVE.getOrDefault(properties)));
  }

  /**
   * @param autoSave connection configuration regarding automatic per-query savepoints
   * @see PGProperty#AUTOSAVE
   */
  public void setAutosave(AutoSave autoSave) {
    PGProperty.AUTOSAVE.set(properties, autoSave.value());
  }

  /**
   * see PGProperty#CLEANUP_SAVEPOINTS
   *
   * @return boolean indicating property set
   */
  public boolean getCleanupSavepoints() {
    return PGProperty.CLEANUP_SAVEPOINTS.getBoolean(properties);
  }

  /**
   * see PGProperty#CLEANUP_SAVEPOINTS
   *
   * @param cleanupSavepoints will cleanup savepoints after a successful transaction
   */
  public void setCleanupSavepoints(boolean cleanupSavepoints) {
    PGProperty.CLEANUP_SAVEPOINTS.set(properties, cleanupSavepoints);
  }

  /**
   * @return boolean indicating property is enabled or not.
   * @see PGProperty#REWRITE_BATCHED_INSERTS
   */
  public boolean getReWriteBatchedInserts() {
    return PGProperty.REWRITE_BATCHED_INSERTS.getBoolean(properties);
  }

  /**
   * @param reWrite boolean value to set the property in the properties collection
   * @see PGProperty#REWRITE_BATCHED_INSERTS
   */
  public void setReWriteBatchedInserts(boolean reWrite) {
    PGProperty.REWRITE_BATCHED_INSERTS.set(properties, reWrite);
  }

  /**
   * @return boolean indicating property is enabled or not.
   * @see PGProperty#HIDE_UNPRIVILEGED_OBJECTS
   */
  public boolean getHideUnprivilegedObjects() {
    return PGProperty.HIDE_UNPRIVILEGED_OBJECTS.getBoolean(properties);
  }

  /**
   * @param hideUnprivileged boolean value to set the property in the properties collection
   * @see PGProperty#HIDE_UNPRIVILEGED_OBJECTS
   */
  public void setHideUnprivilegedObjects(boolean hideUnprivileged) {
    PGProperty.HIDE_UNPRIVILEGED_OBJECTS.set(properties, hideUnprivileged);
  }

  public @Nullable String getMaxResultBuffer() {
    return PGProperty.MAX_RESULT_BUFFER.getOrDefault(properties);
  }

  public void setMaxResultBuffer(@Nullable String maxResultBuffer) {
    PGProperty.MAX_RESULT_BUFFER.set(properties, maxResultBuffer);
  }

  public boolean getAdaptiveFetch() {
    return PGProperty.ADAPTIVE_FETCH.getBoolean(properties);
  }

  public void setAdaptiveFetch(boolean adaptiveFetch) {
    PGProperty.ADAPTIVE_FETCH.set(properties, adaptiveFetch);
  }

  public int getAdaptiveFetchMaximum() {
    return PGProperty.ADAPTIVE_FETCH_MAXIMUM.getIntNoCheck(properties);
  }

  public void setAdaptiveFetchMaximum(int adaptiveFetchMaximum) {
    PGProperty.ADAPTIVE_FETCH_MAXIMUM.set(properties, adaptiveFetchMaximum);
  }

  public int getAdaptiveFetchMinimum() {
    return PGProperty.ADAPTIVE_FETCH_MINIMUM.getIntNoCheck(properties);
  }

  public void setAdaptiveFetchMinimum(int adaptiveFetchMinimum) {
    PGProperty.ADAPTIVE_FETCH_MINIMUM.set(properties, adaptiveFetchMinimum);
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger("org.postgresql");
  }

  public String getXmlFactoryFactory() {
    return castNonNull(PGProperty.XML_FACTORY_FACTORY.getOrDefault(properties));
  }

  public void setXmlFactoryFactory(@Nullable String xmlFactoryFactory) {
    PGProperty.XML_FACTORY_FACTORY.set(properties, xmlFactoryFactory);
  }

  /*
   * Alias methods below, these are to help with ease-of-use with other database tools / frameworks
   * which expect normal java bean getters / setters to exist for the property names.
   */

  public boolean isSsl() {
    return getSsl();
  }

  public @Nullable String getSslfactoryarg() {
    return getSslFactoryArg();
  }

  public void setSslfactoryarg(final @Nullable String arg) {
    setSslFactoryArg(arg);
  }

  public @Nullable String getSslcert() {
    return getSslCert();
  }

  public void setSslcert(final @Nullable String file) {
    setSslCert(file);
  }

  public @Nullable String getSslmode() {
    return getSslMode();
  }

  public void setSslmode(final @Nullable String mode) {
    setSslMode(mode);
  }

  public @Nullable String getSslhostnameverifier() {
    return getSslHostnameVerifier();
  }

  public void setSslhostnameverifier(final @Nullable String className) {
    setSslHostnameVerifier(className);
  }

  public @Nullable String getSslkey() {
    return getSslKey();
  }

  public void setSslkey(final @Nullable String file) {
    setSslKey(file);
  }

  public @Nullable String getSslrootcert() {
    return getSslRootCert();
  }

  public void setSslrootcert(final @Nullable String file) {
    setSslRootCert(file);
  }

  public @Nullable String getSslpasswordcallback() {
    return getSslPasswordCallback();
  }

  public void setSslpasswordcallback(final @Nullable String className) {
    setSslPasswordCallback(className);
  }

  public @Nullable String getSslpassword() {
    return getSslPassword();
  }

  public void setSslpassword(final String sslpassword) {
    setSslPassword(sslpassword);
  }

  public int getRecvBufferSize() {
    return getReceiveBufferSize();
  }

  public void setRecvBufferSize(final int nbytes) {
    setReceiveBufferSize(nbytes);
  }

  public boolean isAllowEncodingChanges() {
    return getAllowEncodingChanges();
  }

  public boolean isLogUnclosedConnections() {
    return getLogUnclosedConnections();
  }

  public boolean isTcpKeepAlive() {
    return getTcpKeepAlive();
  }

  public boolean isReadOnly() {
    return getReadOnly();
  }

  public boolean isDisableColumnSanitiser() {
    return getDisableColumnSanitiser();
  }

  public boolean isLoadBalanceHosts() {
    return getLoadBalanceHosts();
  }

  public boolean isCleanupSavePoints() {
    return getCleanupSavepoints();
  }

  public void setCleanupSavePoints(final boolean cleanupSavepoints) {
    setCleanupSavepoints(cleanupSavepoints);
  }

  public boolean isReWriteBatchedInserts() {
    return getReWriteBatchedInserts();
  }
}
