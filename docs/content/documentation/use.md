---
title: "Initializing the Driver"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 2
toc: true
aliases:
    - "/documentation/head/connect.html"
---

This section describes how to load and initialize the JDBC driver in your programs.

## Importing JDBC

Any source file that uses JDBC needs to import the `java.sql` package, using:

```java
import java.sql.*;
```

> **NOTE**
>
> You should not import the `org.postgresql` package unless you are using PostgreSQL® extensions to the JDBC API.

## Loading the Driver

Applications do not need to explicitly load the `org.postgresql.Driver` class because the pgJDBC driver jar supports the Java Service Provider mechanism. The driver will be loaded by the JVM when the application connects to PostgreSQL® (as long as the driver's jar file is on the classpath).

> **NOTE**
>
> Prior to Java 1.6, the driver had to be loaded by the application: either by calling `Class.forName("org.postgresql.Driver");` or by passing the driver class name as a JVM parameter `java -Djdbc.drivers=org.postgresql.Driver example.ImageViewer`

These older methods of loading the driver are still supported, but they are no longer necessary.

## Connecting to the Database

With JDBC, a database is represented by a URL (Uniform Resource Locator). With PostgreSQL®, this takes one of the following forms:

* jdbc:postgresql:database
* jdbc:postgresql:/
* jdbc:postgresql://host/database
* jdbc:postgresql://host/
* jdbc:postgresql://host:port/database
* jdbc:postgresql://host:port/

The parameters have the following meanings:

* **`host`** = The host name of the server. Defaults to `localhost` . To specify an IPv6 address your must enclose the `host` parameter with square brackets, for example: `jdbc:postgresql://[::1]:5740/accounting`

* **`port`** = The port number the server is listening on. Defaults to the PostgreSQL® standard port number (5432).

* **`database`** = The database name. The default is to connect to a database with the same name as the user name used to connect to the server.

To connect, you need to get a `Connection` instance from JDBC. To do this, you use the `DriverManager.getConnection()` method:
 `Connection db = DriverManager.getConnection(url, username, password)`

> **Important**
> 
> Any reserved characters for URLs (for example, /, :, @, (, ), [, ], &, #, =, ?, and space) that appear in any part of the connection URL must be percent encoded. see [RFC 3986](https://www.rfc-editor.org/rfc/rfc3986#section-2) for details.

### System Properties
`pgjdbc.config.cleanup.thread.ttl` (milliseconds, default: 30000). The driver has an internal cleanup thread which monitors and cleans up unclosed connections. This property sets the duration the cleanup thread will keep running if there is nothing to clean up.

### Connection Parameters

In addition to the standard connection parameters the driver supports a number of additional properties which can be used to specify additional driver behaviour specific to PostgreSQL®. These properties may be specified in either the connection
URL or an additional `Properties` object parameter to `DriverManager.getConnection` . The following examples illustrate the use of both methods to establish an SSL connection.

If a property is specified both in URL and in `Properties` object, the value from `Properties` object is ignored.

```java
String url = "jdbc:postgresql://localhost/test";
Properties props = new Properties();
props.setProperty("user", "fred");
props.setProperty("password", "secret");
props.setProperty("ssl", "true");
Connection conn = DriverManager.getConnection(url, props);

String url = "jdbc:postgresql://localhost/test?user=fred&password=secret&ssl=true";
Connection conn = DriverManager.getConnection(url);
```

* **`user`** = String
The database user on whose behalf the connection is being made.

* **`password`** = String
The database user's password.

* **`options`** = String
Specify 'options' connection initialization parameter sent to the PostgreSQL server. For example setting this to `-c statement_timeout=5min` would set the statement timeout parameter for this session to 5 minutes.
see [Client Connection Defaults](https://www.postgresql.org/docs/current/runtime-config-client.html) for details.

The value of this property may contain spaces or other special characters, and it should be properly encoded if provided in the connection URL. Spaces are considered to separate command-line arguments, unless escaped with a backslash ( `\` ); `\\` represents a literal backslash.

```java
Properties props = new Properties();
props.setProperty("options", "-c search_path=test,public,pg_catalog -c statement_timeout=90000");
Connection conn = DriverManager.getConnection(url, props);

String url = "jdbc:postgresql://localhost:5432/postgres?options=-c%20search_path=test,public,pg_catalog%20-c%20statement_timeout=90000";
Connection conn = DriverManager.getConnection(url);
```

* **`ssl (`*boolean*`)`** *Default `false`*\
Connect using SSL. The server must have been compiled with SSL support. This property does not need a value associated with it. The mere presence of it specifies an SSL connection. 
However, for compatibility with future versions, the value "true" is preferred. For more information see [Using SSL](/documentation/ssl/).\
Setting up the certificates and keys for ssl connection can be tricky see [The test documentation](https://github.com/pgjdbc/pgjdbc/blob/master/certdir/README.md) for detailed examples.

* **`sslfactory(`*String*`)`** *Default `org.postgresql.ssl.LibPQFactory`*\
The provided value is a class name to use as the `SSLSocketFactory` when establishing an SSL connection. 
For more information see the section called [Custom SSLSocketFactory](/documentation/ssl/#custom-sslsocketfactory) defaults to LibPQFactory

* **`sslfactoryarg (`*String*`)`** : (deprecated)\
This value is an optional argument to the constructor of the sslfactory class provided above. For more information see the section called [Custom SSLSocketFactory](/documentation/ssl/#custom-sslsocketfactory).

* **`sslmode (`*String*`)`** *Default `prefer`*\
possible values include `disable` , `allow` , `prefer` , `require` , `verify-ca` and `verify-full` . `require` , `allow` and `prefer` all default to a non-validating SSL factory and do not check the validity of the certificate or the host name. `verify-ca` validates the certificate, but does not verify the hostname. `verify-full` will validate that the certificate is correct and verify the host connected to has the same hostname as the certificate. Default is `prefer` Setting these will necessitate storing the server certificate on the client machine see [Configuring the client](/documentation/ssl/#configuring-the-client) for details.

* **`sslcert (`*String*`)`** *Default `defaultdir/postgresql.crt`*\
Provide the full path for the certificate file. Defaults to `defaultdir/postgresql.crt`, where defaultdir is `${user.home}/.postgresql/` in *nix systems and `%appdata%/postgresql/` on windows.\
It can be a PEM encoded X509v3 certificate

> **NOTE**
>
> This parameter is ignored when using PKCS-12 keys, since in that case the certificate is also retrieved from the same keyfile.

* **`sslkey (`*String*`)`** *Default `defaultdir/postgresql.pk8`*\
Provide the full path for the key file. Defaults to `defaultdir/postgresql.pk8`. See sslcert for defaultdir.

> **NOTE**
>
> The key file **must** be in [PKCS-12](https://en.wikipedia.org/wiki/PKCS_12) or in [PKCS-8](https://en.wikipedia.org/wiki/PKCS_8) [DER format](https://wiki.openssl.org/index.php/DER). 
 A PEM key can be converted to DER format using the openssl command: `openssl pkcs8 -topk8 -inform PEM -in postgresql.key -outform DER -out postgresql.pk8 -v1 PBE-MD5-DES`
 When you create the key the `alias` or the `name` must be *user*. The test codes uses the following to create a .p12 key `openssl pkcs12 -export -in $< -inkey $*.key -out $@ -name user -CAfile $(SERVER_CRT_DIR)root.crt -caname local -passout pass:$(P12_PASSWORD)`

PKCS-12 key files are only recognized if they have the ".p12" (42.2.9+) or the ".pfx" (42.2.16+) extension.

If your key has a password, provide it using the `sslpassword` connection parameter described below. Otherwise, you can add the flag `-nocrypt` to the above command to prevent the driver from requesting a password.

> **NOTE**
>
> The use of -v1 PBE-MD5-DES might be inadequate in environments where high level of security is needed and the key is not protected by other means (e.g. access control of the OS), 
 or the key file is transmitted in untrusted channels. We are depending on the cryptography providers provided by the java runtime. The solution documented here is known to work at the time of writing. If you have stricter security needs, please see [here](https://stackoverflow.com/questions/58488774/configure-tomcat-hibernate-to-have-a-cryptographic-provider-supporting-1-2-840-1) for a discussion of the problem and information on choosing a better cipher suite.

* **`sslrootcert (`*String*`)`** *Default `defaultdir/root.crt`*\
File name of the SSL root certificate. It can be a PEM encoded X509v3 certificate.

* **`sslhostnameverifier (`*String*`)`** *Default `org.postgresql.ssl.PGjdbcHostnameVerifier`*\
Class name of hostname verifier.

* **`sslpasswordcallback (`*String*`)`** *Default `org.postgresql.ssl.jdbc4.LibPQFactory.ConsoleCallbackHandler`*\
Class name of the SSL password provider.

* **`sslpassword (`*String*`)`** *Default `null`*\
If provided will be used by ConsoleCallbackHandler

* **`sslResponseTimeout (`*Integer*`)`** *Default `5000`*\
Time in milliseconds to wait for a response after requesting an SSL encrypted connection from the server. If this is greater than the current connectTimeout then connectTimeout will be used.


* **`protocolVersion (`*int*`)`** *Default `null`*\
The driver supports the V3 frontend/backend protocols. The V3 protocol was introduced in 7.4 and the driver will by default try to connect using the V3 protocol.

* **`loggerLevel (`*String*`)`**\
This property is no longer used by the driver and will be ignored. All logging configuration is handled by java.util.logging.

* **`loggerFile (`*String*`)`**\
This property is no longer used by the driver and will be ignored. All logging configuration is handled by java.util.logging.

* **`allowEncodingChanges (`*boolean*`)`** *Default `false`*\
When using the V3 protocol the driver monitors changes in certain server configuration parameters that should not be touched by end users. 
The `client_encoding` setting is set by the driver and should not be altered. If the driver detects a change it will abort the connection. 
There is one legitimate exception to this behaviour though, using the `COPY` command on a file residing on the server's filesystem.
The only means of specifying the encoding of this file is by altering the `client_encoding` setting. 
The JDBC team considers this a failing of the `COPY` command and hopes to provide an alternate means of specifying the encoding in the future, but for now there is this URL parameter. 
Enable this only if you need to override the client encoding when doing a copy.

* **`logUnclosedConnections (`*boolean*`)`** *Default `false`*\
Clients may leak `Connection` objects by failing to call its `close()` method. Eventually these objects will be garbage collected and the `finalize()` method will be called which will close the `Connection` 
if caller has neglected to do this himself. The usage of a finalizer is just a stopgap solution. To help developers detect and correct the source of these leaks the `logUnclosedConnections` 
URL parameter has been added. It captures a stacktrace at each `Connection` opening and if the `finalize()` method is reached without having been closed the stacktrace is printed to the log.

* **`autosave (`*String*`)`** *Default `never`*\
Specifies what the driver should do if a query fails. In `autosave=always` mode, JDBC driver sets a savepoint before each query, and rolls back to that savepoint in case of failure. 
In `autosave=never` mode (default), no savepoint dance is made ever.
In `autosave=conservative` mode, savepoint is set for each query, however the rollback is done only for rare cases like 'cached statement cannot change return type' 
or 'statement XXX is not valid' so JDBC driver rolls back and retries

* **`cleanupSavepoints (`*boolean*`)`** *Default `false`*\
Determines if the SAVEPOINT created in autosave mode is released prior to the statement. This is done to avoid running out of shared buffers on the server in the case where 1000's of queries are performed.

* **`binaryTransfer (`*boolean*`)`** *Default `true`*\
Use binary format for sending and receiving data if possible. Setting this to false disables any binary transfer.

* **`binaryTransferEnable (`*String*`)`** *Default `empty string`*\
A comma separated list of types to enable binary transfer. Either OID numbers or names.

* **`binaryTransferDisable (`*String*`)`** *Default `empty string`*\
A comma separated list of types to disable binary transfer. Either OID numbers or names.
Over-rides values in the driver default set and values set with binaryTransferEnable.

* **`databaseMetadataCacheFields (`*int*`)`** *Default `65536`*\
Specifies the maximum number of fields to be cached per connection.
A value of 0 disables the cache.

* **`databaseMetadataCacheFieldsMiB (`*int*`)`** *Default `5`*\
Specifies the maximum size (in megabytes) of fields to be cached per connection.
A value of 0 disables the cache.

* **`prepareThreshold (`*int*`)`** *Default `5`*\
Determine the number of `PreparedStatement` executions required before switching over to use server side prepared statements. 
The default is five, meaning start using server side prepared statements on the fifth execution of the same `PreparedStatement` object. 
More information on server side prepared statements is available in the section called [Server Prepared Statements](/documentation/server-prepare/#server-prepared-statements).

* **`preparedStatementCacheQueries (`*int*`)`** *Default `256`*\
Determine the number of queries that are cached in each connection. 
The default is 256, meaning if you use more than 256 different queries in `prepareStatement()` calls, the least recently used ones will be discarded. 
The cache allows application to benefit from [Server Prepared Statements](/documentation/server-prepare/#server-prepared-statements) (see `prepareThreshold` ) even if the prepared statement is closed after each execution. 
The value of 0 disables the cache. N. B. Each connection has its own statement cache.

* **`preparedStatementCacheSizeMiB (`*int*`)`** *Default `5`*\
Determine the maximum size (in mebibytes) of the prepared queries cache (see `preparedStatementCacheQueries` ).
The default is 5, meaning if you happen to cache more than 5 MiB of queries the least recently used ones will be discarded.
The main aim of this setting is to prevent `OutOfMemoryError` . The value of 0 disables the cache.

* **`preferQueryMode (`*String*`)`** *Default `extended`*\
Specifies which mode is used to execute queries to database: simple means ('Q' execute, no parse, no bind, text mode only),
extended means always use bind/execute messages, extendedForPrepared means extended for prepared statements only, endedCacheEverything means use extended protocol and try cache every statement (including Statement.execute(String sql)) 
in a query cache.
extended | extendedForPrepared | extendedCacheEverything | simple

* **`defaultRowFetchSize (`*int*`)`** *Default `0`*\
Determine the number of rows fetched in `ResultSet` by one fetch with trip to the database. 
Limiting the number of rows are fetch with each trip to the database allow avoids unnecessary memory consumption and as a consequence `OutOfMemoryError` .
The default is zero, meaning that `ResultSet` will fetch all rows at once. Must be > 0.

* **`loginTimeout (`*int*`)`** *Default `0`*\
Specify how long to wait for establishment of a database connection. The timeout is specified in seconds max(2147484).

* **`connectTimeout (`*int*`)`** *Default `10`*\
The timeout value used for socket connect operations. If connecting to the server takes longer than this value, the connection is broken. 
The timeout is specified in seconds max(2147484) and a value of zero means that it is disabled.

* **`socketTimeout (`*int*`)`** *Default `0`*\
The timeout value used for socket read operations. If reading from the server takes longer than this value, the connection is closed. 
This can be used as both a brute force global query timeout and a method of detecting network problems. The timeout is specified in seconds max(2147484) and a value of zero means that it is disabled.

* **`cancelSignalTimeout (`*int*`)`** *Default `10`*\
Cancel command is sent out of band over its own connection, so cancel message can itself get stuck. This property controls "connect timeout" and "socket timeout" used for cancel commands. 
The timeout is specified in seconds.

* **`tcpKeepAlive (`*boolean*`)`** *Default `false`*\
Enable or disable TCP keep-alive probe.

* **`tcpNoDelay (`*boolean*`)`** *Default `true`*\
Enable or disable TCP nodelay.

* **`unknownLength (`*int*`)`** *Default `Integer.MAX_VALUE`*\
Certain postgresql types such as `TEXT` do not have a well-defined length. 
When returning meta-data about these types through functions like `ResultSetMetaData.getColumnDisplaySize` and `ResultSetMetaData.getPrecision` 
we must provide a value and various client tools have different ideas about what they would like to see. 
This parameter specifies the length to return for types of unknown length.

* **`stringtype (`*String*`)`** *Default `null`*\
Specify the type to use when binding `PreparedStatement` parameters set via `setString()` . 
If `stringtype` is set to `VARCHAR` (the default), such parameters will be sent to the server as varchar parameters. 
If `stringtype` is set to `unspecified` , parameters will be sent to the server as untyped values, and the server will attempt to infer an appropriate type. 
This is useful if you have an existing application that uses `setString()` to set parameters that are actually some other type, such as integers, 
and you are unable to change the application to use an appropriate method such as `setInt()` .

* **`ApplicationName (`*String*`)`** *`Default PostgreSQL JDBC Driver`*\
Specifies the name of the application that is using the connection. 
This allows a database administrator to see what applications are connected to the server and what resources they are using through views like pg_stat_activity.

* **`kerberosServerName (`*String*`)`** *Default `postgres`*\
The Kerberos service name to use when authenticating with GSSAPI. This is equivalent to libpq's PGKRBSRVNAME environment variable and defaults to "postgres".

* **`jaasApplicationName (`*String*`)`** *Default `pgjdbc`*\
Specifies the name of the JAAS system or application login configuration.

* **`jaasLogin (`*boolean*`)`** *Default `true`*\
Specifies whether to perform a JAAS login before authenticating with GSSAPI. 
If set to `true` (the default), the driver will attempt to obtain GSS credentials using the configured JAAS login module(s) (e.g. `Krb5LoginModule` ) before authenticating. 
To skip the JAAS login, for example if the native GSS implementation is being used to obtain credentials, set this to `false` .

* **`gssEncMode (`*String*`)`** *Default `allow`*\
PostgreSQL® 12 and later now allow GSSAPI encrypted connections. This parameter controls whether to enforce using GSSAPI encryption or not. The options are `disable` , `allow` , `prefer` and `require`
  * `disable` is obvious and disables any attempt to connect using GSS encrypted mode
  * `allow` will connect in plain text then if the server requests it will switch to encrypted mode
  * `prefer` will attempt to connect in encrypted mode and fall back to plain text if it fails to acquire an encrypted connection
  * `require` attempts to connect in encrypted mode and will fail to connect if that is not possible.

* **`gsslib (`*String*`)`** *Default `auto`*\
Force either SSPI (Windows transparent single-sign-on) or GSSAPI (Kerberos, via JSSE) to be used when the server requests Kerberos or SSPI authentication. 
Permissible values are auto (default, see below), sspi (force SSPI) or gssapi (force GSSAPI-JSSE).
If this parameter is auto, SSPI is attempted if the server requests SSPI authentication, the JDBC client is running on Windows, and the Waffle libraries required for SSPI are on the CLASSPATH. 
Otherwise Kerberos/GSSAPI via JSSE is used.

  > **Note**
  >
  > This behaviour does not exactly match that of libpq, which uses Windows' SSPI libraries for Kerberos (GSSAPI) requests by default when on Windows.

  gssapi mode forces JSSE's GSSAPI to be used even if SSPI is available, matching the pre-9.4 behaviour. On non-Windows platforms or where SSPI is unavailable, forcing sspi mode will fail with a PSQLException. 

  To use SSPI with PgJDBC you must ensure that [the `waffle-jna` library](https://mvnrepository.com/artifact/com.github.waffle/waffle-jna/) and its dependencies are present on the `CLASSPATH`. pgJDBC does **not** bundle `waffle-jna` in the pgJDBC jar.

  Compatibility matrix:

  | pgjdbc      | waffle-jna |
  |-------------|------------|
  | &lt; 42.7.1 | &lt; 2.0.0 |
  | &ge; 42.7.1 | &ge; 1.0.0 |

* **`gssResponseTimeout (`*Integer*`)`** *Default `5000`*\
Time in milliseconds to wait for a response after requesting a GSS encrypted connection from the server. If this is greater than the current connectTimeout then connectTimeout will be used.
  Since: 9.4

* **`sspiServiceClass (`*String*`)`** *Default `POSTGRES`*\
Specifies the name of the Windows SSPI service class that forms the service class part of the SPN. The default, POSTGRES, is almost always correct.
See: SSPI authentication (Pg docs) Service Principal Names (MSDN), DsMakeSpn (MSDN) Configuring SSPI (Pg wiki).
This parameter is ignored on non-Windows platforms.

* **`useSpnego (`*boolean*`)`** *Default `false`*\
Use SPNEGO in SSPI authentication requests

* **`sendBufferSize (`*int*`)`** *Default* `-1`*\
Sets SO_SNDBUF on the connection stream

* **`receiveBufferSize (`*int*`)`** *Default* `-1`*\
Sets SO_RCVBUF on the connection stream

* **`readOnly (`*boolean*`)`** *Default `false`*\
Put the connection in read-only mode

* **`readOnlyMode (`*String*`)`** *Default `transaction`*\
One of `ignore`, `transaction`, or `always`.  Controls the behaviour when a connection is set to read only,
When set to `ignore` then the `readOnly` setting has no effect.  When set to `transaction` and `readOnly` is set to `true` 
and autocommit is `false` the driver will set the transaction to readonly by sending `BEGIN READ ONLY`.
When set to `always` and `readOnly` is set to `true` the session will be set to READ ONLY if autoCommit is `true`.
If autocommit is `false` the driver will set the transaction to read only by sending `BEGIN READ ONLY`.
The default the value is `transaction`

* **`disableColumnSanitiser (`*boolean*`)`** *Default `off`*\
Setting this to `true` disables column name sanitiser. The sanitiser folds columns in the ResultSet to lowercase.
The default is to sanitise the columns (off).

* **`assumeMinServerVersion (`*String*`)`** *Default `null`*\
Assume that the server is at least the given version, thus enabling to some optimization at connection time instead of
trying to be version blind.

* **`currentSchema (`*String*`)`** *Default `null`*\
Specify the schema (or several schema separated by commas) to be set in the search-path. 
This schema will be used to resolve unqualified object names used in statements over this connection.

* **`targetServerType (`*String*`)`** *Default `any`*\
Allows opening connections to only servers with required state, the allowed values are `any`, `primary`, `master`, 
`slave`, `secondary`, `preferSlave`, `preferSecondary` and `preferPrimary`.
The primary/secondary distinction is currently done by observing if the server allows writes.
The value `preferSecondary` tries to connect to secondary if any are available, otherwise allows falls back to connecting also to `primary`.
The value `preferPrimary` tries to connect to primary if it is available, otherwise allows falls back to connecting to secondaries available.

  * **N. B.** the words master and slave are being deprecated. We will silently accept them, but primary and secondary are encouraged.

* **`hostRecheckSeconds (`*int*`)`** *Default `10`*\
Controls how long in seconds the knowledge about a host state is cached in JVM wide global cache. The default value is 10 seconds.

* **`loadBalanceHosts (`*boolean*`)`** *Default `false`*\
In default mode (`disabled`) hosts are connected in the given order. If enabled hosts are chosen randomly from the set 
of suitable candidates.

* **`socketFactory (`*String*`)`** *Default `null`\
The provided value is a class name to use as the `SocketFactory` when establishing a socket connection. 
This may be used to create unix sockets instead of normal sockets. The class name specified by `socketFactory` must extend
`javax.net.SocketFactory` and be available to the driver's classloader. This class must have a zero-argument constructor,
a single-argument constructor taking a String argument, or a single-argument constructor taking a Properties argument. 
The Properties object will contain all the connection parameters. The String argument will have the value of the `socketFactoryArg`
connection parameter.

* **`socketFactoryArg (`*String*`)`** : (deprecated)\
This value is an optional argument to the constructor of the socket factory class provided above.

* **`reWriteBatchedInserts (`*boolean*`)`** *Default `false`*\
This will change batch inserts from insert into foo (col1, col2, col3) values (1, 2, 3) into insert into foo (col1, col2, col3) values (1, 2, 3), (4, 5, 6) this provides 2-3x performance improvement

* **`replication (`*String*`)`** *Default `false`*\
Connection parameter passed in the startup message. This parameter accepts two values; `true` and `database` . 
Passing `true` tells the backend to go into walsender mode, wherein a small set of replication commands can be issued instead of SQL statements. 
Only the simple query protocol can be used in walsender mode. Passing "database" as the value instructs walsender to connect to the database specified in the dbname parameter, 
which will allow the connection to be used for logical replication from that database.
Parameter should be use together with `assumeMinServerVersion` with parameter >= 9.4 (backend >= 9.4)

* **`escapeSyntaxCallMode (`*String*`)`** *Default `select`*\
Specifies how the driver transforms JDBC escape call syntax into underlying SQL, for invoking procedures or functions.
In `escapeSyntaxCallMode=select` mode (the default), the driver always uses a SELECT statement (allowing function invocation only). 
In `escapeSyntaxCallMode=callIfNoReturn` mode, the driver uses a CALL statement (allowing procedure invocation) if there
is no return parameter specified, otherwise the driver uses a SELECT statement. In `escapeSyntaxCallMode=call` mode, 
the driver always uses a CALL statement (allowing procedure invocation only). 

* **`maxResultBuffer (`*String*`)`** *Default `null`*\
Specifies size of result buffer in bytes, which can't be exceeded during reading result set. Property can be specified in two styles:
  * as size of bytes (i.e. 100, 150M, 300K, 400G, 1T);
  * as percent of max heap memory (i.e. 10p, 15pct, 20percent);
A limit during setting of property is 90% of max heap memory. All given values, which are going to be higher than the limit,
will be lowered to the limit. By default, maxResultBuffer is not set (is null), which means that reading of results will
be performed without limits.

* **`adaptiveFetch (`*boolean*`)`** *Default `false`*\
Specifies if the number of rows, fetched in `ResultSet` per request from the database, should be dynamic.
Using dynamic number of rows, computed by adaptive fetch, will attempt to use maximize the use of the buffer declared in 
`maxResultBuffer` property. Number of rows would be calculated by dividing `maxResultBuffer` size into max row size observed 
so far, rounded down. First fetch will have number of rows declared in `defaultRowFetchSize`. Number of rows can be limited
by `adaptiveFetchMinimum` and `adaptiveFetchMaximum`. Requires declaring of `maxResultBuffer` and `defaultRowFetchSize` to work.
By default, `adaptiveFetch` is `false`.

* **`adaptiveFetchMinimum (`*int*`)`** *Default `0`*\
Specifies the lowest number of rows which can be calculated by `adaptiveFetch`. Requires `adaptiveFetch` set to `true` to work.
By default, minimum of rows calculated by `adaptiveFetch` is 0.

* **`adaptiveFetchMaximum (`*int*`)`** *Default `-1`*\
Specifies the highest number of rows which can be calculated by `adaptiveFetch`. Requires `adaptiveFetch` set to `true` to work.
By default, maximum of rows calculated by `adaptiveFetch` is -1, which is understood as infinite.

* **`logServerErrorDetail (`*boolean*`)`** *Default `true`*\
Whether to include server error details in exceptions and log messages (for example inlined query parameters).
Setting to `false` will only include minimal, not sensitive messages.
By default, this is set to `true`, server error details are propagated. This may include sensitive details such as query parameters.

* **`quoteReturningIdentifiers (`*boolean*`)`** *Default `false`*\
Quote returning columns. There are some ORM's that quote everything, including returning columns
If we quote them, then we end up sending ""colname"" to the backend instead of "colname" which will not be found.

* **`authenticationPluginClassName (`*String*`)`** *Default `null`*\
Fully qualified class name of the class implementing the AuthenticationPlugin interface. If this is null, the password 
value in the connection properties will be used.

* **`useWithTimezone (`*boolean*`)`** *Default `false`*\
Map time with timezone and timestamp with timezone to `TIME_WITH_TIMEZONE` and `TIMESTAMP_WITH_TIMEZONE`

### Unix sockets

By adding junixsocket you can obtain a socket factory that works with the driver.
Code can be found [here](https://github.com/kohlschutter/junixsocket) and instructions 
[here](https://kohlschutter.github.io/junixsocket/dependency.html)

Dependencies for junixsocket are :

```xml
<dependency>
  <groupId>com.kohlschutter.junixsocket</groupId>
  <artifactId>junixsocket-core</artifactId>
  <version>2.5.1</version>
</dependency>
```

Simply add  `?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg=[path-to-the-unix-socket]` 
to the connection URL.

For many distros the default path is /var/run/postgresql/.s.PGSQL.5432

### Connection Fail-over

To support simple connection fail-over it is possible to define multiple endpoints (host and port pairs) in the connection 
url separated by commas. The driver will try once to connect to each of them in order until the connection succeeds.
If none succeeds a normal connection exception is thrown.

The syntax for the connection url is: `jdbc:postgresql://host1:port1,host2:port2/database`

The simple connection fail-over is useful when running against a high availability postgres installation that has identical 
data on each node. For example streaming replication postgres or postgres-xc cluster.

For example an application can create two connection pools.
One data source is for writes, another for reads. The write pool limits connections only to a primary node:`jdbc:postgresql://node1,node2,node3/accounting?targetServerType=primary` .

And the read pool balances connections between secondary nodes, but allows connections also to a primary if no secondaries
are available: `jdbc:postgresql://node1,node2,node3/accounting?targetServerType=preferSecondary&loadBalanceHosts=true`

If a secondary fails, all secondaries in the list will be tried first. In the case that there are no available secondaries
the primary will be tried. If all the servers are marked as "can't connect" in the cache then an attempt
will be made to connect to all the hosts in the URL, in order.
