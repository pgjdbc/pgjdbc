/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql;

import org.postgresql.util.DriverInfo;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * All connection parameters that can be either set in JDBC URL, in Driver properties or in
 * datasource setters.
 */
public enum PGProperty {

  /**
   * When using the V3 protocol the driver monitors changes in certain server configuration
   * parameters that should not be touched by end users. The {@code client_encoding} setting is set
   * by the driver and should not be altered. If the driver detects a change it will abort the
   * connection.
   */
  ALLOW_ENCODING_CHANGES(
    "allowEncodingChanges",
    "false",
    "Allow for changes in client_encoding"),

  /**
   * The application name (require server version &gt;= 9.0).
   */
  APPLICATION_NAME(
    "ApplicationName",
    DriverInfo.DRIVER_NAME,
    "Name of the Application (backend >= 9.0)"),

  /**
   * Assume the server is at least that version.
   */
  ASSUME_MIN_SERVER_VERSION(
    "assumeMinServerVersion",
    null,
    "Assume the server is at least that version"),

  /**
   * Specifies what the driver should do if a query fails. In {@code autosave=always} mode, JDBC driver sets a savepoint before each query,
   * and rolls back to that savepoint in case of failure. In {@code autosave=never} mode (default), no savepoint dance is made ever.
   * In {@code autosave=conservative} mode, savepoint is set for each query, however the rollback is done only for rare cases
   * like 'cached statement cannot change return type' or 'statement XXX is not valid' so JDBC driver rollsback and retries
   */
  AUTOSAVE(
    "autosave",
    "never",
    "Specifies what the driver should do if a query fails. In autosave=always mode, JDBC driver sets a savepoint before each query, "
        + "and rolls back to that savepoint in case of failure. In autosave=never mode (default), no savepoint dance is made ever. "
        + "In autosave=conservative mode, safepoint is set for each query, however the rollback is done only for rare cases"
        + " like 'cached statement cannot change return type' or 'statement XXX is not valid' so JDBC driver rollsback and retries",
    false,
    new String[] {"always", "never", "conservative"}),

  /**
   * Use binary format for sending and receiving data if possible.
   */
  BINARY_TRANSFER(
    "binaryTransfer",
    "true",
    "Use binary format for sending and receiving data if possible"),

  /**
   * Comma separated list of types to disable binary transfer. Either OID numbers or names.
   * Overrides values in the driver default set and values set with binaryTransferEnable.
   */
  BINARY_TRANSFER_DISABLE(
    "binaryTransferDisable",
    "",
    "Comma separated list of types to disable binary transfer. Either OID numbers or names. Overrides values in the driver default set and values set with binaryTransferEnable."),

  /**
   * Comma separated list of types to enable binary transfer. Either OID numbers or names
   */
  BINARY_TRANSFER_ENABLE(
    "binaryTransferEnable",
    "",
    "Comma separated list of types to enable binary transfer. Either OID numbers or names"),

  /**
   * Cancel command is sent out of band over its own connection, so cancel message can itself get
   * stuck.
   * This property controls "connect timeout" and "socket timeout" used for cancel commands.
   * The timeout is specified in seconds. Default value is 10 seconds.
   */
  CANCEL_SIGNAL_TIMEOUT(
    "cancelSignalTimeout",
    "10",
    "The timeout that is used for sending cancel command."),

  /**
   * Determine whether SAVEPOINTS used in AUTOSAVE will be released per query or not
   */
  CLEANUP_SAVEPOINTS(
    "cleanupSavepoints",
    "false",
    "Determine whether SAVEPOINTS used in AUTOSAVE will be released per query or not",
    false,
    new String[] {"true", "false"}),

  /**
   * <p>The timeout value used for socket connect operations. If connecting to the server takes longer
   * than this value, the connection is broken.</p>
   *
   * <p>The timeout is specified in seconds and a value of zero means that it is disabled.</p>
   */
  CONNECT_TIMEOUT(
    "connectTimeout",
    "10",
    "The timeout value used for socket connect operations."),

