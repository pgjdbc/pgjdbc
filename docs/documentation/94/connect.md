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
PostgreSQL™, this takes one of the following forms:

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

`String url = "jdbc:postgresql://localhost/test";`  
`Properties props = new Properties();`  
`props.setProperty("user","fred");`  
`props.setProperty("password","secret");`  
`props.setProperty("ssl","true");`  
`Connection conn = DriverManager.getConnection(url, props);`

`String url = "jdbc:postgresql://localhost/test?user=fred&password=secret&ssl=true";`  
`Connection conn = DriverManager.getConnection(url);`

* `user = String`

	The database user on whose behalf the connection is being made. 

* `password = String`
	
	The database user's password. 

* `ssl`

	Connect using SSL. The driver must have been compiled with SSL support.
	This property does not need a value associated with it. The mere presence
	of it specifies a SSL connection. However, for compatibility with future
	versions, the value "true" is preferred. For more information see [Chapter
	4, *Using SSL*](ssl.html).
 
* `sslfactory = String`

	The provided value is a class name to use as the `SSLSocketFactory` when
	establishing a SSL connection. For more information see the section
	called [“Custom SSLSocketFactory”](ssl-factory.html). 

* `sslfactoryarg = String`

	This value is an optional argument to the constructor of the sslfactory
	class provided above. For more information see the section called [“Custom SSLSocketFactory”](ssl-factory.html). 

* `socketFactory = String`
	Specify a socket factory for socket creation.

* `socketFactoryArg = String`
	Argument forwarded to constructor of SocketFactory class

* `compatible = String`

	Act like an older version of the driver to retain compatibility with older
	applications. At the moment this controls two driver behaviours: the
	handling of binary data fields, and the handling of parameters set via
	`setString()`.

	Older versions of the driver used this property to also control the
	protocol used to connect to the backend. This is now controlled by the
	`protocolVersion` property.

	Information on binary data handling is detailed in [Chapter 7, Storing Binary Data](binary-data.html).
	To force the use of Large Objects set the compatible property to 7.1.

	When `compatible` is set to 7.4 or below, the default for the `stringtype`
	parameter is changed to `unspecified`.

* `sendBufferSize = int` 

	Sets SO_SNDBUF on the connection stream
	
* `recvBufferSize = int`

	Sets SO_RCVBUF on the connection stream
	
* `protocolVersion = String`

	The driver supports both the V2 and V3 frontend/backend protocols. The
	V3 protocol was introduced in 7.4 and the driver will by default try to
	connect using the V3 protocol, if that fails it will fall back to the V2
	protocol. If the protocolVersion property is specified, the driver will
	try only the specified protocol (which should be either "2" or "3").
	Setting protocolVersion to "2" may be used to avoid the failed attempt
	to use the V3 protocol when connecting to a version 7.3 or earlier server,
	or to force the driver to use the V2 protocol despite connecting to a 7.4
	or greater server.
 
* `loglevel = int`

	Set the amount of logging information printed to the DriverManager's
	current value for LogStream or LogWriter. It currently supports values
	of `org.postgresql.Driver.DEBUG` (2) and `org.postgresql.Driver.INFO` (1).
	`INFO` will log very little information while `DEBUG` will produce significant
	detail. This property is only really useful if you are a developer or
	are having problems with the driver.

* `charSet = String`

	The character set to use for data sent to the database or received from
	the database. This property is only relevant for server versions less
	than or equal to 7.2. The 7.3 release was the first with multibyte support
	compiled by default and the driver uses its character set translation
	facilities instead of trying to do it itself.
 
* `allowEncodingChanges = boolean`

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

* `logUnclosedConnections = boolean`

	Clients may leak `Connection` objects by failing to call its `close()`
	method. Eventually these objects will be garbage collected and the
	`finalize()` method will be called which will close the `Connection` if
	caller has neglected to do this himself. The usage of a finalizer is just
	a stopgap solution. To help developers detect and correct the source of
	these leaks the `logUnclosedConnections` URL parameter has been added.
	It captures a stacktrace at each `Connection` opening and if the `finalize()`
	method is reached without having been closed the stacktrace is printed
	to the log.
	
