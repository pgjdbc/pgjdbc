---
layout: default_docs
title: Connecting to the Database
header: Chapter 3. Initializing the Driver
resource: media
previoustitle: Loading the Driver
previous: load.html
nexttitle: Chapter 4. Using SSL
next: ssl.html
---

With JDBC, a database is represented by a URL (Uniform Resource Locator). With
PostgreSQL, this takes one of the following forms:

* jdbc:postgresql:*`database`*
* jdbc:postgresql:/
* jdbc:postgresql://*`host/database`*
* jdbc:postgresql://*`host/`*
* jdbc:postgresql://*`host:port/database`*
* jdbc:postgresql://*`host:port/`*

The parameters have the following meanings:

* *`host`*
	
	The host name of the server. Defaults to `localhost`. To specify an IPv6
	address your must enclose the `host` parameter with square brackets, for
	example:

	jdbc:postgresql://[::1]:5740/accounting

* *`port`*

	The port number the server is listening on. Defaults to the PostgreSQL™
	standard port number (5432).

* *`database`*

	The database name. The default is to connect to a database with the same name
	as the user name.

To connect, you need to get a `Connection` instance from JDBC. To do this, you use
the `DriverManager.getConnection()` method:

`Connection db = DriverManager.getConnection(url, username, password)`;

<a name="connection-parameters"></a>
## Connection Parameters

In addition to the standard connection parameters the driver supports a number
of additional properties which can be used to specify additional driver behaviour
specific to PostgreSQL™. These properties may be specified in either the connection
URL or an additional `Properties` object parameter to `DriverManager.getConnection`.
The following examples illustrate the use of both methods to establish a SSL
connection.

```java
String url = "jdbc:postgresql://localhost/test";
Properties props = new Properties();
props.setProperty("user","fred");
props.setProperty("password","secret");
props.setProperty("ssl","true");
Connection conn = DriverManager.getConnection(url, props);

String url = "jdbc:postgresql://localhost/test?user=fred&password=secret&ssl=true";
Connection conn = DriverManager.getConnection(url);
```

* **user** = String

	The database user on whose behalf the connection is being made. 

* **password** = String

	The database user's password. 

* **ssl** = boolean

	Connect using SSL. The driver must have been compiled with SSL support.
	This property does not need a value associated with it. The mere presence
	of it specifies a SSL connection. However, for compatibility with future
	versions, the value "true" is preferred. For more information see [Chapter
	4, *Using SSL*](ssl.html).
 
* **sslfactory** = String

	The provided value is a class name to use as the `SSLSocketFactory` when
	establishing a SSL connection. For more information see the section
	called [“Custom SSLSocketFactory”](ssl-factory.html). 

* **sslfactoryarg** (deprecated) = String

	This value is an optional argument to the constructor of the sslfactory
	class provided above. For more information see the section called [“Custom SSLSocketFactory”](ssl-factory.html). 

* **sslmode** = String

	possible values include `disable`, `allow`, `prefer`, `require`, `verify-ca` and `verify-full`
	. `require`, `allow` and `prefer` all default to a non validating SSL factory and do not check the
	validity of the certificate or the host name. `verify-ca` validates the certificate, but does not
	verify the hostname. `verify-full`  will validate that the certificate is correct and verify the
	host connected to has the same hostname as the certificate.

	Setting these will necessitate storing the server certificate on the client machine see
	["Configuring the client"](ssl-client.html) for details.

* **sslcert** = String

	Provide the full path for the certificate file. Defaults to /defaultdir/postgresql.crt

	*Note:* defaultdir is ${user.home}/.postgresql/ in *nix systems and %appdata%/postgresql/ on windows 

* **sslkey** = String

	Provide the full path for the key file. Defaults to /defaultdir/postgresql.pk8

* **sslrootcert** = String

	File name of the SSL root certificate. Defaults to /defaultdir/root.crt

* **sslcrlfile** = String

    File name of the CRL file. Defaults to /defaultdir/root.crl

* **sslhostnameverifier** = String

	Class name of hostname verifier. Defaults to using `org.postgresql.ssl.PGjdbcHostnameVerifier`

* **sslpasswordcallback** = String

	Class name of the SSL password provider. Defaults to `org.postgresql.ssl.jdbc4.LibPQFactory.ConsoleCallbackHandler`

* **sslpassword** = String

	If provided will be used by ConsoleCallbackHandler