  /**
   * Specify the schema (or several schema separated by commas) to be set in the search-path. This schema will be used to resolve
   * unqualified object names used in statements over this connection.
   */
  CURRENT_SCHEMA(
    "currentSchema",
    null,
    "Specify the schema (or several schema separated by commas) to be set in the search-path"),

  /**
   * Specifies the maximum number of fields to be cached per connection. A value of {@code 0} disables the cache.
   */
  DATABASE_METADATA_CACHE_FIELDS(
    "databaseMetadataCacheFields",
    "65536",
    "Specifies the maximum number of fields to be cached per connection. A value of {@code 0} disables the cache."),

  /**
   * Specifies the maximum size (in megabytes) of fields to be cached per connection. A value of {@code 0} disables the cache.
   */
  DATABASE_METADATA_CACHE_FIELDS_MIB(
    "databaseMetadataCacheFieldsMiB",
    "5",
    "Specifies the maximum size (in megabytes) of fields to be cached per connection. A value of {@code 0} disables the cache."),

  /**
   * Default parameter for {@link java.sql.Statement#getFetchSize()}. A value of {@code 0} means
   * that need fetch all rows at once
   */
  DEFAULT_ROW_FETCH_SIZE(
    "defaultRowFetchSize",
    "0",
    "Positive number of rows that should be fetched from the database when more rows are needed for ResultSet by each fetch iteration"),

  /**
   * Enable optimization that disables column name sanitiser.
   */
  DISABLE_COLUMN_SANITISER(
    "disableColumnSanitiser",
    "false",
    "Enable optimization that disables column name sanitiser"),

  /**
   * Instructs the {@link org.postgresql.core.Parser} to escape (with double quotes) the column
   * identifiers appended to the returning clause. A value of {@code true} (default) will make the
   * parser escape the column identifiers in the returning clause. A value of {@code false} will
   * disable this behavior and leave the column identifiers unchanged.
   */
  ESCAPE_RETURNING_COLUMNS(
      "escapeReturningColumns",
      "true",
      "Escaping column identifiers when appending them to the `RETURNING` clause"
  ),

  /**
   * Specifies how the driver transforms JDBC escape call syntax into underlying SQL, for invoking procedures or functions. (backend &gt;= 11)
   * In {@code escapeSyntaxCallMode=select} mode (the default), the driver always uses a SELECT statement (allowing function invocation only).
   * In {@code escapeSyntaxCallMode=callIfNoReturn} mode, the driver uses a CALL statement (allowing procedure invocation) if there is no return parameter specified, otherwise the driver uses a SELECT statement.
   * In {@code escapeSyntaxCallMode=call} mode, the driver always uses a CALL statement (allowing procedure invocation only).
   */
  ESCAPE_SYNTAX_CALL_MODE(
    "escapeSyntaxCallMode",
    "select",
    "Specifies how the driver transforms JDBC escape call syntax into underlying SQL, for invoking procedures or functions. (backend >= 11)"
      + "In escapeSyntaxCallMode=select mode (the default), the driver always uses a SELECT statement (allowing function invocation only)."
      + "In escapeSyntaxCallMode=callIfNoReturn mode, the driver uses a CALL statement (allowing procedure invocation) if there is no return parameter specified, otherwise the driver uses a SELECT statement."
      + "In escapeSyntaxCallMode=call mode, the driver always uses a CALL statement (allowing procedure invocation only).",
    false,
    new String[] {"select", "callIfNoReturn", "call"}),

  GSS_ENC_MODE(
      "gssEncMode",
      "prefer",
      "Force Encoded GSS Mode",
      false,
      new String[] {"disable", "prefer", "require"}
  ),

  /**
   * Force one of
   * <ul>
   * <li>SSPI (Windows transparent single-sign-on)</li>
   * <li>GSSAPI (Kerberos, via JSSE)</li>
   * </ul>
   * to be used when the server requests Kerberos or SSPI authentication.
   */
  GSS_LIB(
    "gsslib",
    "auto",
    "Force SSSPI or GSSAPI",
    false,
    new String[] {"auto", "sspi", "gssapi"}),

