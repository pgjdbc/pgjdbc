---
layout: default_docs
title: Server Prepared Statements
header: Chapter 9. PostgreSQL™ Extensions to the JDBC API
resource: /documentation/head/media
previoustitle: Listen / Notify
previous: listennotify.html
nexttitle: Parameter Status Messages
next: parameterstatus.html
---

### Motivation

The PostgreSQL™ server allows clients to compile sql statements that are expected
to be reused to avoid the overhead of parsing and planning the statement for every
execution. This functionality is available at the SQL level via PREPARE and EXECUTE
beginning with server version 7.3, and at the protocol level beginning with server
version 7.4, but as Java developers we really just want to use the standard
`PreparedStatement` interface.

> PostgreSQL 9.2 release notes: prepared statements used to be optimized once, without any knowledge
of the parameters' values. With 9.2, the planner will use specific plans regarding to the parameters
sent (the query will be planned at execution), except if the query is executed several times and
the planner decides that the generic plan is not too much more expensive than the specific plans.

Server side prepared statements can improve execution speed as

1. It sends just statement handle (e.g. `S_1`) instead of full SQL text
1. It enables use of binary transfer (e.g. binary int4, binary timestamps, etc); the parameters and results are much faster to parse
1. It enables the reuse server-side execution plan
1. The client can reuse result set column definition, so it does not have to receive and parse metadata on each execution

### Activation

> Previous versions of the driver used PREPARE and EXECUTE to implement
server-prepared statements.  This is supported on all server versions beginning
with 7.3, but produced application-visible changes in query results, such as
missing ResultSet metadata and row update counts. The current driver uses the V3
protocol-level equivalents which avoid these changes in query results.

The driver uses server side prepared statements **by default** when `PreparedStatement` API is used.
In order to get to server-side prepare, you need to execute the query 5 times (that can be
configured via `prepareThreshold` connection property).
An internal counter keeps track of how many times the statement has been executed and when it
reaches the threshold it will start to use server side prepared statements.

It is generally a good idea to reuse the same `PreparedStatement` object for performance reasons,
however the driver is able to server-prepare statements automatically across `connection.prepareStatement(...)` calls.

For instance:

    PreparedStatement ps = con.prepareStatement("select /*test*/ ?::int4");
    ps.setInt(1, 42);
    ps.executeQuery().close();
    ps.close();

    PreparedStatement ps = con.prepareStatement("select /*test*/ ?::int4");
    ps.setInt(1, 43);
    ps.executeQuery().close();
    ps.close();

is less efficient than

    PreparedStatement ps = con.prepareStatement("select /*test*/ ?::int4");
    ps.setInt(1, 42);
    ps.executeQuery().close();

    ps.setInt(1, 43);
    ps.executeQuery().close();

however pgjdbc can use server side prepared statements in both cases.

Note: the `Statement` object is bound to a `Connection`, and it is not a good idea to access the same
`Statement` and/or `Connection` from multiple concurrent threads (except `cancel()`, `close()`, and alike cases). It might be safer to just `close()` the statement rather than trying to cache it somehow.

Server-prepared statements consume memory both on the client and the server, so pgjdbc limits the number
of server-prepared statements per connection. It can be configured via `preparedStatementCacheQueries`
(default `256`, the number of queries known to pgjdbc), and `preparedStatementCacheSizeMiB` (default `5`,
that is the client side cache size in megabytes per connection). Only a subset of `statement cache` is
server-prepared as some of the statements might fail to reach `prepareThreshold`.

### Deactivation

There might be cases when you would want to disable use of server-prepared statements.
For instance, if you route connections through a balancer that is incompatible with server-prepared statements,
you have little choice.

You can disable usage of server side prepared statements by setting `prepareThreshold=0`

### Corner cases

#### DDL

V3 protocol avoids sending column metadata on each execution, and BIND message specifies output column format.
That creates a problem for cases like

    SELECT * FROM mytable;
    ALTER mytable ADD column ...;
    SELECT * FROM mytable;

That results in `cached plan must not change result type` error, and it causes the transaction to fail.

The recommendation is:

1. Use explicit column names in the SELECT list
1. Avoid column type alters