* **sendBufferSize** = int

	Sets SO_SNDBUF on the connection stream

* **recvBufferSize** = int

	Sets SO_RCVBUF on the connection stream

* **protocolVersion** = int

	The driver supports the V3 frontend/backend protocols. The V3 protocol was introduced in 7.4 and
	the driver will by default try to	connect using the V3 protocol.
 
* **loggerLevel** = String

	Logger level of the driver. Allowed values: <code>OFF</code>, <code>DEBUG</code> or <code>TRACE</code>.
	This enable the <code>java.util.logging.Logger</code> Level of the driver based on the following mapping
	of levels: DEBUG -&gt; FINE, TRACE -&gt; FINEST. This property is intended for debug the driver and
	not for general SQL query debug.

* **loggerFile** = String

	File name output of the Logger. If set, the Logger will use a <code>java.util.logging.FileHandler</code>
	to write to a specified file. If the parameter is not set or the file can’t be created the
	<code>java.util.logging.ConsoleHandler</code> will be used instead. This parameter should be use
	together with loggerLevel.
 
* **allowEncodingChanges** = boolean

	When using the V3 protocol the driver monitors changes in certain server
	configuration parameters that should not be touched by end users. The
	`client_encoding` setting is set by the driver and should not be altered.
	If the driver detects a change it will abort the connection. There is
	one legitimate exception to this behaviour though, using the `COPY` command
	on a file residing on the server's filesystem. The only means of specifying
	the encoding of this file is by altering the `client_encoding` setting.
	The JDBC team considers this a failing of the `COPY` command and hopes to
	provide an alternate means of specifying the encoding in the future, but
	for now there is this URL parameter. Enable this only if you need to
	override the client encoding when doing a copy.

* **logUnclosedConnections** = boolean

	Clients may leak `Connection` objects by failing to call its `close()`
	method. Eventually these objects will be garbage collected and the
	`finalize()` method will be called which will close the `Connection` if
	caller has neglected to do this himself. The usage of a finalizer is just
	a stopgap solution. To help developers detect and correct the source of
	these leaks the `logUnclosedConnections` URL parameter has been added.
	It captures a stacktrace at each `Connection` opening and if the `finalize()`
	method is reached without having been closed the stacktrace is printed
	to the log.
	
* **autosave** = String

    Specifies what the driver should do if a query fails. In `autosave=always` mode, JDBC driver sets a savepoint before each query,
    and rolls back to that savepoint in case of failure. In `autosave=never` mode (default), no savepoint dance is made ever.
    In `autosave=conservative` mode, savepoint is set for each query, however the rollback is done only for rare cases
    like 'cached statement cannot change return type' or 'statement XXX is not valid' so JDBC driver rollsback and retries

    The default is `never` 

* **binaryTransferEnable** = String

	A comma separated list of types to enable binary transfer. Either OID numbers or names.

* **binaryTransferDisable** = String

	A comma separated list of types to disable binary transfer. Either OID numbers or names.
	Overrides values in the driver default set and values set with binaryTransferEnable.

* **prepareThreshold** = int

	Determine the number of `PreparedStatement` executions required before
	switching over to use server side prepared statements. The default is
	five, meaning start using server side prepared statements on the fifth
	execution of the same `PreparedStatement` object. More information on
	server side prepared statements is available in the section called
	[“Server Prepared Statements”](server-prepare.html).

* **preparedStatementCacheQueries** = int

	Determine the number of queries that are cached in each connection.
	The default is 256, meaning if you use more than 256 different queries
	in `prepareStatement()` calls, the least recently used ones
	will be discarded. The cache allows application to benefit from 
	[“Server Prepared Statements”](server-prepare.html)
	(see `prepareThreshold`) even if the prepared statement is
	closed after each execution. The value of 0 disables the cache.

	N.B.Each connection has its own statement cache.

* **preparedStatementCacheSizeMiB** = int

	Determine the maximum size (in mebibytes) of the prepared queries cache
	(see `preparedStatementCacheQueries`).
	The default is 5, meaning if you happen to cache more than 5 MiB of queries
	the least recently used ones will be discarded.
	The main aim of this setting is to prevent `OutOfMemoryError`.
	The value of 0 disables the cache.