  /**
   * Enable mode to filter out the names of database objects for which the current user has no privileges
   * granted from appearing in the DatabaseMetaData returned by the driver.
   */
  HIDE_UNPRIVILEGED_OBJECTS(
    "hideUnprivilegedObjects",
    "false",
    "Enable hiding of database objects for which the current user has no privileges granted from the DatabaseMetaData"),

  HOST_RECHECK_SECONDS(
    "hostRecheckSeconds",
    "10",
    "Specifies period (seconds) after which the host status is checked again in case it has changed"),

  /**
   * Specifies the name of the JAAS system or application login configuration.
   */
  JAAS_APPLICATION_NAME(
    "jaasApplicationName",
    null,
    "Specifies the name of the JAAS system or application login configuration."),

  /**
   * Flag to enable/disable obtaining a GSS credential via JAAS login before authenticating.
   * Useful if setting system property javax.security.auth.useSubjectCredsOnly=false
   * or using native GSS with system property sun.security.jgss.native=true
   */
  JAAS_LOGIN(
    "jaasLogin",
    "true",
    "Login with JAAS before doing GSSAPI authentication"),

  /**
   * The Kerberos service name to use when authenticating with GSSAPI. This is equivalent to libpq's
   * PGKRBSRVNAME environment variable.
   */
  KERBEROS_SERVER_NAME(
    "kerberosServerName",
    null,
    "The Kerberos service name to use when authenticating with GSSAPI."),

  LOAD_BALANCE_HOSTS(
    "loadBalanceHosts",
    "false",
    "If disabled hosts are connected in the given order. If enabled hosts are chosen randomly from the set of suitable candidates"),

  /**
   * <p>File name output of the Logger, if set, the Logger will use a
   * {@link java.util.logging.FileHandler} to write to a specified file. If the parameter is not set
   * or the file can't be created the {@link java.util.logging.ConsoleHandler} will be used instead.</p>
   *
   * <p>Parameter should be use together with {@link PGProperty#LOGGER_LEVEL}</p>
   */
  LOGGER_FILE(
    "loggerFile",
    null,
    "File name output of the Logger"),

  /**
   * <p>Logger level of the driver. Allowed values: {@code OFF}, {@code DEBUG} or {@code TRACE}.</p>
   *
   * <p>This enable the {@link java.util.logging.Logger} of the driver based on the following mapping
   * of levels:</p>
   * <ul>
   *     <li>FINE -&gt; DEBUG</li>
   *     <li>FINEST -&gt; TRACE</li>
   * </ul>
   *
   * <p><b>NOTE:</b> The recommended approach to enable java.util.logging is using a
   * {@code logging.properties} configuration file with the property
   * {@code -Djava.util.logging.config.file=myfile} or if your are using an application server
   * you should use the appropriate logging subsystem.</p>
   */
  LOGGER_LEVEL(
    "loggerLevel",
    null,
    "Logger level of the driver",
    false,
    new String[] {"OFF", "DEBUG", "TRACE"}),

  /**
   * Specify how long to wait for establishment of a database connection. The timeout is specified
   * in seconds.
   */
  LOGIN_TIMEOUT(
    "loginTimeout",
    "0",
    "Specify how long to wait for establishment of a database connection."),

  /**
   * Whether to include full server error detail in exception messages.
   */
  LOG_SERVER_ERROR_DETAIL(
    "logServerErrorDetail",
    "true",
    "Include full server error detail in exception messages. If disabled then only the error itself will be included."),

  /**
   * When connections that are not explicitly closed are garbage collected, log the stacktrace from
   * the opening of the connection to trace the leak source.
   */
  LOG_UNCLOSED_CONNECTIONS(
    "logUnclosedConnections",
    "false",
    "When connections that are not explicitly closed are garbage collected, log the stacktrace from the opening of the connection to trace the leak source"),

