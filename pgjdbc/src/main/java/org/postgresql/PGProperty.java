/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql;

import org.postgresql.annotations.PgApi;
import org.postgresql.annotations.PgPropertyType;
import org.postgresql.annotations.PgTags;
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
   * Specifies if number of rows, used during fetching rows of a result set, should be computed
   * dynamically. Number of rows will be calculated by dividing maxResultBuffer size by max row size
   * observed so far, rounded down. First fetch will have number of rows declared in
   * defaultRowFetchSize. Number of rows can be limited by adaptiveFetchMinimum and
   * adaptiveFetchMaximum. Requires declaring of maxResultBuffer and defaultRowFetchSize to work.
   * Default value is false.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.11")
  @PgTags({PgTags.Tag.FETCH, PgTags.Tag.NETWORK})
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  ADAPTIVE_FETCH(
      "adaptiveFetch",
      "false",
      "Specifies if number of rows fetched in ResultSet should be adaptive to maxResultBuffer and max row size."),

  /**
   * Specifies the highest number of rows which can be calculated by adaptiveFetch. Requires
   * adaptiveFetch set to true to work. Default value is -1 (used as infinity).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.11")
  @PgTags({PgTags.Tag.FETCH, PgTags.Tag.NETWORK})
  @PgPropertyType(PgPropertyType.Kind.INT)
  ADAPTIVE_FETCH_MAXIMUM(
      "adaptiveFetchMaximum",
      "-1",
      "Specifies maximum number of rows used by adaptive fetch."),

  /**
   * Specifies the lowest number of rows which can be calculated by adaptiveFetch. Requires
   * adaptiveFetch set to true to work. Default value is 0.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.11")
  @PgTags({PgTags.Tag.FETCH, PgTags.Tag.NETWORK})
  @PgPropertyType(PgPropertyType.Kind.INT)
  ADAPTIVE_FETCH_MINIMUM(
      "adaptiveFetchMinimum",
      "0",
      "Specifies minimum number of rows used by adaptive fetch."),

  /**
   * When using the V3 protocol the driver monitors changes in certain server configuration
   * parameters that should not be touched by end users. The {@code client_encoding} setting is set
   * by the driver and should not be altered. If the driver detects a change it will abort the
   * connection.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.COMPATIBILITY)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  ALLOW_ENCODING_CHANGES(
      "allowEncodingChanges",
      "false",
      "Allow for changes in client_encoding"),

  /**
   * The application name (require server version &gt;= 9.0).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags({PgTags.Tag.CONNECTION, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.STRING)
  APPLICATION_NAME(
      "ApplicationName",
      DriverInfo.DRIVER_NAME,
      "Name of the Application (backend >= 9.0)"),

  /**
   * Assume the server is at least that version.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.REPLICATION)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  ASSUME_MIN_SERVER_VERSION(
      "assumeMinServerVersion",
      null,
      "Assume the server is at least that version"),

  /**
   * AuthenticationPluginClass
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.25")
  @PgTags(PgTags.Tag.AUTHENTICATION)
  @PgPropertyType(PgPropertyType.Kind.CLASS)
  AUTHENTICATION_PLUGIN_CLASS_NAME(
      "authenticationPluginClassName",
      null,
      "Name of class which implements AuthenticationPlugin"
  ),

  /**
   * Specifies what the driver should do if a query fails. In {@code autosave=always} mode, JDBC driver sets a savepoint before each query,
   * and rolls back to that savepoint in case of failure. In {@code autosave=never} mode (default), no savepoint dance is made ever.
   * In {@code autosave=conservative} mode, savepoint is set for each query, however the rollback is done only for rare cases
   * like 'cached statement cannot change return type' or 'statement XXX is not valid' so JDBC driver rollsback and retries
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4-1206")
  @PgTags(PgTags.Tag.TRANSACTION)
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  AUTOSAVE(
      "autosave",
      "never",
      "Specifies what the driver should do if a query fails. In autosave=always mode, JDBC driver sets a savepoint before each query, "
          + "and rolls back to that savepoint in case of failure. In autosave=never mode (default), no savepoint dance is made ever. "
          + "In autosave=conservative mode, safepoint is set for each query, however the rollback is done only for rare cases"
          + " like 'cached statement cannot change return type' or 'statement XXX is not valid' so JDBC driver rollsback and retries",
      false,
      new String[]{"always", "never", "conservative"}),

  /**
   * Use binary format for sending and receiving data if possible.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.BINARY_TRANSFER)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  BINARY_TRANSFER(
      "binaryTransfer",
      "true",
      "Enable binary transfer for supported built-in types if possible. "
          + "Setting this to false disables any binary transfer unless it's individually activated "
          + "for each type with `binaryTransferEnable`."),

  /**
   * Comma separated list of types to disable binary transfer. Either OID numbers or names.
   * Overrides values in the driver default set and values set with binaryTransferEnable.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.BINARY_TRANSFER)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  BINARY_TRANSFER_DISABLE(
      "binaryTransferDisable",
      "",
      "Comma separated list of types to disable binary transfer. Either OID numbers or names. "
          + "Overrides values in the driver default set and values set with binaryTransferEnable."),

  /**
   * Comma separated list of types to enable binary transfer. Either OID numbers or names
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.BINARY_TRANSFER)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  BINARY_TRANSFER_ENABLE(
      "binaryTransferEnable",
      "",
      "Comma separated list of types to enable binary transfer. Either OID numbers or names."),

  /**
   * Cancel command is sent out of band over its own connection, so cancel message can itself get
   * stuck.
   * This property controls "connect timeout" and "socket timeout" used for cancel commands.
   * The timeout is specified in seconds. Default value is 10 seconds.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags({PgTags.Tag.TIMEOUT, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.DURATION_SECONDS)
  CANCEL_SIGNAL_TIMEOUT(
      "cancelSignalTimeout",
      "10",
      "The timeout that is used for sending cancel command."),

  /**
   * Channel binding is a method for the server to authenticate itself to the
   * client. It is only supported over SSL connections with PostgreSQL 11 or later
   * servers using the SCRAM authentication method.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.7.0")
  @PgTags({PgTags.Tag.SSL, PgTags.Tag.AUTHENTICATION, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  CHANNEL_BINDING(
      "channelBinding",
      "prefer",
      "This option controls the client's use of channel binding.",
      false,
      new String[] {"disable", "prefer", "require"}),

  /**
   * Determine whether SAVEPOINTS used in AUTOSAVE will be released per query or not
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.9")
  @PgTags(PgTags.Tag.TRANSACTION)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  CLEANUP_SAVEPOINTS(
      "cleanupSavepoints",
      "false",
      "Determine whether SAVEPOINTS used in AUTOSAVE will be released per query or not",
      false,
      new String[]{"true", "false"}),

  /**
   * The timeout value used for socket connect operations. If connecting to the server takes longer
   * than this value, the connection is broken.
   *
   * <p>The timeout is specified in seconds and a value of zero means that it is disabled.</p>
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags({PgTags.Tag.TIMEOUT, PgTags.Tag.NETWORK, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.DURATION_SECONDS)
  CONNECT_TIMEOUT(
      "connectTimeout",
      "10",
      "The timeout value in seconds used for socket connect operations."),

  /**
   * Enable automatic conversion of PostgreSQL boolean values ('t'/'f') to numeric types (1/0).
   * When enabled, calling numeric getters (getByte, getShort, getInt, getLong, getFloat, getDouble, getBigDecimal) on boolean columns
   * will convert 't' to 1 and 'f' to 0 instead of throwing a conversion exception.
   * Default is false to maintain backward compatibility.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.TYPE_HANDLING)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  CONVERT_BOOLEAN_TO_NUMERIC(
      "convertBooleanToNumeric",
      "false",
      "Enable automatic conversion of PostgreSQL boolean values ('t'/'f') to numeric types (1/0)"),

  /**
   * Specify the schema (or several schema separated by commas) to be set in the search-path. This schema will be used to resolve
   * unqualified object names used in statements over this connection.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.CONNECTION)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  CURRENT_SCHEMA(
      "currentSchema",
      null,
      "Specify the schema (or several schema separated by commas) to be set in the search-path"),

  /**
   * Specifies the maximum number of fields to be cached per connection. A value of {@code 0} disables the cache.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.METADATA)
  @PgPropertyType(PgPropertyType.Kind.INT)
  DATABASE_METADATA_CACHE_FIELDS(
      "databaseMetadataCacheFields",
      "65536",
      "Specifies the maximum number of fields to be cached per connection. A value of {@code 0} disables the cache."),

  /**
   * Specifies the maximum size (in megabytes) of fields to be cached per connection. A value of {@code 0} disables the cache.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.METADATA)
  @PgPropertyType(PgPropertyType.Kind.INT)
  DATABASE_METADATA_CACHE_FIELDS_MIB(
      "databaseMetadataCacheFieldsMiB",
      "5",
      "Specifies the maximum size (in megabytes) of fields to be cached per connection. A value of {@code 0} disables the cache."),

  /**
   * Default parameter for {@link java.sql.Statement#getFetchSize()}. A value of {@code 0} means
   * that need fetch all rows at once
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.FETCH)
  @PgPropertyType(PgPropertyType.Kind.INT)
  DEFAULT_ROW_FETCH_SIZE(
      "defaultRowFetchSize",
      "0",
      "Positive number of rows that should be fetched from the database when more rows are needed for ResultSet by each fetch iteration"),

  /**
   * Enable optimization that disables column name sanitiser.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.METADATA)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  DISABLE_COLUMN_SANITISER(
      "disableColumnSanitiser",
      "false",
      "Enable optimization that disables column name sanitiser"),

  /**
   * Specifies how the driver transforms JDBC escape call syntax into underlying SQL, for invoking procedures or functions. (backend &gt;= 11)
   * In {@code escapeSyntaxCallMode=select} mode (the default), the driver always uses a SELECT statement (allowing function invocation only).
   * In {@code escapeSyntaxCallMode=callIfNoReturn} mode, the driver uses a CALL statement (allowing procedure invocation) if there is no return parameter specified, otherwise the driver uses a SELECT statement.
   * In {@code escapeSyntaxCallMode=call} mode, the driver always uses a CALL statement (allowing procedure invocation only).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.9")
  @PgTags(PgTags.Tag.TRANSACTION)
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  ESCAPE_SYNTAX_CALL_MODE(
      "escapeSyntaxCallMode",
      "select",
      "Specifies how the driver transforms JDBC escape call syntax into underlying SQL, for invoking procedures or functions. (backend >= 11)"
          + "In escapeSyntaxCallMode=select mode (the default), the driver always uses a SELECT statement (allowing function invocation only)."
          + "In escapeSyntaxCallMode=callIfNoReturn mode, the driver uses a CALL statement (allowing procedure invocation) if there is no return parameter specified, otherwise the driver uses a SELECT statement."
          + "In escapeSyntaxCallMode=call mode, the driver always uses a CALL statement (allowing procedure invocation only).",
      false,
      new String[]{"select", "callIfNoReturn", "call"}),

  /**
   * Controls whether DDL commands (CREATE/DROP/ALTER) invalidate the
   * prepared-statement cache. When enabled (the default), the driver
   * transparently re-prepares server-side plans after DDL, so callers don't
   * see "cached plan must not change result type" after an
   * {@code ALTER TABLE} on a referenced table. Disable to keep the legacy
   * behaviour (the error is propagated and transparent recovery requires
   * {@code autosave=ALWAYS}).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.7.12")
  @PgTags(PgTags.Tag.PREPARED_STATEMENTS)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  FLUSH_CACHE_ON_DDL(
      "flushCacheOnDdl",
      "true",
      "Invalidate the prepared-statement cache when a CREATE/DROP/ALTER "
          + "CommandComplete is observed (default true). Disable for legacy "
          + "behavior that surfaces 'cached plan must not change result type'."),

  /**
   * Group startup parameters in a transaction
   * This is important in pool-by-transaction scenarios in order to make sure that all the statements
   * reaches the same connection that is being initialized. All of the startup parameters will be wrapped
   * in a transaction
   * Note this is off by default as pgbouncer in statement mode
   * @deprecated since we can send the startup parameters as a multistatment transaction
   */
  @PgApi(status = PgApi.Status.DEPRECATED, introducedIn = "42.7.0", deprecatedIn = "42.7.6")
  @PgTags(PgTags.Tag.CONNECTION)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  @Deprecated
  GROUP_STARTUP_PARAMETERS(
      "groupStartupParameters",
      "false",
      "This is important in pool-by-transaction scenarios in order to make sure that all "
          + "the statements reaches the same connection that is being initialized."
  ),

  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.17")
  @PgTags(PgTags.Tag.KERBEROS_GSS)
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  GSS_ENC_MODE(
      "gssEncMode",
      "allow",
      "Force Encoded GSS Mode",
      false,
      new String[]{"disable", "allow", "prefer", "require"}
  ),

  /**
   * Force one of
   * <ul>
   * <li>SSPI (Windows transparent single-sign-on)</li>
   * <li>GSSAPI (Kerberos, via JSSE)</li>
   * </ul>
   * to be used when the server requests Kerberos or SSPI authentication.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.KERBEROS_GSS)
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  GSS_LIB(
      "gsslib",
      "auto",
      "Force SSSPI or GSSAPI",
      false,
      new String[]{"auto", "sspi", "gssapi"}),

  /**
   * After requesting an upgrade to SSL from the server there are reports of the server not responding due to a failover
   * without a timeout here, the client can wait forever. The pattern for requesting a GSS encrypted connection is the same so we provide the same
   * timeout mechanism This timeout will be set before the request and reset after
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.5.2")
  @PgTags({PgTags.Tag.KERBEROS_GSS, PgTags.Tag.TIMEOUT})
  @PgPropertyType(PgPropertyType.Kind.DURATION_MILLIS)
  GSS_RESPONSE_TIMEOUT(
      "gssResponseTimeout",
      "5000",
      "Time in milliseconds we wait for a response from the server after requesting a GSS upgrade"),

  /**
   * Flag to enable/disable the obtaining the default GSS credentials from a pre-existing ccache,
   * rather than using JAAS.  This also allows GSS to work in environments where the default
   * kerberos principal a user has is not user@DEFAULT_REALM, but some other user (this is valid,
   * and often the case in more advanced Kerberos setups).  Finally, this also means that if
   * the "native" GSS implementation is used (i.e. the local system GSS libraries), all means of
   * fetching the default credential are supported.  Currently, JAAS is pure java on Linux, and
   * does not support the use of KCM (and only supports file-based ccaches and keytabs).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.KERBEROS_GSS)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  GSS_USE_DEFAULT_CREDS(
      "gssUseDefaultCreds",
      "false",
      "Use the default GSS credentials the process already has, rather than a JAAS login"),

  /**
   * Enable mode to filter out the names of database objects for which the current user has no privileges
   * granted from appearing in the DatabaseMetaData returned by the driver.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.METADATA)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  HIDE_UNPRIVILEGED_OBJECTS(
      "hideUnprivilegedObjects",
      "false",
      "Enable hiding of database objects for which the current user has no privileges granted from the DatabaseMetaData"),

  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.0")
  @PgTags({PgTags.Tag.FAILOVER, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.DURATION_SECONDS)
  HOST_RECHECK_SECONDS(
      "hostRecheckSeconds",
      "10",
      "Specifies period (seconds) after which the host status is checked again in case it has changed"),

  /**
   * Specifies the name of the JAAS system or application login configuration.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.KERBEROS_GSS)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  JAAS_APPLICATION_NAME(
      "jaasApplicationName",
      "pgjdbc",
      "Specifies the name of the JAAS system or application login configuration."),

  /**
   * Flag to enable/disable obtaining a GSS credential via JAAS login before authenticating.
   * Useful if setting system property javax.security.auth.useSubjectCredsOnly=false
   * or using native GSS with system property sun.security.jgss.native=true
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.0")
  @PgTags(PgTags.Tag.KERBEROS_GSS)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  JAAS_LOGIN(
      "jaasLogin",
      "true",
      "Login with JAAS before doing GSSAPI authentication"),

  /**
   * The Kerberos service name to use when authenticating with GSSAPI. This is equivalent to libpq's
   * PGKRBSRVNAME environment variable.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.KERBEROS_GSS)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  KERBEROS_SERVER_NAME(
      "kerberosServerName",
      null,
      "The Kerberos service name to use when authenticating with GSSAPI."),

  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.0")
  @PgTags({PgTags.Tag.FAILOVER, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  LOAD_BALANCE_HOSTS(
      "loadBalanceHosts",
      "false",
      "If disabled hosts are connected in the given order. If enabled hosts are chosen randomly from the set of suitable candidates"),

  /**
   * If this is set then the client side will bind to this address. This is useful if you need
   * to choose which interface to connect to.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.NETWORK)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  LOCAL_SOCKET_ADDRESS(
      "localSocketAddress",
      null,
      "Local Socket address, if set bind the client side of the socket to this address"),

  /**
   * This property is no longer used by the driver and will be ignored.
   * @deprecated Logging is configured via java.util.logging.
   */
  @PgApi(status = PgApi.Status.DEPRECATED, introducedIn = "9.4", deprecatedIn = "42.0.0")
  @PgTags(PgTags.Tag.LOGGING)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  @Deprecated
  LOGGER_FILE(
      "loggerFile",
      null,
      "File name output of the Logger"),

  /**
   * This property is no longer used by the driver and will be ignored.
   * @deprecated Logging is configured via java.util.logging.
   */
  @PgApi(status = PgApi.Status.DEPRECATED, introducedIn = "9.4", deprecatedIn = "42.0.0")
  @PgTags(PgTags.Tag.LOGGING)
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  @Deprecated
  LOGGER_LEVEL(
      "loggerLevel",
      null,
      "Logger level of the driver",
      false,
      new String[]{"OFF", "DEBUG", "TRACE"}),

  /**
   * Specify how long to wait for establishment of a database connection. The timeout is specified
   * in seconds.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags({PgTags.Tag.TIMEOUT, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.DURATION_SECONDS)
  LOGIN_TIMEOUT(
      "loginTimeout",
      "0",
      "Specify how long in seconds to wait for establishment of a database connection."),

  /**
   * Whether to include full server error detail in exception messages.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.24")
  @PgTags({PgTags.Tag.LOGGING, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  LOG_SERVER_ERROR_DETAIL(
      "logServerErrorDetail",
      "true",
      "Include full server error detail in exception messages. If disabled then only the error itself will be included."),

  /**
   * When connections that are not explicitly closed are garbage collected, log the stacktrace from
   * the opening of the connection to trace the leak source.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags({PgTags.Tag.LOGGING, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  LOG_UNCLOSED_CONNECTIONS(
      "logUnclosedConnections",
      "false",
      "When connections that are not explicitly closed are garbage collected, log the stacktrace from the opening of the connection to trace the leak source"),

  /**
   * Specifies size of buffer during fetching result set. Can be specified as specified size or
   * percent of heap memory.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.10")
  @PgTags(PgTags.Tag.FETCH)
  @PgPropertyType(PgPropertyType.Kind.SIZE_EXPRESSION)
  MAX_RESULT_BUFFER(
      "maxResultBuffer",
      null,
      "Specifies size of buffer during fetching result set. Can be specified as specified size or percent of heap memory."),

  /**
   * Maximum amount of bytes buffered before sending to the backend, default is 8192.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.7.4")
  @PgTags(PgTags.Tag.NETWORK)
  @PgPropertyType(PgPropertyType.Kind.SIZE_BYTES)
  MAX_SEND_BUFFER_SIZE(
      "maxSendBufferSize",
      "8192",
      "Maximum amount of bytes buffered before sending to the backend"),

  /**
   * Specify 'options' connection initialization parameter.
   * The value of this parameter may contain spaces and other special characters or their URL representation.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.CONNECTION)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  OPTIONS(
      "options",
      null,
      "Specify 'options' connection initialization parameter."),

  /**
   * Password to use when authenticating.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags({PgTags.Tag.CONNECTION, PgTags.Tag.AUTHENTICATION})
  @PgPropertyType(PgPropertyType.Kind.STRING)
  PASSWORD(
      "password",
      null,
      "Password to use when authenticating.",
      false),

  /**
   * Algorithm for the PEM key.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.7.9")
  @PgTags(PgTags.Tag.SSL)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  PEM_KEY_ALGORITHM("pemKeyAlgorithm", "RSA", "Algorithm of the PEM key"),

  /**
   * Database name to connect to (may be specified directly in the JDBC URL).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.CONNECTION)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  PG_DBNAME(
      "PGDBNAME",
      null,
      "Database name to connect to (may be specified directly in the JDBC URL)",
      true),

  /**
   * Hostname of the PostgreSQL server (may be specified directly in the JDBC URL).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.CONNECTION)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  PG_HOST(
      "PGHOST",
      "localhost",
      "Hostname of the PostgreSQL server (may be specified directly in the JDBC URL)",
      false),

  /**
   * Port of the PostgreSQL server (may be specified directly in the JDBC URL).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.CONNECTION)
  @PgPropertyType(PgPropertyType.Kind.INT)
  PG_PORT(
      "PGPORT",
      "5432",
      "Port of the PostgreSQL server (may be specified directly in the JDBC URL)"),

  /**
   * Specifies which mode is used to execute queries to database: simple means ('Q' execute, no parse, no bind, text mode only),
   * extended means always use bind/execute messages, extendedForPrepared means extended for prepared statements only,
   * extendedCacheEverything means use extended protocol and try cache every statement (including Statement.execute(String sql)) in a query cache.
   *
   * <p>This mode is meant for debugging purposes and/or for cases when extended protocol cannot be used (e.g. logical replication protocol)</p>
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.1.2")
  @PgTags(PgTags.Tag.PREPARED_STATEMENTS)
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  PREFER_QUERY_MODE(
      "preferQueryMode",
      "extended",
      "Specifies which mode is used to execute queries to database: simple means ('Q' execute, no parse, no bind, text mode only), "
          + "extended means always use bind/execute messages, extendedForPrepared means extended for prepared statements only, "
          + "extendedCacheEverything means use extended protocol and try cache every statement (including Statement.execute(String sql)) in a query cache.", false,
      new String[]{"extended", "extendedForPrepared", "extendedCacheEverything", "simple"}),

  /**
   * Specifies the maximum number of entries in cache of prepared statements. A value of {@code 0}
   * disables the cache.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.PREPARED_STATEMENTS)
  @PgPropertyType(PgPropertyType.Kind.INT)
  PREPARED_STATEMENT_CACHE_QUERIES(
      "preparedStatementCacheQueries",
      "256",
      "Specifies the maximum number of entries in per-connection cache of prepared statements. A value of {@code 0} disables the cache."),

  /**
   * Specifies the maximum size (in megabytes) of the prepared statement cache. A value of {@code 0}
   * disables the cache.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.PREPARED_STATEMENTS)
  @PgPropertyType(PgPropertyType.Kind.INT)
  PREPARED_STATEMENT_CACHE_SIZE_MIB(
      "preparedStatementCacheSizeMiB",
      "5",
      "Specifies the maximum size (in megabytes) of a per-connection prepared statement cache. A value of {@code 0} disables the cache."),

  /**
   * Sets the default threshold for enabling server-side prepare. A value of {@code -1} stands for
   * forceBinary
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.PREPARED_STATEMENTS)
  @PgPropertyType(PgPropertyType.Kind.INT)
  PREPARE_THRESHOLD(
      "prepareThreshold",
      "5",
      "Statement prepare threshold. A value of {@code -1} stands for forceBinary"),

  /**
   * Force use of a particular protocol version when connecting, if set, disables protocol version
   * fallback.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.COMPATIBILITY)
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  PROTOCOL_VERSION(
      "protocolVersion",
      "3",
      "Force use of a particular protocol version when connecting, currently only version 3 is supported.",
      false,
      new String[]{"3"}),

  /**
   * Parameter for {@link java.sql.Statement#getQueryTimeout()}. A value of {@code 0} means no timeout.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags({PgTags.Tag.TIMEOUT, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.DURATION_SECONDS)
  QUERY_TIMEOUT(
      "queryTimeout",
      "0",
      "The timeout value in seconds that the driver will wait for a query to execute."),

  /**
   * Quote returning columns.
   * There are some ORM's that quote everything, including returning columns
   * If we quote them, then we end up sending ""colname"" to the backend
   * which will not be found
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.7.9")
  @PgTags(PgTags.Tag.METADATA)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  QUOTE_RETURNING_IDENTIFIERS(
    "quoteReturningIdentifiers",
    "true",
    "Quote identifiers provided in returning array",
      false),
  /**
   * Puts this connection in read-only mode.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.TRANSACTION)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  READ_ONLY(
      "readOnly",
      "false",
      "Puts this connection in read-only mode"),

  /**
   * Connection parameter to control behavior when
   * {@link Connection#setReadOnly(boolean)} is set to {@code true}.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.15")
  @PgTags(PgTags.Tag.TRANSACTION)
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  READ_ONLY_MODE(
      "readOnlyMode",
      "transaction",
      "Controls the behavior when a connection is set to be read only, one of 'ignore', 'transaction', or 'always' "
          + "When 'ignore', setting readOnly has no effect. "
          + "When 'transaction' setting readOnly to 'true' will cause transactions to BEGIN READ ONLY if autocommit is 'false'. "
          + "When 'always' setting readOnly to 'true' will set the session to READ ONLY if autoCommit is 'true' "
          + "and the transaction to BEGIN READ ONLY if autocommit is 'false'.",
      false,
      new String[]{"ignore", "transaction", "always"}),

  /**
   * Socket read buffer size (SO_RECVBUF). A value of {@code -1}, which is the default, means system
   * default.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.NETWORK)
  @PgPropertyType(PgPropertyType.Kind.SIZE_BYTES)
  RECEIVE_BUFFER_SIZE(
      "receiveBufferSize",
      "-1",
      "Socket read buffer size"),

  /**
   * Connection parameter passed in the startup message. This parameter accepts two values; "true"
   * and "database". Passing "true" tells the backend to go into walsender mode, wherein a small set
   * of replication commands can be issued instead of SQL statements. Only the simple query protocol
   * can be used in walsender mode. Passing "database" as the value instructs walsender to connect
   * to the database specified in the dbname parameter, which will allow the connection to be used
   * for logical replication from that database.
   *
   * <p>Parameter should be use together with {@link PGProperty#ASSUME_MIN_SERVER_VERSION} with
   * parameter &gt;= 9.4 (backend &gt;= 9.4)</p>
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.REPLICATION)
  @PgPropertyType(PgPropertyType.Kind.STRING)
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
   * Comma-separated list of acceptable authentication methods.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.7.0")
  @PgTags({PgTags.Tag.AUTHENTICATION, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.STRING)
  REQUIRE_AUTH(
      "requireAuth",
      null,
      "Comma-separated list of acceptable authentication methods. "
      + "Use '!' prefix to reject methods (e.g., '!password' to reject cleartext). "
      + "Supported: password, md5, gss, sspi, scram-sha-256, none",
      false,
      new String[]{"password", "md5", "gss", "sspi", "scram-sha-256", "none",
                   "!password", "!md5", "!gss", "!sspi", "!scram-sha-256", "!none"}
  ),

  /**
   * Configure optimization to enable batch insert re-writing.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.1")
  @PgTags(PgTags.Tag.BATCH)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  REWRITE_BATCHED_INSERTS(
      "reWriteBatchedInserts",
      "false",
      "Enable optimization to rewrite and collapse compatible INSERT statements that are batched."),

  /**
   * Maximum number of PBKDF2 iterations the client will accept from the server during SCRAM
   * authentication. If the server advertises more iterations than this value, authentication
   * is rejected before the expensive PBKDF2 computation runs. This mitigates a denial-of-service
   * vector where a malicious or compromised server forces the client to burn CPU on an
   * attacker-controlled iteration count. Must be a non-negative integer. Defaults to 100000. Raise
   * only if you know you are connecting to a trusted server that legitimately uses a higher
   * iteration count. A value of zero disables this check.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.7.11")
  @PgTags({PgTags.Tag.AUTHENTICATION, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.INT)
  SCRAM_MAX_ITERATIONS(
      "scramMaxIterations",
      "100000",
      "Maximum PBKDF2 iteration count accepted from the server during SCRAM authentication. A value of zero disables this check."),

  /**
   * Socket write buffer size (SO_SNDBUF). A value of {@code -1}, which is the default, means system
   * default.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.NETWORK)
  @PgPropertyType(PgPropertyType.Kind.SIZE_BYTES)
  SEND_BUFFER_SIZE(
      "sendBufferSize",
      "-1",
      "Socket write buffer size"),

  /**
   * Service name to use for additional parameters. It specifies a service name in "pg_service
   * .conf" that holds additional connection parameters. This allows applications to specify only
   * a service name so connection parameters can be centrally maintained.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.0")
  @PgTags(PgTags.Tag.CONNECTION)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  SERVICE(
      "service",
      null,
      "Service name to be searched in pg_service.conf resource"),

  /**
   * Socket factory used to create socket. A null value, which is the default, means system default.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.NETWORK)
  @PgPropertyType(PgPropertyType.Kind.CLASS)
  SOCKET_FACTORY(
      "socketFactory",
      null,
      "Specify a socket factory for socket creation"),

  /**
   * The String argument to give to the constructor of the Socket Factory.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.NETWORK)
  @PgPropertyType(PgPropertyType.Kind.STRING)
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
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags({PgTags.Tag.TIMEOUT, PgTags.Tag.NETWORK, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.DURATION_SECONDS)
  SOCKET_TIMEOUT(
      "socketTimeout",
      "0",
      "The timeout value in seconds max(2147484) used for socket read operations."),

  /**
   * Control use of SSL: empty or {@code true} values imply {@code sslmode==verify-full}
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.SSL)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  SSL(
      "ssl",
      null,
      "Control use of SSL (any non-null value causes SSL to be required)"),

  /**
   * File containing the SSL Certificate. Default will be the file {@code postgresql.crt} in {@code
   * $HOME/.postgresql} (*nix) or {@code %APPDATA%\postgresql} (windows).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.SSL)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  SSL_CERT(
      "sslcert",
      null,
      "The location of the client's SSL certificate"),

  /**
   * Classname of the SSL Factory to use (instance of {@link javax.net.ssl.SSLSocketFactory}).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.SSL)
  @PgPropertyType(PgPropertyType.Kind.CLASS)
  SSL_FACTORY(
      "sslfactory",
      "org.postgresql.ssl.LibPQFactory",
      "Provide a SSLSocketFactory class when using SSL."),

  /**
   * The String argument to give to the constructor of the SSL Factory.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.SSL)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  SSL_FACTORY_ARG(
      "sslfactoryarg",
      null,
      "Argument forwarded to constructor of SSLSocketFactory class."),

  /**
   * Classname of the SSL HostnameVerifier to use (instance of {@link javax.net.ssl.HostnameVerifier}).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.SSL)
  @PgPropertyType(PgPropertyType.Kind.CLASS)
  SSL_HOSTNAME_VERIFIER(
      "sslhostnameverifier",
      null,
      "A class, implementing javax.net.ssl.HostnameVerifier that can verify the server"),

  /**
   * File containing the SSL Key. Default will be the file {@code postgresql.pk8} in {@code $HOME/.postgresql} (*nix)
   * or {@code %APPDATA%\postgresql} (windows).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.SSL)
  @PgPropertyType(PgPropertyType.Kind.STRING)
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
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4-1200")
  @PgTags({PgTags.Tag.SSL, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  SSL_MODE(
      "sslmode",
      null,
      "Parameter governing the use of SSL",
      false,
      new String[]{"disable", "allow", "prefer", "require", "verify-ca", "verify-full"}),

  /**
   * Normally a GSS connection is attempted first. If this is set to {@code direct}
   * then the GSS connection attempt will not be made
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.7.4")
  @PgTags(PgTags.Tag.SSL)
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  SSL_NEGOTIATION(
      "sslNegotiation",
      "postgres",
      "This option controls whether the driver will perform its protocol\n"
              + "negotiation to request encryption from the server or will just\n"
              + "directly make a standard SSL connection. Traditional PostgreSQL\n"
              + "protocol negotiation is the default and the most flexible with\n"
              + "different server configurations. If the server is known to support\n"
              + "direct SSL connections then the latter requires one\n"
              + "fewer round trip reducing connection latency and also allows the use\n"
              + "of protocol agnostic SSL network tools.",
      false,
      new String[]{"postgres", "direct"}),

  /**
   * The SSL password to use in the default CallbackHandler.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.SSL)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  SSL_PASSWORD(
      "sslpassword",
      null,
      "The password for the client's ssl key (ignored if sslpasswordcallback is set)"),

  /**
   * The classname instantiating {@link javax.security.auth.callback.CallbackHandler} to use.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.SSL)
  @PgPropertyType(PgPropertyType.Kind.CLASS)
  SSL_PASSWORD_CALLBACK(
      "sslpasswordcallback",
      null,
      "A class, implementing javax.security.auth.callback.CallbackHandler that can handle PasswordCallback for the ssl password."),

  /**
   * After requesting an upgrade to SSL from the server there are reports of the server not responding due to a failover
   * without a timeout here, the client can wait forever. This timeout will be set before the request and reset after
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.5.2")
  @PgTags({PgTags.Tag.SSL, PgTags.Tag.TIMEOUT})
  @PgPropertyType(PgPropertyType.Kind.DURATION_MILLIS)
  SSL_RESPONSE_TIMEOUT(
      "sslResponseTimeout",
      "5000",
      "Time in milliseconds we wait for a response from the server after requesting SSL upgrade"),

  /**
   * File containing the root certificate when validating server ({@code sslmode} = {@code
   * verify-ca} or {@code verify-full}). Default will be the file {@code root.crt} in {@code
   * $HOME/.postgresql} (*nix) or {@code %APPDATA%\postgresql} (windows).
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.SSL)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  SSL_ROOT_CERT(
      "sslrootcert",
      null,
      "The location of the root certificate for authenticating the server."),

  /**
   * Specifies the name of the SSPI service class that forms the service class part of the SPN. The
   * default, {@code POSTGRES}, is almost always correct.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.KERBEROS_GSS)
  @PgPropertyType(PgPropertyType.Kind.STRING)
  SSPI_SERVICE_CLASS(
      "sspiServiceClass",
      "POSTGRES",
      "The Windows SSPI service class for SPN"),

  /**
   * Bind String to either {@code unspecified} or {@code varchar}. Default is {@code varchar} for
   * 8.0+ backends.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.TYPE_HANDLING)
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  STRING_TYPE(
      "stringtype",
      null,
      "The type to bind String parameters as (usually 'varchar', 'unspecified' allows implicit casting to other types)",
      false,
      new String[]{"unspecified", "varchar"}),

  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.2.0")
  @PgTags({PgTags.Tag.FAILOVER, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.ENUM)
  TARGET_SERVER_TYPE(
      "targetServerType",
      "any",
      "Specifies what kind of server to connect",
      false,
      new String []{"any", "primary", "master", "slave", "secondary", "preferSlave", "preferSecondary", "preferPrimary"}),

  /**
   * Enable or disable TCP keep-alive. The default is {@code false}.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags({PgTags.Tag.NETWORK, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  TCP_KEEP_ALIVE(
      "tcpKeepAlive",
      "false",
      "Enable or disable TCP keep-alive. The default is {@code false}."),

  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags({PgTags.Tag.NETWORK, PgTags.Tag.OPERATIONS})
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  TCP_NO_DELAY(
      "tcpNoDelay",
      "true",
      "Enable or disable TCP no delay. The default is (@code true}."
  ),
  /**
   * Specifies the length to return for types of unknown length.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.TYPE_HANDLING)
  @PgPropertyType(PgPropertyType.Kind.INT)
  UNKNOWN_LENGTH(
      "unknownLength",
      Integer.toString(Integer.MAX_VALUE),
      "Specifies the length to return for types of unknown length"),

  /**
   * Username to connect to the database as.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags({PgTags.Tag.CONNECTION, PgTags.Tag.AUTHENTICATION})
  @PgPropertyType(PgPropertyType.Kind.STRING)
  USER(
      "user",
      null,
      "Username to connect to the database as.",
      true),

  /**
   * Use SPNEGO in SSPI authentication requests.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "9.4")
  @PgTags(PgTags.Tag.KERBEROS_GSS)
  @PgPropertyType(PgPropertyType.Kind.BOOLEAN)
  USE_SPNEGO(
      "useSpnego",
      "false",
      "Use SPNEGO in SSPI authentication requests"),

  /**
   * Factory class to instantiate factories for XML processing.
   * The default factory disables external entity processing.
   * Legacy behavior with external entity processing can be enabled by specifying a value of LEGACY_INSECURE.
   * Or specify a custom class that implements {@link org.postgresql.xml.PGXmlFactoryFactory}.
   */
  @PgApi(status = PgApi.Status.STABLE, introducedIn = "42.7.8")
  @PgTags(PgTags.Tag.TYPE_HANDLING)
  @PgPropertyType(PgPropertyType.Kind.CLASS)
  XML_FACTORY_FACTORY(
      "xmlFactoryFactory",
      "",
      "Factory class to instantiate factories for XML processing"),

  ;

  private final String name;
  private final @Nullable String defaultValue;
  private final boolean required;
  private final String description;
  @SuppressWarnings("ImmutableEnumChecker")
  private final String @Nullable [] choices;

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
  }

  private static final Map<String, PGProperty> PROPS_BY_NAME = new HashMap<>();

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
   * Returns the value of the connection parameter from the given {@link Properties} or the
   * default value.
   *
   * @param properties properties to take actual value from
   * @return evaluated value for this connection parameter
   */
  public @Nullable String getOrDefault(Properties properties) {
    return properties.getProperty(name, defaultValue);
  }

  /**
   * Returns the value of the connection parameter from the given {@link Properties} or the
   * default value
   * @param properties properties to take actual value from
   * @return evaluated value for this connection parameter or null
   * @deprecated use {@link #getOrDefault(Properties)} instead
   */
  @Deprecated
  public @Nullable String get(Properties properties) {
    return getOrDefault(properties);
  }

  /**
   * Returns the value of the connection parameter from the given {@link Properties} or null if there
   * is no default value
   * @param properties properties object to get value from
   * @return evaluated value for this connection parameter
   */
  public @Nullable String getOrNull(Properties properties) {
    return properties.getProperty(name);
  }

  /**
   * Set the value for this connection parameter in the given {@link Properties}.
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
   * Return the boolean value for this connection parameter in the given {@link Properties}.
   *
   * @param properties properties to take actual value from
   * @return evaluated value for this connection parameter converted to boolean
   */
  public boolean getBoolean(Properties properties) {
    return Boolean.parseBoolean(getOrDefault(properties));
  }

  /**
   * Return the int value for this connection parameter in the given {@link Properties}. Prefer the
   * use of {@link #getInt(Properties)} anywhere you can throw an {@link java.sql.SQLException}.
   *
   * @param properties properties to take actual value from
   * @return evaluated value for this connection parameter converted to int
   * @throws NumberFormatException if it cannot be converted to int.
   */
  @SuppressWarnings("nullness:argument")
  public int getIntNoCheck(Properties properties) {
    String value = getOrDefault(properties);
    //noinspection ConstantConditions
    return Integer.parseInt(value);
  }

  /**
   * Return the int value for this connection parameter in the given {@link Properties}.
   *
   * @param properties properties to take actual value from
   * @return evaluated value for this connection parameter converted to int
   * @throws PSQLException if it cannot be converted to int.
   */
  @SuppressWarnings("nullness:argument")
  public int getInt(Properties properties) throws PSQLException {
    String value = getOrDefault(properties);
    try {
      //noinspection ConstantConditions
      return Integer.parseInt(value);
    } catch (NumberFormatException nfe) {
      throw new PSQLException(GT.tr("{0} parameter value must be an integer but was: {1}",
          getName(), value), PSQLState.INVALID_PARAMETER_VALUE, nfe);
    }
  }

  /**
   * Return the {@link Integer} value for this connection parameter in the given {@link Properties}.
   *
   * @param properties properties to take actual value from
   * @return evaluated value for this connection parameter converted to Integer or null
   * @throws PSQLException if unable to parse property as integer
   */
  public @Nullable Integer getInteger(Properties properties) throws PSQLException {
    String value = getOrDefault(properties);
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
   * Set the boolean value for this connection parameter in the given {@link Properties}.
   *
   * @param properties properties in which the value should be set
   * @param value boolean value for this connection parameter
   */
  public void set(Properties properties, boolean value) {
    properties.setProperty(name, Boolean.toString(value));
  }

  /**
   * Set the int value for this connection parameter in the given {@link Properties}.
   *
   * @param properties properties in which the value should be set
   * @param value int value for this connection parameter
   */
  public void set(Properties properties, int value) {
    properties.setProperty(name, Integer.toString(value));
  }

  /**
   * Test whether this property is present in the given {@link Properties}.
   *
   * @param properties set of properties to check current in
   * @return true if the parameter is specified in the given properties
   */
  public boolean isPresent(Properties properties) {
    return getSetString(properties) != null;
  }

  /**
   * Convert this connection parameter and the value read from the given {@link Properties} into a
   * {@link DriverPropertyInfo}.
   *
   * @param properties properties to take actual value from
   * @return a DriverPropertyInfo representing this connection parameter
   */
  public DriverPropertyInfo toDriverPropertyInfo(Properties properties) {
    DriverPropertyInfo propertyInfo = new DriverPropertyInfo(name, getOrDefault(properties));
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