* **preferQueryMode** = String

    Specifies which mode is used to execute queries to database: simple means ('Q' execute, no parse, no bind, text mode only), 
    extended means always use bind/execute messages, extendedForPrepared means extended for prepared statements only, 
    extendedCacheEverything means use extended protocol and try cache every statement 
    (including Statement.execute(String sql)) in a query cache.
    extended | extendedForPrepared | extendedCacheEverything | simple

    The default is extended

* **defaultRowFetchSize** = int

	Determine the number of rows fetched in `ResultSet`
	by one fetch with trip to the database. Limiting the number of rows are fetch with 
	each trip to the database allow avoids unnecessary memory consumption 
	and as a consequence `OutOfMemoryException`.

	The default is zero, meaning that in `ResultSet` will be fetch all rows at once. 
	Negative number is not available.

* **loginTimeout** = int

	Specify how long to wait for establishment of a database connection. The
	timeout is specified in seconds. 

* **connectTimeout** = int

	The timeout value used for socket connect operations. If connecting to the server
	takes longer than this value, the connection is broken. 
	The timeout is specified in seconds and a value of zero means that it 	is disabled.

* **socketTimeout** = int

	The timeout value used for socket read operations. If reading from the
	server takes longer than this value, the connection is closed. This can
	be used as both a brute force global query timeout and a method of
	detecting network problems. The timeout is specified in seconds and a
	value of zero means that it is disabled.

* **cancelSignalTimeout** = int

  Cancel command is sent out of band over its own connection, so cancel message can itself get
  stuck. This property controls "connect timeout" and "socket timeout" used for cancel commands.
  The timeout is specified in seconds. Default value is 10 seconds.


* **tcpKeepAlive** = boolean

	Enable or disable TCP keep-alive probe. The default is `false`.

* **unknownLength** = int

	Certain postgresql types such as `TEXT` do not have a well defined length.
	When returning meta-data about these types through functions like
	`ResultSetMetaData.getColumnDisplaySize` and `ResultSetMetaData.getPrecision`
	we must provide a value and various client tools have different ideas
	about what they would like to see. This parameter specifies the length
	to return for types of unknown length.

* **stringtype** = String

	Specify the type to use when binding `PreparedStatement` parameters set
	via `setString()`. If `stringtype` is set to `VARCHAR` (the default), such
	parameters will be sent to the server as varchar parameters. If `stringtype`
	is set to `unspecified`, parameters will be sent to the server as untyped
	values, and the server will attempt to infer an appropriate type. This
	is useful if you have an existing application that uses `setString()` to
	set parameters that are actually some other type, such as integers, and
	you are unable to change the application to use an appropriate method
	such as `setInt()`.

* **kerberosServerName** = String

	The Kerberos service name to use when authenticating with GSSAPI. This
	is equivalent to libpq's PGKRBSRVNAME environment variable and defaults
	to "postgres".

* **jaasApplicationName** = String

	Specifies the name of the JAAS system or application login configuration.

* **jaasLogin** = boolean

	Specifies whether to perform a JAAS login before authenticating with GSSAPI.
	If set to `true` (the default), the driver will attempt to obtain GSS credentials
	using the configured JAAS login module(s) (e.g. `Krb5LoginModule`) before
	authenticating. To skip the JAAS login, for example if the native GSS
	implementation is being used to obtain credentials, set this to `false`.

* **ApplicationName** = String

	Specifies the name of the application that is using the connection. 
	This allows a database administrator to see what applications are 
	connected to the server and what resources they are using through views like pg_stat_activity.

* **gsslib** = String

	Force either SSPI (Windows transparent single-sign-on) or GSSAPI (Kerberos, via JSSE)
	to be used when the server requests Kerberos or SSPI authentication. 
	Permissible values are auto (default, see below), sspi (force SSPI) or gssapi (force GSSAPI-JSSE).

	If this parameter is auto, SSPI is attempted if the server requests SSPI authentication, 
	the JDBC client is running on Windows, and the Waffle libraries required 
	for SSPI are on the CLASSPATH. Otherwise Kerberos/GSSAPI via JSSE is used. 
	Note that this behaviour does not exactly match that of libpq, which uses 
	Windows' SSPI libraries for Kerberos (GSSAPI) requests by default when on Windows.

	gssapi mode forces JSSE's GSSAPI to be used even if SSPI is available, matching the pre-9.4 behaviour.

	On non-Windows platforms or where SSPI is unavailable, forcing sspi mode will fail with a PSQLException.

	Since: 9.4

