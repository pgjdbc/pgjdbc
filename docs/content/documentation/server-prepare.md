---
title: "PostgreSQL® Extensions to the JDBC API"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 8
toc: true
---

PostgreSQL® is an extensible database system. You can add your own functions to the server, which can then be called from queries, or even add your own data types. As these are facilities unique to PostgreSQL®, we support them from Java, with a set of extension APIs. Some features within the core of the standard driver actually use these extensions to implement Large Objects, etc.

## Accessing the Extensions

To access some of the extensions, you need to use some extra methods in the `org.postgresql.PGConnection` class. In this case, you would need to cast the return value of `Driver.getConnection()` . For example:

```java
Connection db = Driver.getConnection(url, username, password);
// ...
// later on
Fastpath fp = db.unwrap(org.postgresql.PGConnection.class).getFastpathAPI();
```

## Timestamp Infinity

The driver uses the following values to represent negative and positive infinity:

| type | Negative infinity | Positive infinity |
| ------- | ---------------------| --------------------- |
| `LocalDateTime` | `LocalDateTime.MIN` | `LocalDateTime.MAX` |
| `OffsetDateTime`| `OffsetDateTime.MIN` | `OffsetDateTime.MAX` |
| `java.sql.Timestamp`| when object's millisecond value equals `PGStatement.DATE_NEGATIVE_INFINITY`| when object's millisecond value equals `PGStatement.DATE_POSITIVE_INFINITY` |

#### ResultSet example

```java
java.sql.Timestamp ts = myResultSet.getTimestamp("mycol");

if (ts.getTime() == PGStatement.DATE_NEGATIVE_INFINITY) {
  // The value in the database is '-infinity'
}
if (ts.getTime() == PGStatement.DATE_POSITIVE_INFINITY) {
  // The value in the database is 'infinity'
}
```
## Geometric Data Types

PostgreSQL® has a set of data types that can store geometric features into a table. These include single points, lines, and polygons.  We support these types in Java with the org.postgresql.geometric package. Please consult the Javadoc mentioned in [Further Reading](/documentation/reading) for details of available classes and features.

##### Example 9.1. Using the CIRCLE datatype JDBC

```java
import java.sql.*;

import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGcircle;

public class GeometricTest {
    public static void main(String args[]) throws Exception {
        String url = "jdbc:postgresql://localhost:5432/test";
        try (Connection conn = DriverManager.getConnection(url, "test", "")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TEMP TABLE geomtest(mycirc circle)");
            }
            insertCircle(conn);
            retrieveCircle(conn);
        }
    }

    private static void insertCircle(Connection conn) throws SQLException {
        PGpoint center = new PGpoint(1, 2.5);
        double radius = 4;
        PGcircle circle = new PGcircle(center, radius);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO geomtest(mycirc) VALUES (?)")) {
            ps.setObject(1, circle);
            ps.executeUpdate();
        }
    }

    private static void retrieveCircle(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT mycirc, area(mycirc) FROM geomtest")) {
                while (rs.next()) {
                    PGcircle circle = (PGcircle) rs.getObject(1);
                    double area = rs.getDouble(2);

                    System.out.println("Center (X, Y) = (" + circle.center.x + ", " + circle.center.y + ")");
                    System.out.println("Radius = " + circle.radius);
                    System.out.println("Area = " + area);
                }
            }
        }
    }
}
```

## Large Objects

Large objects are supported in the standard JDBC specification. However, that
interface is limited, and the API provided by PostgreSQL® allows for random
access to the objects contents, as if it was a local file.