  /**
   * Specifies size of buffer during fetching result set. Can be specified as specified size or
   * percent of heap memory.
   */
  MAX_RESULT_BUFFER(
    "maxResultBuffer",
    null,
    "Specifies size of buffer during fetching result set. Can be specified as specified size or percent of heap memory."),

  /**
   * Specify 'options' connection initialization parameter.
   * The value of this parameter may contain spaces and other special characters or their URL representation.
   */
  OPTIONS(
    "options",
    null,
    "Specify 'options' connection initialization parameter."),

  /**
   * Password to use when authenticating.
   */
  PASSWORD(
    "password",
    null,
    "Password to use when authenticating.",
    false),

  /**
   * Database name to connect to (may be specified directly in the JDBC URL).
   */
  PG_DBNAME(
    "PGDBNAME",
    null,
    "Database name to connect to (may be specified directly in the JDBC URL)",
    true),

  /**
   * Hostname of the PostgreSQL server (may be specified directly in the JDBC URL).
   */
  PG_HOST(
    "PGHOST",
    null,
    "Hostname of the PostgreSQL server (may be specified directly in the JDBC URL)",
    false),

  /**
   * Port of the PostgreSQL server (may be specified directly in the JDBC URL).
   */
  PG_PORT(
    "PGPORT",
    null,
    "Port of the PostgreSQL server (may be specified directly in the JDBC URL)"),

  /**
   * <p>Specifies which mode is used to execute queries to database: simple means ('Q' execute, no parse, no bind, text mode only),
   * extended means always use bind/execute messages, extendedForPrepared means extended for prepared statements only,
   * extendedCacheEverything means use extended protocol and try cache every statement (including Statement.execute(String sql)) in a query cache.</p>
   *
   * <p>This mode is meant for debugging purposes and/or for cases when extended protocol cannot be used (e.g. logical replication protocol)</p>
   */
  PREFER_QUERY_MODE(
    "preferQueryMode",
    "extended",
    "Specifies which mode is used to execute queries to database: simple means ('Q' execute, no parse, no bind, text mode only), "
        + "extended means always use bind/execute messages, extendedForPrepared means extended for prepared statements only, "
        + "extendedCacheEverything means use extended protocol and try cache every statement (including Statement.execute(String sql)) in a query cache.", false,
    new String[] {"extended", "extendedForPrepared", "extendedCacheEverything", "simple"}),

  /**
   * Specifies the maximum number of entries in cache of prepared statements. A value of {@code 0}
   * disables the cache.
   */
  PREPARED_STATEMENT_CACHE_QUERIES(
    "preparedStatementCacheQueries",
    "256",
    "Specifies the maximum number of entries in per-connection cache of prepared statements. A value of {@code 0} disables the cache."),

  /**
   * Specifies the maximum size (in megabytes) of the prepared statement cache. A value of {@code 0}
   * disables the cache.
   */
  PREPARED_STATEMENT_CACHE_SIZE_MIB(
    "preparedStatementCacheSizeMiB",
    "5",
    "Specifies the maximum size (in megabytes) of a per-connection prepared statement cache. A value of {@code 0} disables the cache."),

  /**
   * Sets the default threshold for enabling server-side prepare. A value of {@code -1} stands for
   * forceBinary
   */
  PREPARE_THRESHOLD(
    "prepareThreshold",
    "5",
    "Statement prepare threshold. A value of {@code -1} stands for forceBinary"),

  /**
   * Force use of a particular protocol version when connecting, if set, disables protocol version
   * fallback.
   */
  PROTOCOL_VERSION(
    "protocolVersion",
    null,
    "Force use of a particular protocol version when connecting, currently only version 3 is supported.",
    false,
    new String[] {"3"}),

  /**
   * Puts this connection in read-only mode.
   */
  READ_ONLY(
    "readOnly",
    "false",
    "Puts this connection in read-only mode"),