* `binaryTransferEnable = String`

	A comma separated list of types to enable binary transfer. Either OID numbers or names.
	
* `binaryTransferDisable = String`

	A comma separated list of types to disable binary transfer. Either OID numbers or names.
	Overrides values in the driver default set and values set with binaryTransferEnable.

* `prepareThreshold = int`

	Determine the number of `PreparedStatement` executions required before
	switching over to use server side prepared statements. The default is
	five, meaning start using server side prepared statements on the fifth
	execution of the same `PreparedStatement` object. More information on
	server side prepared statements is available in the section called
	[“Server Prepared Statements”](server-prepare.html).
	
* `preparedStatementCacheQueries = int`

    Determine the number of queries that are cached in each connection.
    The default is 256, meaning if you use more than 256 different queries
    in prepareStatement() calls, the least recently used ones
    will be discarded. The cache allows application to benefit from 
	[“Server Prepared Statements”](server-prepare.html)
    prepareThreshold even if the prepared statement is
    closed after each execution. The value of 0 disables the cache.
    
	Each connection has its own statement cache.

* `preparedStatementCacheSizeMiB = int`

    Determine the maximum size (in mebibytes) of the prepared queries cache
    (see preparedStatementCacheQueries).
    The default is 5, meaning if you happen to cache more than 5 MiB of queries
    the least recently used ones will be discarded.
    The main aim of this setting is to prevent `OutOfMemoryError`.
    The value of 0 disables the cache.
    If a query would consume more than a half of `preparedStatementCacheSizeMiB`,
    then it is discarded immediately.

* `defaultRowFetchSize = int`

    Determine the number of rows fetched in `ResultSet`
 	by one fetch with trip to the database. Limiting the number of rows are fetch with 
 	each trip to the database allow avoids unnecessary memory consumption 
 	and as a consequence `OutOfMemoryException`
 	The default is zero, meaning that in `ResultSet`
 	will be fetch all rows at once. Negative number is not available.

* `reWriteBatchedInserts = bool`
	Enable optimization to rewrite and collapse compatible INSERT statements that are batched.
	If enabled, pgjdbc rewrites batch of `insert into ... values(?, ?)` into `insert into ... values(?, ?), (?, ?), ...`
	That reduces per-statement overhead. The drawback is if one of the statements fail, the whole batch fails.
	The default value is `false`. The option is avaliable since 9.4.1208

* `loginTimeout = int`

	Specify how long to wait for establishment of a database connection. The
	timeout is specified in seconds. 

* `connectTimeout = int`
	
	The timeout value used for socket connect operations. If connecting to the server
	takes longer than this value, the connection is broken. 
	The timeout is specified in seconds and a value of zero means that it is disabled.
	The default value is 0 (unlimited) up to 9.4.1208, and 10 seconds since 9.4.1209

* `cancelSignalTimeout = int` (since 9.4.1209)
	Cancel command is sent out of band over its own connection, so cancel message can itself get stuck.
	This property controls "connect timeout" and "socket timeout" used for cancel commands.
	The timeout is specified in seconds. Default value is 10 seconds.

* `socketTimeout = int`

	The timeout value used for socket read operations. If reading from the
	server takes longer than this value, the connection is closed. This can
	be used as both a brute force global query timeout and a method of
	detecting network problems. The timeout is specified in seconds and a
	value of zero means that it is disabled.

* `tcpKeepAlive = boolean`

	Enable or disable TCP keep-alive probe. The default is `false`.

* `unknownLength = int`

	Certain postgresql types such as `TEXT` do not have a well defined length.
	When returning meta-data about these types through functions like
	`ResultSetMetaData.getColumnDisplaySize` and `ResultSetMetaData.getPrecision`
	we must provide a value and various client tools have different ideas
	about what they would like to see. This parameter specifies the length
	to return for types of unknown length.