The org.postgresql.largeobject package provides to Java the libpq C interface's
large object API. It consists of two classes, `LargeObjectManager` , which deals
with creating, opening and deleting large objects, and `LargeObject` which deals
with an individual object.  For an example usage of this API, please see
[Processing Binary Data in JDBC](/documentation/binary-data/#example71processing-binary-data-in-jdbc).

## Listen / Notify

Listen and Notify provide a simple form of signal or interprocess communication mechanism for a collection of processes accessing the same PostgreSQL® database. For more information on notifications consult the main server documentation. This section only deals with the JDBC specific aspects of notifications.

Standard `LISTEN` , `NOTIFY` , and `UNLISTEN` commands are issued via the standard `Statement` interface. To retrieve and process retrieved notifications the `Connection` must be cast to the PostgreSQL® specific extension interface `PGConnection` . From there the `getNotifications()` method can be used to retrieve any outstanding notifications.

> **NOTE**
>
> A key limitation of the JDBC driver is that it cannot receive asynchronous notifications and must poll the backend to check if any notifications were issued. A timeout can be given to the poll function, but then the execution of statements from other threads will block.

##### Example 9.2. Receiving Notifications

```java
import java.sql.*;

public class NotificationTest {
    public static void main(String args[]) throws Exception {
        Class.forName("org.postgresql.Driver");
        String url = "jdbc:postgresql://localhost:5432/test";

        // Create two distinct connections, one for the notifier
        // and another for the listener to show the communication
        // works across connections although this example would
        // work fine with just one connection.

        Connection lConn = DriverManager.getConnection(url, "test", "");
        Connection nConn = DriverManager.getConnection(url, "test", "");

        // Create two threads, one to issue notifications and
        // the other to receive them.

        Listener listener = new Listener(lConn);
        Notifier notifier = new Notifier(nConn);
        listener.start();
        notifier.start();
    }
}

class Listener extends Thread {
    private Connection conn;
    private org.postgresql.PGConnection pgconn;

    Listener(Connection conn) throws SQLException {
        this.conn = conn;
        this.pgconn = conn.unwrap(org.postgresql.PGConnection.class);
        Statement stmt = conn.createStatement();
        stmt.execute("LISTEN mymessage");
        stmt.close();
    }

    public void run() {
        try {
            while (true) {
                org.postgresql.PGNotification notifications[] = pgconn.getNotifications();

                // If this thread is the only one that uses the connection, a timeout can be used to
                // receive notifications immediately:
                // org.postgresql.PGNotification notifications[] = pgconn.getNotifications(10000);

                for (int i = 0; i < notifications.length; i++) {
                    System.out.println("Got notification: " + notifications[i].getName());
                }

                // wait a while before checking again for new
                // notifications

                Thread.sleep(500);
            }
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        } catch (InterruptedException ie) {
            ie.printStackTrace();
        }
    }
}

class Notifier extends Thread {
    private Connection conn;

    public Notifier(Connection conn) {
        this.conn = conn;
    }

    public void run() {
        while (true) {
            try {
                Statement stmt = conn.createStatement();
                stmt.execute("NOTIFY mymessage");
                stmt.close();
                Thread.sleep(2000);
            } catch (SQLException sqle) {
                sqle.printStackTrace();
            } catch (InterruptedException ie) {
                ie.printStackTrace();
            }
        }
    }
}
```

## Server Prepared Statements

### Motivation

The PostgreSQL® server allows clients to compile sql statements that are expected to be reused to avoid the overhead of parsing and planning the statement for every execution. This functionality is available at the SQL level via PREPARE and EXECUTE beginning with server version 7.3, and at the protocol level beginning with server version 7.4, but as Java developers we really just want to use the standard `PreparedStatement` interface.

> **NOTE**
>
> PostgreSQL® 9.2 release notes: prepared statements used to be optimized once, without any knowledge of the parameters' values. With 9.2, the planner will use specific plans regarding to the parameters sent (the query will be planned at execution), except if the query is executed several times and the planner decides that the generic plan is not too much more expensive than the specific plans.

Server side prepared statements can improve execution speed as

1. It sends just statement handle (e.g. `S_1`) instead of full SQL text
1. It enables use of binary transfer (e.g. binary int4, binary timestamps, etc); the parameters and results are much faster to parse
1. It enables the reuse server-side execution plan
1. The client can reuse result set column definition, so it does not have to receive and parse metadata on each execution

### Activation

Previous versions of the driver used PREPARE and EXECUTE to implement server-prepared statements.  
This is supported on all server versions beginning with 7.3, but produced application-visible changes in query results, 
such as missing ResultSet metadata and row update counts. The current driver uses the V3 protocol-level equivalents 
which avoid these changes in query results. The Extended Query protocol prepares a temporary "unnamed statement". 
See [Extended Query](https://www.postgresql.org/docs/current/protocol-flow.html) Section 53.2.3 for details.

The driver uses the Extended Protocol **by default** when the `PreparedStatement` API is used. 

An internal counter keeps track of how many times the statement has been executed and when it reaches the `prepareThreshold` (default 5)
the driver will switch to creating a named statement and using `Prepare` and `Execute`.

It is generally a good idea to reuse the same `PreparedStatement` object for performance reasons, however the driver is able to server-prepare statements automatically across `connection.prepareStatement(...)` calls.

For instance:

```java
PreparedStatement ps = con.prepareStatement("select /*test*/ ?::int4");
ps.setInt(1, 42);
ps.executeQuery().close();
ps.close();

PreparedStatement ps = con.prepareStatement("select /*test*/ ?::int4");
ps.setInt(1, 43);
ps.executeQuery().close();
ps.close();
```

is less efficient than

```java
PreparedStatement ps = con.prepareStatement("select /*test*/ ?::int4");

ps.setInt(1, 42);
ps.executeQuery().close();

ps.setInt(1, 43);
ps.executeQuery().close();
```

however pgJDBC can use server side prepared statements in both cases.

> **Note**
>
> The `Statement` object is bound to a `Connection` , and it is not a good idea to access the same `Statement` and/or
> `Connection` from multiple concurrent threads (except `cancel()` , `close()` , and alike cases). It might be safer to
> just `close()` the statement rather than trying to cache it somehow.

Server-prepared statements consume memory both on the client and the server, so pgJDBC limits the number of server-prepared
statements per connection. It can be configured via `preparedStatementCacheQueries` (default `256` , the number of queries
known to pgJDBC), and `preparedStatementCacheSizeMiB` (default `5` , that is the client side cache size in megabytes per
connection). Only a subset of `statement cache` is server-prepared as some statements might fail to reach `prepareThreshold` .

### Deactivation

There might be cases when you would want to disable use of server-prepared statements. For instance, if you route
connections through a balancer that is incompatible with server-prepared statements, you have little choice.

You can disable usage of server side prepared statements by setting `prepareThreshold=0`

### Corner cases

#### DDL

V3 protocol avoids sending column metadata on each execution, and BIND message specifies output column format.
That creates a problem for cases like

```sql
SELECT * FROM mytable;
ALTER mytable ADD column ...;
SELECT * FROM mytable;
```

That results in `cached plan must not change result type` error, and it causes the transaction to fail.

The recommendation is:

1. Use explicit column names in the SELECT list
2. Avoid column type alters

#### DEALLOCATE ALL, DISCARD ALL

There are explicit commands to deallocate all server side prepared statements. It would result in the following server-side
error message: `prepared statement name is invalid`. Of course, it could defeat pgJDBC, however there are cases when you need
to discard statements (e.g. after lots of DDLs)

The recommendation is:

1. Use simple `DEALLOCATE ALL` and/or `DISCARD ALL` commands, avoid nesting the commands into pl/pgsql or alike. 
The driver does understand top-level DEALLOCATE/DISCARD commands, and it invalidates client-side cache as well
2. Reconnect. The cache is per connection, so it would get invalidated if you reconnect

#### set search_path = ...

PostgreSQL® allows to customize `search_path` , and it provides great power to the developer. With great power the 
following case could happen:

```sql
set search_path='app_v1';
SELECT * FROM mytable;
set search_path='app_v2';
SELECT * FROM mytable; -- Does mytable mean app_v1.mytable or app_v2.mytable here?
```

Server side prepared statements are linked to database object IDs, so it could fetch data from "old" `app_v1.mytable` table.
It is hard to tell which behaviour is expected, however pgJDBC tries to track `search_path` changes, and it invalidates
prepare cache accordingly.

The recommendation is:

1. Avoid changing `search_path` often, as it invalidates server side prepared statements
2. Use simple `set search_path...` commands, avoid nesting the commands into pl/pgsql or alike, otherwise pgJDBC won't
be able to identify `search_path` change

#### Re-execution of failed statements

It is a pity that a single `cached plan must not change result type` could cause the whole transaction to fail. The driver
could re-execute the statement automatically in certain cases.

1. In case the transaction has not failed (e.g. the transaction did not exist before execution of the statement that caused
`cached plan...` error), then pgJDBC re-executes the statement automatically. This makes the application happy, and avoids
unnecessary errors.
2. In case the transaction is in a failed state, there's nothing to do but rollback it. pgJDBC does have "automatic savepoint"
feature, and it could automatically rollback and retry the statement. The behaviour is controlled via `autosave` property
(default `never` ). The value of `conservative` would auto-rollback for the errors related to invalid server-prepared statements.

> **Note**
>
> `autosave` might result in **severe** performance issues for long transactions, as PostgreSQL® backend is not optimized
> for the case of long transactions and lots of savepoints.

#### Replication connection

PostgreSQL® replication connection does not allow to use server side prepared statements, so pgJDBC
uses simple queries in the case where `replication` connection property is activated.

#### Use of server-prepared statements for con.createStatement()

By default, pgJDBC uses server-prepared statements for `PreparedStatement` only, however you might want
to activate server side prepared statements for regular `Statement` as well. For instance, if you
execute the same statement through `con.createStatement().executeQuery(...)` , then you might improve
performance by caching the statement. Of course, it is better to use `PreparedStatements` explicitly,
however the driver has an option to cache simple statements as well.

You can do that by setting `preferQueryMode` to `extendedCacheEverything`.

> **Note**
>
> the option is more of a diagnostic/debugging sort, so be careful how you use it .

#### Bind placeholder datatypes

The database optimizes the execution plan for given parameter types.
Consider the below case:

```sql
-- create table rooms (id int4, name varchar);
-- create index name__rooms on rooms(name);
PreparedStatement ps = con.prepareStatement("select id from rooms where name=?");
ps.setString(1, "42");
```

It works as expected, however what would happen if one uses `setInt` instead? `ps.setInt(1, 42);`

Even though the result would be identical, the first variation ( `setString` case) enables the database to use index
`name__rooms` , and the latter does not. In case the database gets `42` as integer, it uses the plan like `where cast(name as int4) = ?`.

The plan has to be specific for the ( `SQL text` ; `parameter types` ) combination, so the driver has to invalidate
server side prepared statements in case the statement is used with different parameter types.

This gets especially painful for batch operations as you don't want to interrupt the batch by using alternating datatypes.

The most typical case is as follows (don't ever use this in production):

```java
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
```

As you might guess, `setString` vs `setNull(..., Types.INTEGER)` result in alternating datatypes,
and it forces the driver to invalidate and re-prepare server side statement.

Recommendation is to use the consistent datatype for each bind placeholder, and use the same type
for `setNull` .
Check out `org.postgresql.test.jdbc2.PreparedStatementTest.testAlternatingBindType` example for more details.

#### Debugging

In case you run into `cached plan must not change result type` or `prepared statement \"S_2\" does not exist` the
following might be helpful to debug the case.

1. Client logging. If you add `loggerLevel=TRACE&loggerFile=pgjdbc-trace.log`, you would get trace
of the messages send between the driver and the backend
1. You might check `org.postgresql.test.jdbc2.AutoRollbackTest` as it verifies lots of combinations

##### Client Logging
Logging is now configured using `java.util.logging`. Create a logging.properties file in resources similar to:

```java
handlers=java.util.logging.FileHandler
.level= INFO

java.util.logging.FileHandler.level=FINEST
java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
java.util.logging.FileHandler.pattern=/tmp/debug.log

java.util.logging.ConsoleHandler.level = INFO
java.util.logging.ConsoleHandler.formatter = java.util.logging.SimpleFormatter
org.postgresql.level = FINEST
```
Which can be loaded using:
```java
        LogManager.getLogManager().readConfiguration(YourClass.class.getResourceAsStream("/logging.properties"));

```


##### Example 9.3. Using server side prepared statements

```java
import java.sql.*;

public class ServerSidePreparedStatement {

    public static void main(String args[]) throws Exception {
        
        String url = "jdbc:postgresql://localhost:5432/test";
        try (Connection conn = DriverManager.getConnection(url, "test", "")){

            try (PreparedStatement pstmt = conn.prepareStatement("SELECT ?")){

                // cast to the pg extension interface
                org.postgresql.PGStatement pgstmt = pstmt.unwrap(org.postgresql.PGStatement.class);

                // on the third execution start using server side statements
                pgstmt.setPrepareThreshold(3);

                for (int i = 1; i <= 5; i++) {
                    pstmt.setInt(1, i);
                    boolean usingServerPrepare = pgstmt.isUseServerPrepare();
                    ResultSet rs = pstmt.executeQuery();
                    rs.next();
                    System.out.println("Execution: " + i + ", Used server side: " + usingServerPrepare + ", Result: " + rs.getInt(1));
                    rs.close();
                }
            }        
        }
    }
}
```

Which produces the expected result of using server side prepared statements upon
the third execution.

|Execution|Used server side|Result|
|---|---|---|
|1|false|1|
|2|false|2|
|3|true|3|
|4|true|4|
|5|true|5|

The example shown above requires the programmer to use PostgreSQL® specific code in a supposedly portable API which is not ideal.
Also it sets the threshold only for that particular statement which is some extra typing if we wanted to use that threshold for
every statement. Let's take a look at the other ways to set the threshold to enable server side prepared statements.
There is already a hierarchy in place above a `PreparedStatement` , the `Connection` it was created from, and above that
the source of the connection be it a `Datasource` or a URL. The server side prepared statement threshold can be set at any
of these levels such that the value will be the default for all of its children.

```java
// pg extension interfaces
org.postgresql.PGConnection pgconn;
org.postgresql.PGStatement pgstmt;

// set a prepared statement threshold for connections created from this url
String url = "jdbc:postgresql://localhost:5432/test?prepareThreshold=3";

// see that the connection has picked up the correct threshold from the url
Connection conn = DriverManager.getConnection(url, "test", "");
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

## Parameter Status Messages

PostgreSQL® supports server parameters, also called server variables or, internally, Grand Unified Configuration (GUC) variables.
These variables are manipulated by the `SET` command, `postgresql.conf` , `ALTER SYSTEM SET` , `ALTER USER SET`, ` ALTER DATABASE SET `,
the `set_config(...)` SQL-callable function, etc. See [The PostgreSQL manual](https://www.postgresql.org/docs/current/config-setting.html).

For a subset of these variables the server will *automatically report changes to the value to the client driver and application*.
These variables are known internally as `GUC_REPORT` variables after the name of the flag that enables the functionality.

The server keeps track of all the variable scopes and reports when a variable reverts to a prior value, so the client doesn't
have to guess what the current value is and whether some server-side function could've changed it.  Whenever the value changes,
no matter why or how it changes, the server reports the new effective value in a *Parameter Status* protocol message to the client.
pgJDBC uses many of these reports internally.

As of pgJDBC 42.2.6, it also exposes the parameter status information to user applications via the PGConnection extensions interface.

## Methods

Two methods on `org.postgresql.PGConnection` provide the client interface to reported parameters. Parameter names are
case-insensitive and case-preserving.

* `Map PGConnection.getParameterStatuses()` - return a map of all reported parameters and their values.

* `String PGConnection.getParameterStatus()` - shorthand to retrieve one value by name, or null if no value has been reported.

See the `PGConnection` JavaDoc for details.

## Example

If you're working directly with a `java.sql.Connection` you can

```java
import org.postgresql.PGConnection;

void my_function(Connection conn) {
    System.out.println("My application name is " + ((PGConnection) conn).getParameterStatus("application_name"));
}
```

## Other client drivers

The `libpq` equivalent is the `PQparameterStatus(...)` API function.

## Physical and Logical replication API

Postgres 9.4 (released in December 2014) introduced a new feature called logical replication. Logical replication allows
changes from a database to be streamed in real-time to an external system. The difference between physical replication and
logical replication is that logical replication sends data over in a logical format whereas physical replication sends data
over in a binary format. Additionally logical replication can send over a single table, or database. Binary replication
replicates the entire cluster in an all or nothing fashion; which is to say there is no way to get a specific table or
database using binary replication

Prior to logical replication keeping an external system synchronized in real time was problematic. The application would
have to update/invalidate the appropriate cache entries, reindex the data in your search engine, send it to your analytics
system, and so on.

This suffers from race conditions and reliability problems. For example if slightly different data gets written to two
different datastores (perhaps due to a bug or a race condition), the contents of the datastores will gradually drift
apart — they will become more and more inconsistent over time. Recovering from such gradual data corruption is difficult.

Logical decoding takes the database’s write-ahead log (WAL), and gives us access to row-level change events:
every time a row in a table is inserted, updated or deleted, that’s an event. Those events are grouped by transaction,
and appear in the order in which they were committed to the database. Aborted/rolled-back transactions
do not appear in the stream. Thus, if you apply the change events in the same order, you end up with an exact,
transactionally consistent copy of the database. It's looks like the Event Sourcing pattern that you previously implemented
in your application, but now it's available out of the box from the PostgreSQL® database.

For access to real-time changes PostgreSQL® provides the streaming replication protocol. Replication protocol can be physical
or logical. Physical replication protocol is used for Master/Secondary replication. Logical replication protocol can be used
to stream changes to an external system.

Since the JDBC API does not include replication `PGConnection` implements the PostgreSQL® API

## Configure database

Your database should be configured to enable logical or physical replication

### postgresql.conf

* Property `max_wal_senders` should be at least equal to the number of replication consumers
* Property `wal_keep_segments` should contain count wal segments that can't be removed from database.
* Property `wal_level` for logical replication should be equal to `logical`.
* Property `max_replication_slots` should be greater than zero for logical replication, because logical replication can't
 work without replication slot.

### pg_hba.conf

Enable connect user with replication privileges to replication stream.

```sql
local   replication   all                   trust
host    replication   all   127.0.0.1/32    md5
host    replication   all   ::1/128         md5
```

### Configuration for examples

*postgresql.conf*

```ini
max_wal_senders = 4             # max number of walsender processes
wal_keep_segments = 4           # in logfile segments, 16MB each; 0 disables
wal_level = logical             # minimal, replica, or logical
max_replication_slots = 4       # max number of replication slots
```

*pg_hba.conf*

```sql
# Allow replication connections from localhost, by a user with the
# replication privilege.
local   replication   all                   trust
host    replication   all   127.0.0.1/32    md5
host    replication   all   ::1/128         md5
```

## Logical replication

Logical replication uses a replication slot to reserve WAL logs on the server and also defines which decoding plugin to
use to decode the WAL logs to the required format, for example you can decode changes as json, protobuf, etc. To demonstrate
how to  use the pgJDBC replication API we will use the `test_decoding` plugin that is included in the `postgresql-contrib`
package, but you can use your own decoding plugin. There are a few on github which can be used as examples.

In order to use the replication API, the Connection has to be created in replication mode, in this mode the connection
is not available to execute SQL commands, and can only be used with replication API. This is a restriction imposed by PostgreSQL®.

##### Example 9.4. Create replication connection.

```java
String url = "jdbc:postgresql://localhost:5432/postgres";
Properties props = new Properties();
PGProperty.USER.set(props, "postgres");
PGProperty.PASSWORD.set(props, "postgres");
PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "9.4");
PGProperty.REPLICATION.set(props, "database");
PGProperty.PREFER_QUERY_MODE.set(props, "simple");

Connection con = DriverManager.getConnection(url, props);
PGConnection replConnection = con.unwrap(PGConnection.class);
```

The entire replication API is grouped in `org.postgresql.replication.PGReplicationConnection` and is available via
`org.postgresql.PGConnection#getReplicationAPI` .

Before you can start replication protocol, you need to have replication slot, which can be also created via pgJDBC API.

##### Example 9.5. Create replication slot via pgJDBC API

```java
replConnection.getReplicationAPI()
    .createReplicationSlot()
    .logical()
    .withSlotName("demo_logical_slot")
    .withOutputPlugin("test_decoding")
    .make();
```

Once we have the replication slot, we can create a ReplicationStream.

##### Example 9.6. Create logical replication stream.

```java
PGReplicationStream stream =
    replConnection.getReplicationAPI()
        .replicationStream()
        .logical()
        .withSlotName("demo_logical_slot")
        .withSlotOption("include-xids", false)
        .withSlotOption("skip-empty-xacts", true)
        .start();
```

The replication stream will send all changes since the creation of the replication slot or from replication slot
restart LSN if the slot was already used for replication. You can also start streaming changes from a particular LSN position,
in that case LSN position should be specified when you create the replication stream.

##### Example 9.7. Create logical replication stream from particular position.

```java
LogSequenceNumber waitLSN = LogSequenceNumber.valueOf("6F/E3C53568");

PGReplicationStream stream =
    replConnection.getReplicationAPI()
        .replicationStream()
        .logical()
        .withSlotName("demo_logical_slot")
        .withSlotOption("include-xids", false)
        .withSlotOption("skip-empty-xacts", true)
        .withStartPosition(waitLSN)
        .start();
```

Via `withSlotOption` we also can specify options that will be sent to our output plugin, this allows the user to customize decoding.
For example, I have my own output plugin that has a property `sensitive=true` which will include changes by sensitive columns to change
event.

##### Example 9.8. Example output with include-xids=true

```sql
BEGIN 105779
table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'previous value'
COMMIT 105779
```

##### Example 9.9. Example output with include-xids=false

```sql
BEGIN
table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'previous value'
COMMIT
```

During replication the database and consumer periodically exchange ping messages. When the database or client do not receive
ping message within the configured timeout, replication has been deemed to have stopped and an exception will be thrown and
the database will free resources. In PostgreSQL® the ping timeout is configured by the property `wal_sender_timeout`
(default = 60 seconds). Replication stream in pgjdc can be configured to send feedback(ping) when required or by time interval.
It is recommended to send feedback(ping) to the database more often than configured `wal_sender_timeout` . In production systems
I use value equal to `wal_sender_timeout / 3` . It's avoids a potential problems with networks and changes to be
streamed without disconnects by timeout. To specify the feedback interval use `withStatusInterval` method.

##### Example 9.10. Replication stream with configured feedback interval equal to 20 sec

```java
PGReplicationStream stream =
    replConnection.getReplicationAPI()
        .replicationStream()
        .logical()
        .withSlotName("demo_logical_slot")
        .withSlotOption("include-xids", false)
        .withSlotOption("skip-empty-xacts", true)
        .withStatusInterval(20, TimeUnit.SECONDS)
        .start();
```

After create `PGReplicationStream` , it's time to start receive changes in real-time.

Changes can be received from stream as blocking( `org.postgresql.replication.PGReplicationStream#read` ) or as
non-blocking (`org.postgresql.replication.PGReplicationStream#readPending` ).
Both methods receive changes as a `java.nio.ByteBuffer` with the payload from the send output plugin. We can't receive
part of message, only the full message that was sent by the output plugin. ByteBuffer contains message in format that is
defined by the decoding output plugin, it can be simple String, json, or whatever the plugin determines. That's why
pgJDBC returns the raw ByteBuffer instead of making assumptions.

##### Example 9.11. Example send message from output plugin.

```java
OutputPluginPrepareWrite(ctx, true);
appendStringInfo(ctx->out, "BEGIN %u", txn->xid);
OutputPluginWrite(ctx, true);
```

**Example 9.12. Receive changes via replication stream.**

```java
while (true) {
    //non blocking receive message
    ByteBuffer msg = stream.readPending();

    if (msg == null) {
        TimeUnit.MILLISECONDS.sleep(10 L);
        continue;
    }

    int offset = msg.arrayOffset();
    byte[] source = msg.array();
    int length = source.length - offset;
    System.out.println(new String(source, offset, length));
}
```

As mentioned previously, replication stream should periodically send feedback to the database to prevent disconnect via
timeout. Feedback is automatically sent when `read` or `readPending` are called if it's time to send feedback. Feedback
can also be sent via `org.postgresql.replication.PGReplicationStream#forceUpdateStatus()` regardless of the timeout. Another
important duty of feedback is to provide the  server with the Logical Sequence Number (LSN) that has been successfully received
and applied to consumer, it is necessary for monitoring and to truncate/archive WAL's that that are no longer needed. In the
event that replication has been restarted, it's will start from last successfully processed LSN that was sent via feedback to database.

The API provides the following feedback mechanism to indicate the successfully applied LSN by the current consumer. LSN's
before this can be truncated or archived. `org.postgresql.replication.PGReplicationStream#setFlushedLSN` and
`org.postgresql.replication.PGReplicationStream#setAppliedLSN` . You always can get last receive LSN via
`org.postgresql.replication.PGReplicationStream#getLastReceiveLSN` .

##### Example 9.13. Add feedback indicating a successfully process LSN

```java
while (true) {
    //Receive last successfully send to queue message. LSN ordered.
    LogSequenceNumber successfullySendToQueue = getQueueFeedback();
    if (successfullySendToQueue != null) {
        stream.setAppliedLSN(successfullySendToQueue);
        stream.setFlushedLSN(successfullySendToQueue);
    }

    //non blocking receive message
    ByteBuffer msg = stream.readPending();

    if (msg == null) {
        TimeUnit.MILLISECONDS.sleep(10 L);
        continue;
    }

    asyncSendToQueue(msg, stream.getLastReceiveLSN());
}
```

##### Example 9.14. Full example of logical replication

```java
String url = "jdbc:postgresql://localhost:5432/test";
Properties props = new Properties();
PGProperty.USER.set(props, "postgres");
PGProperty.PASSWORD.set(props, "postgres");
PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "9.4");
PGProperty.REPLICATION.set(props, "database");
PGProperty.PREFER_QUERY_MODE.set(props, "simple");

Connection con = DriverManager.getConnection(url, props);
PGConnection replConnection = con.unwrap(PGConnection.class);

replConnection.getReplicationAPI()
    .createReplicationSlot()
    .logical()
    .withSlotName("demo_logical_slot")
    .withOutputPlugin("test_decoding")
    .make();

//some changes after create replication slot to demonstrate receive it
sqlConnection.setAutoCommit(true);
Statement st = sqlConnection.createStatement();
st.execute("insert into test_logic_table(name) values('first tx changes')");
st.close();

st = sqlConnection.createStatement();
st.execute("update test_logic_table set name = 'second tx change' where pk = 1");
st.close();

st = sqlConnection.createStatement();
st.execute("delete from test_logic_table where pk = 1");
st.close();

PGReplicationStream stream =
    replConnection.getReplicationAPI()
    .replicationStream()
    .logical()
    .withSlotName("demo_logical_slot")
    .withSlotOption("include-xids", false)
    .withSlotOption("skip-empty-xacts", true)
    .withStatusInterval(20, TimeUnit.SECONDS)
    .start();

while (true) {
    //non blocking receive message
    ByteBuffer msg = stream.readPending();

    if (msg == null) {
        TimeUnit.MILLISECONDS.sleep(10 L);
        continue;
    }

    int offset = msg.arrayOffset();
    byte[] source = msg.array();
    int length = source.length - offset;
    System.out.println(new String(source, offset, length));

    //feedback
    stream.setAppliedLSN(stream.getLastReceiveLSN());
    stream.setFlushedLSN(stream.getLastReceiveLSN());
}
```

Where output looks like this, where each line is a separate message.

```sql
BEGIN
table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'first tx changes'
COMMIT
BEGIN
table public.test_logic_table: UPDATE: pk[integer]:1 name[character varying]:'second tx change'
COMMIT
BEGIN
table public.test_logic_table: DELETE: pk[integer]:1
COMMIT
```

## Physical replication

API for physical replication looks like the API for logical replication. Physical replication does not require a replication
slot. And ByteBuffer will contain the binary form of WAL logs. The binary WAL format is a very low level API, and can change
from version to version. That is why replication between different major PostgreSQL® versions is not possible. But physical
replication can contain many important data, that is not available via logical replication. That is why pgJDBC contains an
implementation for both.

**Example 9.15. Use physical replication**

```java
LogSequenceNumber lsn = getCurrentLSN();

Statement st = sqlConnection.createStatement();
st.execute("insert into test_physic_table(name) values('previous value')");
st.close();

PGReplicationStream stream =
    pgConnection
    .getReplicationAPI()
    .replicationStream()
    .physical()
    .withStartPosition(lsn)
    .start();

ByteBuffer read = stream.read();
```

## Arrays

PostgreSQL® provides robust support for array data types as column types, function arguments
and criteria in where clauses. There are several ways to create arrays with pgJDBC.

The [java.sql. Connection.createArrayOf(String, Object\[\])](https://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html#createArrayOf-java.lang.String-java.lang.Object:A-) can be used to create an [java.sql. Array](https://docs.oracle.com/javase/8/docs/api/java/sql/Array.html) from `Object[]` instances (Note: this includes both primitive and object multi-dimensional arrays).
A similar method `org.postgresql.PGConnection.createArrayOf(String, Object)` provides support for primitive array types.
The `java.sql.Array` object returned from these methods can be used in other methods, such as
[PreparedStatement.setArray(int, Array)](https://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html#setArray-int-java.sql.Array-).

The following types of arrays support binary representation in requests and can be used in `PreparedStatement.setObject`

|Java Type | Supported binary PostgreSQL® Types | Default PostgreSQL® Type|
|--- | --- | ---|
|`short[]` , `Short[]` | `int2[]` | `int2[]`|
|`int[]` , `Integer[]` | `int4[]` | `int4[]`|
|`long[]` , `Long[]` | `int8[]` | `int8[]`|
|`float[]` , `Float[]` | `float4[]` | `float4[]`|
|`double[]` , `Double[]` | `float8[]` | `float8[]`|
|`boolean[]` , `Boolean[]` | `bool[]` | `bool[]`|
|`String[]` | `varchar[]` , `text[]` | `varchar[]`|
|`byte[][]` | `bytea[]` | `bytea[]`|


## CopyManager
The driver provides an extension for accessing `COPY`. Copy is an extension that PostreSQL provides. see [Copy](https://www.postgresql.org/docs/current/sql-copy.html)

#### Example 9.15 Copying Data in
```java

/*
* DDL for code below
* create table copytest (stringvalue text, intvalue int, numvalue numeric(5,2));
*/
private static String[] origData =
            {"First Row\t1\t1.10\n",
                    "Second Row\t2\t-22.20\n",
                    "\\N\t\\N\t\\N\n",
                    "\t4\t444.40\n"};
private int dataRows = origData.length;
private String sql = "COPY copytest FROM STDIN";

try (Connection con = DriverManager.getConnection(url, "postgres", "somepassword")){
    PGConnection pgConnection = con.unwrap(org.postgresql.PGConnection.class);
    CopyManager copyAPI = pgConnection.getCopyAPI();
    CopyIn cp = copyAPI.copyIn(sql);

    for (String anOrigData : origData) {
        byte[] buf = anOrigData.getBytes();
        cp.writeToCopy(buf, 0, buf.length);
    }

    long updatedRows = cp.endCopy();
    long handledRowCount = cp.getHandledRowCount();
    System.err.println(String.format("copy Updated %d Rows, and handled %d rows", updatedRows, handledRowCount));

    int rowCount = getCount(con);
    System.err.println(rowCount);

}

``` 

#### Example 9.16 Copying Data out

```java
String sql = "COPY copytest TO STDOUT";
try (Connection con = DriverManager.getConnection(url, "postgres", "somepassword")){
    PGConnection pgConnection = con.unwrap(org.postgresql.PGConnection.class);
    CopyManager copyAPI = pgConnection.getCopyAPI();
    CopyOut cp = copyAPI.copyOut(sql);
    int count = 0;
    byte[] buf;  // This is a relatively simple example. buf will contain rows from the database

    while ((buf = cp.readFromCopy()) != null) {
        count++;
    }
    long rowCount = cp.getHandledRowCount();
}
```

More examples can be found in the [Copy Test Code](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/test/java/org/postgresql/test/jdbc2/CopyTest.java)