  /**
   * Connection parameter to control behavior when
   * {@link Connection#setReadOnly(boolean)} is set to {@code true}.
   */
  READ_ONLY_MODE(
    "readOnlyMode",
    "transaction",
    "Controls the behavior when a connection is set to be read only, one of 'ignore', 'transaction', or 'always' "
      + "When 'ignore', setting readOnly has no effect. "
      + "When 'transaction' setting readOnly to 'true' will cause transactions to BEGIN READ ONLY if autocommit is 'false'. "
      + "When 'always' setting readOnly to 'true' will set the session to READ ONLY if autoCommit is 'true' "
      + "and the transaction to BEGIN READ ONLY if autocommit is 'false'.",
    false,
    new String[] {"ignore", "transaction", "always"}),

  /**
   * Socket read buffer size (SO_RECVBUF). A value of {@code -1}, which is the default, means system
   * default.
   */
  RECEIVE_BUFFER_SIZE(
    "receiveBufferSize",
    "-1",
    "Socket read buffer size"),

  /**
   * <p>Connection parameter passed in the startup message. This parameter accepts two values; "true"
   * and "database". Passing "true" tells the backend to go into walsender mode, wherein a small set
   * of replication commands can be issued instead of SQL statements. Only the simple query protocol
   * can be used in walsender mode. Passing "database" as the value instructs walsender to connect
   * to the database specified in the dbname parameter, which will allow the connection to be used
   * for logical replication from that database.</p>
   * <p>Parameter should be use together with {@link PGProperty#ASSUME_MIN_SERVER_VERSION} with
   * parameter &gt;= 9.4 (backend &gt;= 9.4)</p>
   */
  REPLICATION(
    "replication",
    null,
    "Connection parameter passed in startup message, one of 'true' or 'database' "
      + "Passing 'true' tells the backend to go into walsender mode, "
      + "wherein a small set of replication commands can be issued instead of SQL statements. "
      + "Only the simple query protocol can be used in walsender mode. "
      + "Passing 'database' as the value instructs walsender to connect "
      + "to the database specified in the dbname parameter, "
      + "which will allow the connection to be used for logical replication "
      + "from that database. "
      + "(backend >= 9.4)"),

  /**
   * Configure optimization to enable batch insert re-writing.
   */
  REWRITE_BATCHED_INSERTS(
    "reWriteBatchedInserts",
    "false",
    "Enable optimization to rewrite and collapse compatible INSERT statements that are batched."),

  /**
   * Socket write buffer size (SO_SNDBUF). A value of {@code -1}, which is the default, means system
   * default.
   */
  SEND_BUFFER_SIZE(
    "sendBufferSize",
    "-1",
    "Socket write buffer size"),

  /**
   * Socket factory used to create socket. A null value, which is the default, means system default.
   */
  SOCKET_FACTORY(
    "socketFactory",
    null,
    "Specify a socket factory for socket creation"),

  /**
   * The String argument to give to the constructor of the Socket Factory.
   * @deprecated use {@code ..Factory(Properties)} constructor.
   */
  @Deprecated
  SOCKET_FACTORY_ARG(
    "socketFactoryArg",
    null,
    "Argument forwarded to constructor of SocketFactory class."),

  /**
   * The timeout value used for socket read operations. If reading from the server takes longer than
   * this value, the connection is closed. This can be used as both a brute force global query
   * timeout and a method of detecting network problems. The timeout is specified in seconds and a
   * value of zero means that it is disabled.
   */
  SOCKET_TIMEOUT(
    "socketTimeout",
    "0",
    "The timeout value used for socket read operations."),

  /**
   * Control use of SSL: empty or {@code true} values imply {@code sslmode==verify-full}
   */
  SSL(
    "ssl",
    null,
    "Control use of SSL (any non-null value causes SSL to be required)"),