#### DEALLOCATE ALL, DISCARD ALL

There are explicit commands to deallocate all server side prepared statements. It would result in
the following server-side error message: `prepared statement name is invalid`.
Of course it could defeat pgjdbc, however there are cases when you need to discard statements (e.g. after lots of DDLs)

The recommendation is:

1. Use simple `DEALLOCATE ALL` and/or `DISCARD ALL` commands, avoid nesting the commands into pl/pgsql or alike. The driver does understand top-level DEALLOCATE/DISCARD commands, and it invalidates client-side cache as well
1. Reconnect. The cache is per connection, so it would get invalidated if you reconnect

#### set search_path=...

PostgreSQL allows to customize `search_path`, and it provides great power to the developer.
With great power the following case could happen:

    set search_path='app_v1';
    SELECT * FROM mytable;
    set search_path='app_v2';
    SELECT * FROM mytable; -- Does mytable mean app_v1.mytable or app_v2.mytable here?

Server side prepared statements are linked to database object IDs, so it could fetch data from "old"
`app_v1.mytable` table. It is hard to tell which behaviour is expected, however pgjdbc tries to track
`search_path` changes, and it invalidates prepare cache accordingly.

The recommendation is:

1. Avoid changing `search_path` often, as it invalidates server side prepared statements
1. Use simple `set search_path...` commands, avoid nesting the commands into pl/pgsql or alike, otherwise
pgjdbc won't be able to identify `search_path` change

#### Re-execution of failed statements

It is a pity that a single `cached plan must not change result type` could cause the whole transaction to fail.
The driver could re-execute the statement automatically in certain cases.

1. In case the transaction has not failed (e.g. the transaction did not exist before execution of
the statement that caused `cached plan...` error), then pgjdbc re-executes the statement automatically.
This makes the application happy, and avoids unnecessary errors.
1. In case the transaction is in a failed state, there's nothing to do but rollback it. pgjdbc does have
"automatic savepoint" feature, and it could automatically rollback and retry the statement. The behaviour
is controlled via `autosave` property (default `never`). The value of `conservative` would auto-rollback
for the errors related to invalid server-prepared statements.
Note: `autosave` might result in **severe** performance issues for long transactions, as PostgreSQL backend
is not optimized for the case of long transactions and lots of savepoints.

#### Replication connection

PostgreSQL replication connection does not allow to use server side prepared statements, so pgjdbc
uses simple queries in the case where `replication` connection property is activated.

#### Use of server-prepared statements for con.createStatement()

By default, pgjdbc uses server-prepared statements for `PreparedStatement` only, however you might want
to activate server side prepared statements for regular `Statement` as well. For instance, if you
execute the same statement through `con.createStatement().executeQuery(...)`, then you might improve
performance by caching the statement. Of course it is better to use `PreparedStatements` explicitly,
however the driver has an option to cache simple statements as well.

You can do that by setting `preferQueryMode` to `extendedCacheEverything`.
Note: the option is more of a diagnostinc/debugging sort, so be careful how you use it .

#### Bind placeholder datatypes

The database optimizes the execution plan for given parameter types.
Consider the below case:

    -- create table rooms (id int4, name varchar);
    -- create index name__rooms on rooms(name);
    PreparedStatement ps = con.prepareStatement("select id from rooms where name=?");
    ps.setString(1, "42");

It works as expected, however what would happen if one uses `setInt` instead?

    ps.setInt(1, 42);

Even though the result would be identical, the first variation (`setString` case) enables the database
to use index `name__rooms`, and the latter does not.
In case the database gets `42` as integer, it uses the plan like `where cast(name as int4) = ?`.

The plan has to be specific for the (`SQL text`; `parameter types`) combination, so the driver
has to invalidate server side prepared statements in case the statement is used with different
parameter types.

This gets especially painful for batch operations as you don't want to interrupt the batch
by using alternating datatypes.