* **sspiServiceClass** = String

	Specifies the name of the Windows SSPI service class that forms the service 
	class part of the SPN. The default, POSTGRES, is almost always correct.

	See: SSPI authentication (Pg docs) Service Principal Names (MSDN), DsMakeSpn (MSDN) Configuring SSPI (Pg wiki).

	This parameter is ignored on non-Windows platforms.

* **useSpnego** = boolean

	Use SPNEGO in SSPI authentication requests

* **sendBufferSize** = int

	Sets SO_SNDBUF on the connection stream

* **receiveBufferSize** = int

	Sets SO_RCVBUF on the connection stream

* **readOnly** = boolean

	Put the connection in read-only mode

* **disableColumnSanitiser** = boolean

	Setting this to true disables column name sanitiser. 
	The sanitiser folds columns in the resultset to lowercase. 
	The default is to sanitise the columns (off).

* **assumeMinServerVersion** = String

	Assume that the server is at least the given version, 
	thus enabling to some optimization at connection time instead of trying to be version blind.

* **currentSchema** = String

	Specify the schema to be set in the search-path. 
	This schema will be used to resolve unqualified object names used in statements over this connection.

* **targetServerType** = String

	Allows opening connections to only servers with required state, 
	the allowed values are any, master, slave, secondary, preferSlave and preferSecondary. 
	The master/slave distinction is currently done by observing if the server allows writes. 
	The value preferSecondary tries to connect to secondary if any are available, 
	otherwise allows falls back to connecting also to master.

* **hostRecheckSeconds** = int

	Controls how long in seconds the knowledge about a host state 
	is cached in JVM wide global cache. The default value is 10 seconds.

* **loadBalanceHosts** = boolean

	In default mode (disabled) hosts are connected in the given order. 
	If enabled hosts are chosen randomly from the set of suitable candidates.

* **socketFactory** = String

	The provided value is a class name to use as the `SocketFactory` when establishing a socket connection. 
	This may be used to create unix sockets instead of normal sockets. The class name specified by `socketFactory` 
	must extend `javax.net.SocketFactory` and be available to the driver's classloader.
	This class must have a zero argument constructor or a single argument constructor taking a String argument. 
	This argument may optionally be supplied by `socketFactoryArg`.

* **socketFactoryArg** (deprecated) = String

	This value is an optional argument to the constructor of the socket factory
	class provided above. 

* **reWriteBatchedInserts** = boolean

	This will change batch inserts from insert into foo (col1, col2, col3) values (1,2,3) into 
	insert into foo (col1, col2, col3) values (1,2,3), (4,5,6) this provides 2-3x performance improvement

* **replication** = String

   Connection parameter passed in the startup message. This parameter accepts two values; "true"
   and `database`. Passing `true` tells the backend to go into walsender mode, wherein a small set
   of replication commands can be issued instead of SQL statements. Only the simple query protocol
   can be used in walsender mode. Passing "database" as the value instructs walsender to connect
   to the database specified in the dbname parameter, which will allow the connection to be used
   for logical replication from that database. <p>Parameter should be use together with 
   `assumeMinServerVersion` with parameter >= 9.4 (backend >= 9.4)</p>
    
    
<a name="connection-failover"></a>
## Connection Fail-over

To support simple connection fail-over it is possible to define multiple endpoints
(host and port pairs) in the connection url separated by commas.
The driver will try to once connect to each of them in order until the connection succeeds. 
If none succeed, a normal connection exception is thrown.

The syntax for the connection url is:

`jdbc:postgresql://host1:port1,host2:port2/database`

The simple connection fail-over is useful when running against a high availability 
postgres installation that has identical data on each node. 
For example streaming replication postgres or postgres-xc cluster.

For example an application can create two connection pools. 
One data source is for writes, another for reads. The write pool limits connections only to master node:

`jdbc:postgresql://node1,node2,node3/accounting?targetServerType=master`.

And read pool balances connections between slaves nodes, but allows connections also to master if no slaves are available:

`jdbc:postgresql://node1,node2,node3/accounting?targetServerType=preferSlave&loadBalanceHosts=true`

If a slave fails, all slaves in the list will be tried first. If the case that there are no available slaves
the master will be tried. If all of the servers are marked as "can't connect" in the cache then an attempt
will be made to connect to all of the hosts in the URL in order.