  /**
   * File containing the SSL Certificate. Default will be the file {@code postgresql.crt} in {@code
   * $HOME/.postgresql} (*nix) or {@code %APPDATA%\postgresql} (windows).
   */
  SSL_CERT(
    "sslcert",
    null,
    "The location of the client's SSL certificate"),

  /**
   * Classname of the SSL Factory to use (instance of {@code javax.net.ssl.SSLSocketFactory}).
   */
  SSL_FACTORY(
    "sslfactory",
    null,
    "Provide a SSLSocketFactory class when using SSL."),

  /**
   * The String argument to give to the constructor of the SSL Factory.
   * @deprecated use {@code ..Factory(Properties)} constructor.
   */
  @Deprecated
  SSL_FACTORY_ARG(
    "sslfactoryarg",
    null,
    "Argument forwarded to constructor of SSLSocketFactory class."),

  /**
   * Classname of the SSL HostnameVerifier to use (instance of {@code
   * javax.net.ssl.HostnameVerifier}).
   */
  SSL_HOSTNAME_VERIFIER(
    "sslhostnameverifier",
    null,
    "A class, implementing javax.net.ssl.HostnameVerifier that can verify the server"),

  /**
   * File containing the SSL Key. Default will be the file {@code postgresql.pk8} in {@code
   * $HOME/.postgresql} (*nix) or {@code %APPDATA%\postgresql} (windows).
   */
  SSL_KEY(
    "sslkey",
    null,
    "The location of the client's PKCS#8 SSL key"),

  /**
   * Parameter governing the use of SSL. The allowed values are {@code disable}, {@code allow},
   * {@code prefer}, {@code require}, {@code verify-ca}, {@code verify-full}.
   * If {@code ssl} property is empty or set to {@code true} it implies {@code verify-full}.
   * Default mode is "require"
   */
  SSL_MODE(
    "sslmode",
    null,
    "Parameter governing the use of SSL",
    false,
    new String[] {"disable", "allow", "prefer", "require", "verify-ca", "verify-full"}),

  /**
   * The SSL password to use in the default CallbackHandler.
   */
  SSL_PASSWORD(
    "sslpassword",
    null,
    "The password for the client's ssl key (ignored if sslpasswordcallback is set)"),


  /**
   * The classname instantiating {@code javax.security.auth.callback.CallbackHandler} to use.
   */
  SSL_PASSWORD_CALLBACK(
    "sslpasswordcallback",
    null,
    "A class, implementing javax.security.auth.callback.CallbackHandler that can handle PassworCallback for the ssl password."),

  /**
   * File containing the root certificate when validating server ({@code sslmode} = {@code
   * verify-ca} or {@code verify-full}). Default will be the file {@code root.crt} in {@code
   * $HOME/.postgresql} (*nix) or {@code %APPDATA%\postgresql} (windows).
   */
  SSL_ROOT_CERT(
    "sslrootcert",
    null,
    "The location of the root certificate for authenticating the server."),

  /**
   * Specifies the name of the SSPI service class that forms the service class part of the SPN. The
   * default, {@code POSTGRES}, is almost always correct.
   */
  SSPI_SERVICE_CLASS(
    "sspiServiceClass",
    "POSTGRES",
    "The Windows SSPI service class for SPN"),

  /**
   * Bind String to either {@code unspecified} or {@code varchar}. Default is {@code varchar} for
   * 8.0+ backends.
   */
  STRING_TYPE(
    "stringtype",
    null,
    "The type to bind String parameters as (usually 'varchar', 'unspecified' allows implicit casting to other types)",
    false,
    new String[] {"unspecified", "varchar"}),

  TARGET_SERVER_TYPE(
    "targetServerType",
    "any",
    "Specifies what kind of server to connect",
    false,
    new String [] {"any", "primary", "master", "slave", "secondary",  "preferSlave", "preferSecondary"}),

  /**
   * Enable or disable TCP keep-alive. The default is {@code false}.
   */
  TCP_KEEP_ALIVE(
    "tcpKeepAlive",
    "false",
    "Enable or disable TCP keep-alive. The default is {@code false}."),