The most typical case is as follows (don't ever use this in production):

    PreparedStatement ps = con.prepareStatement("select id from rooms where ...");
    if (param instanceof String) {
        ps.setString(1, param);
    } else if (param instanceof Integer) {
        ps.setInt(1, ((Integer) param).intValue());
    } else {
        // Does it really matter which type of NULL to use?
        // In fact, it does since data types specify which server-procedure to call
        ps.setNull(1, Types.INTEGER);
    }

As you might guess, `setString` vs `setNull(..., Types.INTEGER)` result in alternating datatypes,
and it forces the driver to invalidate and re-prepare server side statement.

Recommendation is to use the consistent datatype for each bind placeholder, and use the same type
for `setNull`.
Check out `org.postgresql.test.jdbc2.PreparedStatementTest.testAlternatingBindType` example for more details.

#### Debugging

In case you run into `cached plan must not change result type` or `prepared statement \"S_2\" does not exist`
the following might be helpful to debug the case.

1. Client logging. If you add `loggerLevel=TRACE&loggerFile=pgjdbc-trace.log`, you would get trace
of the messages send between the driver and the backend
1. You might check `org.postgresql.test.jdbc2.AutoRollbackTestSuite` as it verifies lots of combinations

<a name="server-prepared-statement-example"></a>
**Example 9.3. Using server side prepared statements**

```java
import java.sql.*;

public class ServerSidePreparedStatement
{

	public static void main(String args[]) throws Exception
	{
		Class.forName("org.postgresql.Driver");
		String url = "jdbc:postgresql://localhost:5432/test";
		Connection conn = DriverManager.getConnection(url,"test","");

		PreparedStatement pstmt = conn.prepareStatement("SELECT ?");

		// cast to the pg extension interface
		org.postgresql.PGStatement pgstmt = pstmt.unwrap(org.postgresql.PGStatement.class);

		// on the third execution start using server side statements
		pgstmt.setPrepareThreshold(3);

		for (int i=1; i<=5; i++)
		{
			pstmt.setInt(1,i);
			boolean usingServerPrepare = pgstmt.isUseServerPrepare();
			ResultSet rs = pstmt.executeQuery();
			rs.next();
			System.out.println("Execution: "+i+", Used server side: " + usingServerPrepare + ", Result: "+rs.getInt(1));
			rs.close();
		}

		pstmt.close();
		conn.close();
	}
}
```

Which produces the expected result of using server side prepared statements upon
the third execution.

```
Execution: 1, Used server side: false, Result: 1
Execution: 2, Used server side: false, Result: 2
Execution: 3, Used server side: true, Result: 3
Execution: 4, Used server side: true, Result: 4
Execution: 5, Used server side: true, Result: 5
```

The example shown above requires the programmer to use PostgreSQL™ specific code
in a supposedly portable API which is not ideal. Also it sets the threshold only
for that particular statement which is some extra typing if we wanted to use that
threshold for every statement. Let's take a look at the other ways to set the
threshold to enable server side prepared statements.  There is already a hierarchy
in place above a `PreparedStatement`, the `Connection` it was created from, and
above that the source of the connection be it a `Datasource` or a URL. The server
side prepared statement threshold can be set at any of these levels such that
the value will be the default for all of it's children.

```java
// pg extension interfaces
org.postgresql.PGConnection pgconn;
org.postgresql.PGStatement pgstmt;

// set a prepared statement threshold for connections created from this url
String url = "jdbc:postgresql://localhost:5432/test?prepareThreshold=3";

// see that the connection has picked up the correct threshold from the url
Connection conn = DriverManager.getConnection(url,"test","");
pgconn = conn.unwrap(org.postgresql.PGConnection.class);
System.out.println(pgconn.getPrepareThreshold()); // Should be 3

// see that the statement has picked up the correct threshold from the connection
PreparedStatement pstmt = conn.prepareStatement("SELECT ?");
pgstmt = pstmt.unwrap(org.postgresql.PGStatement.class);
System.out.println(pgstmt.getPrepareThreshold()); // Should be 3

// change the connection's threshold and ensure that new statements pick it up
pgconn.setPrepareThreshold(5);
PreparedStatement pstmt = conn.prepareStatement("SELECT ?");
pgstmt = pstmt.unwrap(org.postgresql.PGStatement.class);
System.out.println(pgstmt.getPrepareThreshold()); // Should be 5
```
