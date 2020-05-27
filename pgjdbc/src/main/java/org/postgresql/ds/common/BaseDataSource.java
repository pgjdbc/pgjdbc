/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ds.common;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.util.ExpressionProperties;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.URLCoder;

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
  private String[] serverNames = new String[] {"localhost"};
  private String databaseName = "";
  private String user;
  private String password;
  private int[] portNumbers = new int[] {0};

  // Map for all other properties
  private Properties properties = new Properties();

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
  public Connection getConnection(String user, String password) throws SQLException {
    try {
      Connection con = DriverManager.getConnection(getUrl(), user, password);
      if (LOGGER.isLoggable(Level.FINE)) {
        LOGGER.log(Level.FINE, "Created a {0} for {1} at {2}",
            new Object[] {getDescription(), user, getUrl()});
      }
      return con;
    } catch (SQLException e) {
      LOGGER.log(Level.FINE, "Failed to create a {0} for {1} at {2}: {3}",
          new Object[] {getDescription(), user, getUrl(), e});
      throw e;
    }
  }

  /**
   * This implementation don't use a LogWriter.
   */
  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  /**
   * This implementation don't use a LogWriter.
   *
   * @param printWriter Not used
   */
  @Override
  public void setLogWriter(PrintWriter printWriter) {
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
    this.setServerNames(new String[] { serverName });
  }

  /**
   * Sets the name of the host(s) the PostgreSQL database is running on. If this is changed, it will
   * only affect future calls to getConnection. The default value is {@code localhost}.
   *
   * @param serverNames name of the host(s) the PostgreSQL database is running on
   */
  public void setServerNames(String[] serverNames) {
    if (serverNames == null || serverNames.length == 0) {
      this.serverNames = new String[] {"localhost"};
    } else {
      serverNames = Arrays.copyOf(serverNames, serverNames.length);
      for (int i = 0; i < serverNames.length; i++) {
        if (serverNames[i] == null || serverNames[i].equals("")) {
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
  public String getDatabaseName() {
    return databaseName;
  }

  /**
   * Sets the name of the PostgreSQL database, running on the server identified by the serverName
   * property. If this is changed, it will only affect future calls to getConnection.
   *
   * @param databaseName name of the PostgreSQL database
   */
  public void setDatabaseName(String databaseName) {
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
  public String getUser() {
    return user;
  }

  /**
   * Sets the user to connect as by default. If this is not specified, you must use the
   * getConnection method which takes a user and password as parameters. If this is changed, it will
   * only affect future calls to getConnection.
   *
   * @param user user to connect as by default
   */
  public void setUser(String user) {
    this.user = user;
  }

  /**
   * Gets the password to connect with by default. If this is not specified but a password is needed
   * to log in, you must use the getConnection method which takes a user and password as parameters.
   *
   * @return password to connect with by default
   */
  public String getPassword() {
    return password;
  }

  /**
   * Sets the password to connect with by default. If this is not specified but a password is needed
   * to log in, you must use the getConnection method which takes a user and password as parameters.
   * If this is changed, it will only affect future calls to getConnection.
   *
   * @param password password to connect with by default
   */
  public void setPassword(String password) {
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
    setPortNumbers(new int[] { portNumber });
  }

  /**
   * Sets the port(s) which the PostgreSQL server is listening on for TCP/IP connections. Be sure the
   * -i flag is passed to postmaster when PostgreSQL is started. If this is not set, or set to 0,
   * the default port will be used.
   *
   * @param portNumbers port(s) which the PostgreSQL server is listening on for TCP/IP
   */
  public void setPortNumbers(int[] portNumbers) {
    if (portNumbers == null || portNumbers.length == 0) {
      portNumbers = new int[] { 0 };
    }
    this.portNumbers = Arrays.copyOf(portNumbers, portNumbers.length);
  }

  /**
   * @return command line options for this connection
   */
  public String getOptions() {
    return PGProperty.OPTIONS.get(properties);
  }

  /**
   * Set command line options for this connection
   *
   * @param options string to set options to
   */
  public void setOptions(String options) {
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
    return PGProperty.SSL.getBoolean(properties) || "".equals(PGProperty.SSL.get(properties));
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
  public String getSslfactory() {
    return PGProperty.SSL_FACTORY.get(properties);
  }

  /**
   * @return SSL mode
   * @see PGProperty#SSL_MODE
   */
  public String getSslMode() {
    return PGProperty.SSL_MODE.get(properties);
  }

  /**
   * @param mode SSL mode
   * @see PGProperty#SSL_MODE
   */
  public void setSslMode(String mode) {
    PGProperty.SSL_MODE.set(properties, mode);
  }

  /**
   * @return SSL mode
   * @see PGProperty#SSL_FACTORY_ARG
   */
  public String getSslFactoryArg() {
    return PGProperty.SSL_FACTORY_ARG.get(properties);
  }

  /**
   * @param arg argument forwarded to SSL factory
   * @see PGProperty#SSL_FACTORY_ARG
   */
  public void setSslFactoryArg(String arg) {
    PGProperty.SSL_FACTORY_ARG.set(properties, arg);
  }

  /**
   * @return argument forwarded to SSL factory
   * @see PGProperty#SSL_HOSTNAME_VERIFIER
   */
  public String getSslHostnameVerifier() {
    return PGProperty.SSL_HOSTNAME_VERIFIER.get(properties);
  }

  /**
   * @param className SSL hostname verifier
   * @see PGProperty#SSL_HOSTNAME_VERIFIER
   */
  public void setSslHostnameVerifier(String className) {
    PGProperty.SSL_HOSTNAME_VERIFIER.set(properties, className);
  }

  /**
   * @return className SSL hostname verifier
   * @see PGProperty#SSL_CERT
   */
  public String getSslCert() {
    return PGProperty.SSL_CERT.get(properties);
  }

  /**
   * @param file SSL certificate
   * @see PGProperty#SSL_CERT
   */
  public void setSslCert(String file) {
    PGProperty.SSL_CERT.set(properties, file);
  }

  /**
   * @return SSL certificate
   * @see PGProperty#SSL_KEY
   */
  public String getSslKey() {
    return PGProperty.SSL_KEY.get(properties);
  }

  /**
   * @param file SSL key
   * @see PGProperty#SSL_KEY
   */
  public void setSslKey(String file) {
    PGProperty.SSL_KEY.set(properties, file);
  }

  /**
   * @return SSL root certificate
   * @see PGProperty#SSL_ROOT_CERT
   */
  public String getSslRootCert() {
    return PGProperty.SSL_ROOT_CERT.get(properties);
  }

  /**
   * @param file SSL root certificate
   * @see PGProperty#SSL_ROOT_CERT
   */
  public void setSslRootCert(String file) {
    PGProperty.SSL_ROOT_CERT.set(properties, file);
  }

  /**
   * @return SSL password
   * @see PGProperty#SSL_PASSWORD
   */
  public String getSslPassword() {
    return PGProperty.SSL_PASSWORD.get(properties);
  }

  /**
   * @param password SSL password
   * @see PGProperty#SSL_PASSWORD
   */
  public void setSslPassword(String password) {
    PGProperty.SSL_PASSWORD.set(properties, password);
  }

  /**
   * @return SSL password callback
   * @see PGProperty#SSL_PASSWORD_CALLBACK
   */
  public String getSslPasswordCallback() {
    return PGProperty.SSL_PASSWORD_CALLBACK.get(properties);
  }

  /**
   * @param className SSL password callback class name
   * @see PGProperty#SSL_PASSWORD_CALLBACK
   */
  public void setSslPasswordCallback(String className) {
    PGProperty.SSL_PASSWORD_CALLBACK.set(properties, className);
  }

  /**
   * @param applicationName application name
   * @see PGProperty#APPLICATION_NAME
   */
  public void setApplicationName(String applicationName) {
    PGProperty.APPLICATION_NAME.set(properties, applicationName);
  }

  /**
   * @return application name
   * @see PGProperty#APPLICATION_NAME
   */
  public String getApplicationName() {
    return PGProperty.APPLICATION_NAME.get(properties);
  }

  /**
   * @param targetServerType target server type
   * @see PGProperty#TARGET_SERVER_TYPE
   */
  public void setTargetServerType(String targetServerType) {
    PGProperty.TARGET_SERVER_TYPE.set(properties, targetServerType);
  }

  /**
   * @return target server type
   * @see PGProperty#TARGET_SERVER_TYPE
   */
  public String getTargetServerType() {
    return PGProperty.TARGET_SERVER_TYPE.get(properties);
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
  public void setBinaryTransferEnable(String oidList) {
    PGProperty.BINARY_TRANSFER_ENABLE.set(properties, oidList);
  }

  /**
   * @return list of OIDs that are allowed to use binary transfer
   * @see PGProperty#BINARY_TRANSFER_ENABLE
   */
  public String getBinaryTransferEnable() {
    return PGProperty.BINARY_TRANSFER_ENABLE.get(properties);
  }

  /**
   * @param oidList list of OIDs that are not allowed to use binary transfer
   * @see PGProperty#BINARY_TRANSFER_DISABLE
   */
  public void setBinaryTransferDisable(String oidList) {
    PGProperty.BINARY_TRANSFER_DISABLE.set(properties, oidList);
  }

  /**
   * @return list of OIDs that are not allowed to use binary transfer
   * @see PGProperty#BINARY_TRANSFER_DISABLE
   */
  public String getBinaryTransferDisable() {
    return PGProperty.BINARY_TRANSFER_DISABLE.get(properties);
  }

  /**
   * @return string type
   * @see PGProperty#STRING_TYPE
   */
  public String getStringType() {
    return PGProperty.STRING_TYPE.get(properties);
  }

  /**
   * @param stringType string type
   * @see PGProperty#STRING_TYPE
   */
  public void setStringType(String stringType) {
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
  public String getCurrentSchema() {
    return PGProperty.CURRENT_SCHEMA.get(properties);
  }

  /**
   * @param currentSchema current schema
   * @see PGProperty#CURRENT_SCHEMA
   */
  public void setCurrentSchema(String currentSchema) {
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
    return PGProperty.READ_ONLY_MODE.get(properties);
  }

  /**
   * @param mode the behavior when set read only
   * @see PGProperty#READ_ONLY_MODE
   */
  public void setReadOnlyMode(String mode) {
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
  public String getAssumeMinServerVersion() {
    return PGProperty.ASSUME_MIN_SERVER_VERSION.get(properties);
  }

  /**
   * @param minVersion assumed minimal server version
   * @see PGProperty#ASSUME_MIN_SERVER_VERSION
   */
  public void setAssumeMinServerVersion(String minVersion) {
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(properties, minVersion);
  }

  /**
   * @return JAAS application name
   * @see PGProperty#JAAS_APPLICATION_NAME
   */
  public String getJaasApplicationName() {
    return PGProperty.JAAS_APPLICATION_NAME.get(properties);
  }

  /**
   * @param name JAAS application name
   * @see PGProperty#JAAS_APPLICATION_NAME
   */
  public void setJaasApplicationName(String name) {
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
  public String getKerberosServerName() {
    return PGProperty.KERBEROS_SERVER_NAME.get(properties);
  }

  /**
   * @param serverName Kerberos server name
   * @see PGProperty#KERBEROS_SERVER_NAME
   */
  public void setKerberosServerName(String serverName) {
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
  public String getGssLib() {
    return PGProperty.GSS_LIB.get(properties);
  }

  /**
   * @param lib GSS mode: auto, sspi, or gssapi
   * @see PGProperty#GSS_LIB
   */
  public void setGssLib(String lib) {
    PGProperty.GSS_LIB.set(properties, lib);
  }

  /**
   * @return SSPI service class
   * @see PGProperty#SSPI_SERVICE_CLASS
   */
  public String getSspiServiceClass() {
    return PGProperty.SSPI_SERVICE_CLASS.get(properties);
  }

  /**
   * @param serviceClass SSPI service class
   * @see PGProperty#SSPI_SERVICE_CLASS
   */
  public void setSspiServiceClass(String serviceClass) {
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
  public String getSocketFactory() {
    return PGProperty.SOCKET_FACTORY.get(properties);
  }

  /**
   * @param socketFactoryClassName socket factory class name
   * @see PGProperty#SOCKET_FACTORY
   */
  public void setSocketFactory(String socketFactoryClassName) {
    PGProperty.SOCKET_FACTORY.set(properties, socketFactoryClassName);
  }

  /**
   * @return socket factory argument
   * @see PGProperty#SOCKET_FACTORY_ARG
   */
  public String getSocketFactoryArg() {
    return PGProperty.SOCKET_FACTORY_ARG.get(properties);
  }

  /**
   * @param socketFactoryArg socket factory argument
   * @see PGProperty#SOCKET_FACTORY_ARG
   */
  public void setSocketFactoryArg(String socketFactoryArg) {
    PGProperty.SOCKET_FACTORY_ARG.set(properties, socketFactoryArg);
  }

  /**
   * @param replication set to 'database' for logical replication or 'true' for physical replication
   * @see PGProperty#REPLICATION
   */
  public void setReplication(String replication) {
    PGProperty.REPLICATION.set(properties, replication);
  }

  /**
   * @return 'select', "callIfNoReturn', or 'call'
   * @see PGProperty#ESCAPE_SYNTAX_CALL_MODE
   */
  public String getEscapeSyntaxCallMode() {
    return PGProperty.ESCAPE_SYNTAX_CALL_MODE.get(properties);
  }

  /**
   * @param callMode the call mode to use for JDBC escape call syntax
   * @see PGProperty#ESCAPE_SYNTAX_CALL_MODE
   */
  public void setEscapeSyntaxCallMode(String callMode) {
    PGProperty.ESCAPE_SYNTAX_CALL_MODE.set(properties, callMode);
  }

  /**
   * @return null, 'database', or 'true
   * @see PGProperty#REPLICATION
   */
  public String getReplication() {
    return PGProperty.REPLICATION.get(properties);
  }

  /**
   * @return Logger Level of the JDBC Driver
   * @see PGProperty#LOGGER_LEVEL
   */
  public String getLoggerLevel() {
    return PGProperty.LOGGER_LEVEL.get(properties);
  }

  /**
   * @param loggerLevel of the JDBC Driver
   * @see PGProperty#LOGGER_LEVEL
   */
  public void setLoggerLevel(String loggerLevel) {
    PGProperty.LOGGER_LEVEL.set(properties, loggerLevel);
  }

  /**
   * @return File output of the Logger.
   * @see PGProperty#LOGGER_FILE
   */
  public String getLoggerFile() {
    ExpressionProperties exprProps = new ExpressionProperties(properties, System.getProperties());
    return PGProperty.LOGGER_FILE.get(exprProps);
  }

  /**
   * @param loggerFile File output of the Logger.
   * @see PGProperty#LOGGER_LEVEL
   */
  public void setLoggerFile(String loggerFile) {
    PGProperty.LOGGER_FILE.set(properties, loggerFile);
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
      if (portNumbers != null && portNumbers.length >= i && portNumbers[i] != 0) {
        url.append(":").append(portNumbers[i]);
      }
    }
    url.append("/").append(URLCoder.encode(databaseName));

    StringBuilder query = new StringBuilder(100);
    for (PGProperty property : PGProperty.values()) {
      if (property.isPresent(properties)) {
        if (query.length() != 0) {
          query.append("&");
        }
        query.append(property.getName());
        query.append("=");
        query.append(URLCoder.encode(property.get(properties)));
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

    Properties p = org.postgresql.Driver.parseURL(url, null);

    if (p == null) {
      throw new IllegalArgumentException("URL invalid " + url);
    }
    for (PGProperty property : PGProperty.values()) {
      if (!this.properties.containsKey(property.getName())) {
        setProperty(property, property.get(p));
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

  public String getProperty(String name) throws SQLException {
    PGProperty pgProperty = PGProperty.forName(name);
    if (pgProperty != null) {
      return getProperty(pgProperty);
    } else {
      throw new PSQLException(GT.tr("Unsupported property name: {0}", name),
        PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  public void setProperty(String name, String value) throws SQLException {
    PGProperty pgProperty = PGProperty.forName(name);
    if (pgProperty != null) {
      setProperty(pgProperty, value);
    } else {
      throw new PSQLException(GT.tr("Unsupported property name: {0}", name),
        PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  public String getProperty(PGProperty property) {
    return property.get(properties);
  }

  public void setProperty(PGProperty property, String value) {
    if (value == null) {
      return;
    }
    switch (property) {
      case PG_HOST:
        setServerNames(value.split(","));
        break;
      case PG_PORT:
        String[] ps = value.split(",");
        int[] ports = new int[ps.length];
        for (int i = 0 ; i < ps.length; i++) {
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
        ref.add(new StringRefAddr(property.getName(), property.get(properties)));
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
    setServerNames(getReferenceProperty(ref, "serverName").split(","));

    for (PGProperty property : PGProperty.values()) {
      setProperty(property, getReferenceProperty(ref, property.getName()));
    }
  }

  private static String getReferenceProperty(Reference ref, String propertyName) {
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
    return PreferQueryMode.of(PGProperty.PREFER_QUERY_MODE.get(properties));
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
    return AutoSave.of(PGProperty.AUTOSAVE.get(properties));
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

  public String getMaxResultBuffer() {
    return PGProperty.MAX_RESULT_BUFFER.get(properties);
  }

  public void setMaxResultBuffer(String maxResultBuffer) {
    PGProperty.MAX_RESULT_BUFFER.set(properties, maxResultBuffer);
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Override
  //#endif
  public java.util.logging.Logger getParentLogger() {
    return Logger.getLogger("org.postgresql");
  }

  /*
   * Alias methods below, these are to help with ease-of-use with other database tools / frameworks
   * which expect normal java bean getters / setters to exist for the property names.
   */

  public boolean isSsl() {
    return getSsl();
  }

  public String getSslfactoryarg() {
    return getSslFactoryArg();
  }

  public void setSslfactoryarg(final String arg) {
    setSslFactoryArg(arg);
  }

  public String getSslcert() {
    return getSslCert();
  }

  public void setSslcert(final String file) {
    setSslCert(file);
  }

  public String getSslmode() {
    return getSslMode();
  }

  public void setSslmode(final String mode) {
    setSslMode(mode);
  }

  public String getSslhostnameverifier() {
    return getSslHostnameVerifier();
  }

  public void setSslhostnameverifier(final String className) {
    setSslHostnameVerifier(className);
  }

  public String getSslkey() {
    return getSslKey();
  }

  public void setSslkey(final String file) {
    setSslKey(file);
  }

  public String getSslrootcert() {
    return getSslRootCert();
  }

  public void setSslrootcert(final String file) {
    setSslRootCert(file);
  }

  public String getSslpasswordcallback() {
    return getSslPasswordCallback();
  }

  public void setSslpasswordcallback(final String className) {
    setSslPasswordCallback(className);
  }

  public String getSslpassword() {
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