  /**
   * Specifies the length to return for types of unknown length.
   */
  UNKNOWN_LENGTH(
    "unknownLength",
    Integer.toString(Integer.MAX_VALUE),
    "Specifies the length to return for types of unknown length"),

  /**
   * Username to connect to the database as.
   */
  USER(
    "user",
    null,
    "Username to connect to the database as.",
    true),

  /**
   * Use SPNEGO in SSPI authentication requests.
   */
  USE_SPNEGO(
    "useSpnego",
    "false",
    "Use SPNEGO in SSPI authentication requests"),

  /**
   * Factory class to instantiate factories for XML processing.
   * The default factory disables external entity processing.
   * Legacy behavior with external entity processing can be enabled by specifying a value of LEGACY_INSECURE.
   * Or specify a custom class that implements {@code org.postgresql.xml.PGXmlFactoryFactory}.
   */
  XML_FACTORY_FACTORY(
    "xmlFactoryFactory",
    "",
    "Factory class to instantiate factories for XML processing"),

  ;

  private final String name;
  private final @Nullable String defaultValue;
  private final boolean required;
  private final String description;
  private final String @Nullable [] choices;
  private final boolean deprecated;

  PGProperty(String name, @Nullable String defaultValue, String description) {
    this(name, defaultValue, description, false);
  }

  PGProperty(String name, @Nullable String defaultValue, String description, boolean required) {
    this(name, defaultValue, description, required, (String[]) null);
  }

  PGProperty(String name, @Nullable String defaultValue, String description, boolean required,
      String @Nullable [] choices) {
    this.name = name;
    this.defaultValue = defaultValue;
    this.required = required;
    this.description = description;
    this.choices = choices;
    try {
      this.deprecated = PGProperty.class.getField(name()).getAnnotation(Deprecated.class) != null;
    } catch (NoSuchFieldException e) {
      throw new RuntimeException(e);
    }
  }

  private static final Map<String, PGProperty> PROPS_BY_NAME = new HashMap<String, PGProperty>();
  static {
    for (PGProperty prop : PGProperty.values()) {
      if (PROPS_BY_NAME.put(prop.getName(), prop) != null) {
        throw new IllegalStateException("Duplicate PGProperty name: " + prop.getName());
      }
    }
  }

  /**
   * Returns the name of the connection parameter. The name is the key that must be used in JDBC URL
   * or in Driver properties
   *
   * @return the name of the connection parameter
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the default value for this connection parameter.
   *
   * @return the default value for this connection parameter or null
   */
  public @Nullable String getDefaultValue() {
    return defaultValue;
  }

  /**
   * Returns whether this parameter is required.
   *
   * @return whether this parameter is required
   */
  public boolean isRequired() {
    return required;
  }

  /**
   * Returns the description for this connection parameter.
   *
   * @return the description for this connection parameter
   */
  public String getDescription() {
    return description;
  }

  /**
   * Returns the available values for this connection parameter.
   *
   * @return the available values for this connection parameter or null
   */
  public String @Nullable [] getChoices() {
    return choices;
  }

  /**
   * Returns whether this connection parameter is deprecated.
   *
   * @return whether this connection parameter is deprecated
   */
  public boolean isDeprecated() {
    return deprecated;
  }

  /**
   * Returns the value of the connection parameters according to the given {@code Properties} or the
   * default value.
   *
   * @param properties properties to take actual value from
   * @return evaluated value for this connection parameter
   */
  public @Nullable String get(Properties properties) {
    return properties.getProperty(name, defaultValue);
  }

  /**
   * Set the value for this connection parameter in the given {@code Properties}.
   *
   * @param properties properties in which the value should be set
   * @param value value for this connection parameter
   */
  public void set(Properties properties, @Nullable String value) {
    if (value == null) {
      properties.remove(name);
    } else {
      properties.setProperty(name, value);
    }
  }