* `stringtype = String`

	Specify the type to use when binding `PreparedStatement` parameters set
	via `setString()`. If `stringtype` is set to `VARCHAR` (the default), such
	parameters will be sent to the server as varchar parameters. If `stringtype`
	is set to `unspecified`, parameters will be sent to the server as untyped
	values, and the server will attempt to infer an appropriate type. This
	is useful if you have an existing application that uses `setString()` to
	set parameters that are actually some other type, such as integers, and
	you are unable to change the application to use an appropriate method
	such as `setInt()`.

* `kerberosServerName = String`

	The Kerberos service name to use when authenticating with GSSAPI. This
	is equivalent to libpq's PGKRBSRVNAME environment variable and defaults
	to "postgres".

* `jaasApplicationName = String`

	Specifies the name of the JAAS system or application login configuration. 
	
*	`ApplicationName = String`
	
	Specifies the name of the application that is using the connection. 
	This allows a database administrator to see what applications are 
	connected to the server and what resources they are using through views like pg_stat_activity.

*	`gsslib = String`
	
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

*	`sspiServiceClass = String`
	
	Specifies the name of the Windows SSPI service class that forms the service 
	class part of the SPN. The default, POSTGRES, is almost always correct.

	See: SSPI authentication (Pg docs) Service Principal Names (MSDN), DsMakeSpn (MSDN) Configuring SSPI (Pg wiki).

	This parameter is ignored on non-Windows platforms.

*	`useSpnego = boolean`
	
	Use SPNEGO in SSPI authentication requests

*	`sendBufferSize = int`  
	
	Sets SO_SNDBUF on the connection stream

*	`receiveBufferSize = int`  
	
	Sets SO_RCVBUF on the connection stream

*	`readOnly = boolean`
	
	Put the connection in read-only mode

*	`disableColumnSanitiser = boolean`
	
	Enable optimization that disables column name sanitiser.

*	`assumeMinServerVersion = String`
	
	Assume that the server is at least the given version, 
	thus enabling to some optimization at connection time instead of trying to be version blind.

*	`currentSchema = String`
	
	Specify the schema to be set in the search-path. 
	This schema will be used to resolve unqualified object names used in statements over this connection.

*	`targetServerType`
	
	Allows opening connections to only servers with required state, 
	the allowed values are any, master, slave and preferSlave. 
	The master/slave distinction is currently done by observing if the server allows writes. 
	The value preferSlave tries to connect to slaves if any are available, 
	otherwise allows falls back to connecting also to master.

*	`hostRecheckSeconds = int`

	Controls how long in seconds the knowledge about a host state 
	is cached in JVM wide global cache. The default value is 10 seconds.

*	`loadBalanceHosts = boolean`
	
	In default mode (disabled) hosts are connected in the given order. 
	If enabled hosts are chosen randomly from the set of suitable candidates.

	<a name="connection-failover"></a>
	## Connection Fail-over

	To support simple connection fail-over it is possible to define multiple endpoints
	(host and port pairs) in the connection url separated by commas.
	The driver will try to once connect to each of them in order until the connection succeeds. 
	If none succeed, a normal connection exception is thrown.

	The syntax for the connection url is:

	jdbc:postgresql://host1:port1,host2:port2/database

	The simple connection fail-over is useful when running against a high availability 
	postgres installation that has identical data on each node. 
	For example streaming replication postgres or postgres-xc cluster.

	For example an application can create two connection pools. 
	One data source is for writes, another for reads. The write pool limits connections only to master node:

	jdbc:postgresql://node1,node2,node3/accounting?targetServerType=master
	. And read pool balances connections between slaves nodes, but allows connections also to master if no slaves are available:

	jdbc:postgresql://node1,node2,node3/accounting?targetServerType=preferSlave&loadBalanceHosts=true