  /**
   * Return the boolean value for this connection parameter in the given {@code Properties}.
   *
   * @param properties properties to take actual value from
   * @return evaluated value for this connection parameter converted to boolean
   */
  public boolean getBoolean(Properties properties) {
    return Boolean.parseBoolean(get(properties));
  }

  /**
   * Return the int value for this connection parameter in the given {@code Properties}. Prefer the
   * use of {@link #getInt(Properties)} anywhere you can throw an {@link java.sql.SQLException}.
   *
   * @param properties properties to take actual value from
   * @return evaluated value for this connection parameter converted to int
   * @throws NumberFormatException if it cannot be converted to int.
   */
  @SuppressWarnings("nullness:argument.type.incompatible")
  public int getIntNoCheck(Properties properties) {
    String value = get(properties);
    //noinspection ConstantConditions
    return Integer.parseInt(value);
  }

  /**
   * Return the int value for this connection parameter in the given {@code Properties}.
   *
   * @param properties properties to take actual value from
   * @return evaluated value for this connection parameter converted to int
   * @throws PSQLException if it cannot be converted to int.
   */
  @SuppressWarnings("nullness:argument.type.incompatible")
  public int getInt(Properties properties) throws PSQLException {
    String value = get(properties);
    try {
      //noinspection ConstantConditions
      return Integer.parseInt(value);
    } catch (NumberFormatException nfe) {
      throw new PSQLException(GT.tr("{0} parameter value must be an integer but was: {1}",
          getName(), value), PSQLState.INVALID_PARAMETER_VALUE, nfe);
    }
  }

  /**
   * Return the {@code Integer} value for this connection parameter in the given {@code Properties}.
   *
   * @param properties properties to take actual value from
   * @return evaluated value for this connection parameter converted to Integer or null
   * @throws PSQLException if unable to parse property as integer
   */
  public @Nullable Integer getInteger(Properties properties) throws PSQLException {
    String value = get(properties);
    if (value == null) {
      return null;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException nfe) {
      throw new PSQLException(GT.tr("{0} parameter value must be an integer but was: {1}",
          getName(), value), PSQLState.INVALID_PARAMETER_VALUE, nfe);
    }
  }

  /**
   * Set the boolean value for this connection parameter in the given {@code Properties}.
   *
   * @param properties properties in which the value should be set
   * @param value boolean value for this connection parameter
   */
  public void set(Properties properties, boolean value) {
    properties.setProperty(name, Boolean.toString(value));
  }

  /**
   * Set the int value for this connection parameter in the given {@code Properties}.
   *
   * @param properties properties in which the value should be set
   * @param value int value for this connection parameter
   */
  public void set(Properties properties, int value) {
    properties.setProperty(name, Integer.toString(value));
  }

  /**
   * Test whether this property is present in the given {@code Properties}.
   *
   * @param properties set of properties to check current in
   * @return true if the parameter is specified in the given properties
   */
  public boolean isPresent(Properties properties) {
    return getSetString(properties) != null;
  }

  /**
   * Convert this connection parameter and the value read from the given {@code Properties} into a
   * {@code DriverPropertyInfo}.
   *
   * @param properties properties to take actual value from
   * @return a DriverPropertyInfo representing this connection parameter
   */
  public DriverPropertyInfo toDriverPropertyInfo(Properties properties) {
    DriverPropertyInfo propertyInfo = new DriverPropertyInfo(name, get(properties));
    propertyInfo.required = required;
    propertyInfo.description = description;
    propertyInfo.choices = choices;
    return propertyInfo;
  }

  public static @Nullable PGProperty forName(String name) {
    return PROPS_BY_NAME.get(name);
  }

  /**
   * Return the property if exists but avoiding the default. Allowing the caller to detect the lack
   * of a property.
   *
   * @param properties properties bundle
   * @return the value of a set property
   */
  public @Nullable String getSetString(Properties properties) {
    Object o = properties.get(name);
    if (o instanceof String) {
      return (String) o;
    }
    return null;
  }
}
