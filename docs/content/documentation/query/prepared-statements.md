---
title: "Server-prepared statements"
description: "How pgJDBC promotes a `PreparedStatement` to a server-prepared plan: the `prepareThreshold` counter, when binary transfer kicks in, the per-statement override, and how `autosave` and `flushCacheOnDdl` interact with cached plans."
date: 2026-05-13T00:00:00Z
draft: false
weight: 5
toc: true
last_reviewed: "2026-05-13"
aliases:
    - "/documentation/server-prepare/#server-prepared-statements/"
---

### Motivation

The PostgreSQL® server allows clients to compile SQL statements that are expected to be reused to avoid the overhead of parsing and planning the statement for every execution. This functionality is available at the SQL level via PREPARE and EXECUTE beginning with server version 7.3, and at the protocol level beginning with server version 7.4, but as Java developers we really just want to use the standard `PreparedStatement` interface.

> **NOTE**
>
> PostgreSQL® 9.2 release notes: prepared statements used to be optimized once, without any knowledge of the parameters' values. With 9.2, the planner will use specific plans regarding to the parameters sent (the query will be planned at execution), except if the query is executed several times and the planner decides that the generic plan is not too much more expensive than the specific plans.

Server side prepared statements can improve execution speed as

1. It sends just statement handle (e.g. `S_1`) instead of full SQL text
1. It enables use of binary transfer (e.g. binary int4, binary timestamps, etc); the parameters and results are much faster to parse
1. It enables the reuse server-side execution plan
1. The client can reuse result set column definition, so it does not have to receive and parse metadata on each execution

### Execution model

The driver does not jump straight to "named, cached, binary" on the
first execution. The first few `executeQuery()` / `executeUpdate()`
calls on a given SQL go through the **unnamed extended-protocol**
path (Parse + Bind + Execute, re-parsed each time, text-format
results). Once an internal counter passes `prepareThreshold`, the
driver names the statement (`S_1`, `S_2`, ...), parses it once on
the server, and from then on sends only Bind + Execute against the
cached name, and switches to binary transfer for the OIDs that
support it.

This warm-up is normal. It is also the answer to most "why was the
first run so much slower than the rest?" questions.

#### When server-prepared statements activate

The counter is tracked per **SQL text** within a `Connection`, not
per `PreparedStatement` object. Calling
`connection.prepareStatement("SELECT ?")` twice and executing each
once still counts as two executions of the same query. With the
default `prepareThreshold=5`:

- executions 1–4 use the unnamed prepared statement;
- execution 5 names the statement and sends a one-time `PARSE`;
- executions 6+ reuse the named statement, sending only
  `BIND`/`EXECUTE`.

The driver's `PGStatement.isUseServerPrepare()` returns the live
state; see the worked example at the bottom of this page for an
execution-by-execution trace.

Crucially, the named server statement is anchored to the
`Connection`, not to the `PreparedStatement` object. Calling
`PreparedStatement.close()` does **not** drop the server-side plan
or reset the counter. The most common shape that bites readers is
a per-iteration `prepareStatement` / `execute` / `close` loop on
the same SQL: it looks like five independent statements but the
fifth iteration still server-prepares, and every subsequent
iteration reuses the cached plan.

#### Why the first executions may be slower

Three things change at once when the threshold is crossed:

- **Parsing moves from per-execution to per-statement.** Before the
  threshold, each execute pays a fresh `PARSE` on the server; after
  it, parsing is a one-time cost amortized over every subsequent
  execute.
- **Result format flips from text to binary.** Until the statement
  is named, results come back as ASCII strings the driver has to
  decode. Once binary transfer activates, `int4` is 4 raw bytes,
  `timestamp` is 8, and the driver skips most of the
  string-to-Java-object pipeline (see below).
- **Server-side planning stabilizes.** PostgreSQL plans the first
  few executions of a named statement with the actual parameter
  values it sees (custom plans). After a few iterations it picks a
  parameter-independent generic plan if that is not noticeably
  worse, controlled server-side by
  [`plan_cache_mode`](https://www.postgresql.org/docs/current/runtime-config-query.html#GUC-PLAN-CACHE-MODE).
  In a default installation, latency stabilizes around execution 10
  rather than execution 5.

A benchmark that measures throughput on the first execution and then
extrapolates will overestimate the cost of a steady-state query, in
the same way a benchmark of a single class load overestimates the
cost of a JVM method call.

#### What `prepareThreshold` changes

`prepareThreshold` is the only knob that affects when the transition
happens. The relevant values:

- **`prepareThreshold > 0`** (default `5`): wait N executions
  before naming. Trade-off: the first N executes are cheaper in
  steady state (no named statement to maintain) but more expensive
  per call (Parse repeated).
- **`prepareThreshold = 1`**: name immediately. Useful when the
  application knows the statement will be hot; every execute is a
  fast Bind/Execute, but every `prepareStatement(sql)` call pays
  the one-time `PARSE` cost up front.
- **`prepareThreshold = 0`**: never name. Server-prepare is off
  entirely; binary transfer is also off, since binary transfer
  needs a named statement to anchor the column-format descriptors.
  Use this when a pooler or other middleware in the path cannot
  cope with named statements (see also the
  [PgBouncer note in `prepared-statement-cannot-change`](/documentation/troubleshooting/prepared-statement-cannot-change/#make-pgbouncer-keep-server-prepared-statements)).
- **`prepareThreshold = -1`**: a corner-case value that forces
  binary transfer for the OIDs the driver knows how to encode
  without otherwise changing the prepare path.

The threshold can be set per-URL, per-`Connection` via
`PGConnection.setPrepareThreshold(int)`, or per-statement via
`PGStatement.setPrepareThreshold(int)`. Smaller scopes override
larger ones.

#### `preferQueryMode`: the top-level switch

Everything above assumes the driver is using the extended query
protocol (Parse / Bind / Execute). The
[`preferQueryMode`](/documentation/reference/connection-properties/#prop-preferquerymode)
property selects which protocol shape the driver uses at all, and
therefore whether `prepareThreshold` even applies:

- **`extended`** (default): Parse / Bind / Execute for
  `PreparedStatement`; `prepareThreshold` controls when the
  statement is named on the server.
- **`extendedForPrepared`**: same as `extended` but only for
  explicitly prepared statements; `Statement.execute(String)`
  takes the simple-protocol path.
- **`extendedCacheEverything`**: extends the prepared-statement
  cache to *every* SQL the connection runs, including
  `Statement.execute(String)`. Mostly a diagnostic / debugging mode
  (see the [corner case below](#use-of-server-prepared-statements-for-concreatestatement)).
- **`simple`**: single-message `'Q'` execute, no Parse, no Bind,
  text format only. Disables server-prepare, binary transfer and
  parameter typing; useful when something in the path (a pooler,
  a wire-level proxy) cannot cope with the extended protocol.

#### Binary transfer and its activation

By default ([`binaryTransfer=true`](/documentation/reference/connection-properties/#prop-binarytransfer))
the driver advertises that it can both send parameters and receive
columns in PostgreSQL's binary representation for ~30 built-in
types (`int2` / `int4` / `int8`, `float4` / `float8`, `numeric`,
`uuid`, `timestamp` / `timestamptz`, `date`, `bytea`, the array
variants of those, and so on). Binary skips the ASCII detour: a 64-bit
integer is 8 wire bytes instead of up to 20 ASCII characters, plus
no `Long.parseLong` on the client.

Binary transfer **piggybacks on server-prepare**. Until the statement
is named, the driver cannot pin which OIDs will be in the result, so
it falls back to text format. Once the threshold is crossed and the
statement is named, the driver pins the per-column format from the
cached `Describe` response and binary transfer activates.

Two narrower knobs override the per-OID defaults:

- [`binaryTransferEnable`](/documentation/reference/connection-properties/#prop-binarytransferenable):
  comma-separated OID names or numbers added to the binary set.
- [`binaryTransferDisable`](/documentation/reference/connection-properties/#prop-binarytransferdisable):
  comma-separated OID names or numbers removed from the binary
  set. Wins over `binaryTransferEnable` and over the driver default.

`binaryTransfer=false` switches the driver to text-only mode
regardless of `prepareThreshold`, primarily useful for debugging.

#### Prepared statement lifetime and invalidation

A server-prepared statement lives on a single backend connection
until one of:

- **The connection closes.** All server-side state is dropped.
- **The client-side cache evicts it.** pgJDBC caps the per-connection
  cache via
  [`preparedStatementCacheQueries`](/documentation/reference/connection-properties/#prop-preparedstatementcachequeries)
  (default `256` entries) and
  [`preparedStatementCacheSizeMiB`](/documentation/reference/connection-properties/#prop-preparedstatementcachesizemib)
  (default `5` MiB). When a new statement crosses either threshold,
  the LRU entry is evicted from the cache, and a `DEALLOCATE` is
  sent to free the backend memory.
- **The application issues `DEALLOCATE ALL` / `DISCARD ALL`.** The
  driver watches the command tags and invalidates the client-side
  cache so the next execute re-parses.
- **`SET search_path` changes.** Object lookups in cached plans
  could now resolve differently. The driver detects top-level `SET`
  on `search_path` and invalidates accordingly. (Caveat: a SET
  buried inside a PL/pgSQL function or a server-side trigger is
  *not* detected; see the Corner cases section for the workaround.)
- **A schema migration invalidates the plan.** ALTER TABLE adding,
  dropping or retyping a column makes the cached plan stale. The
  next execute raises `cached plan must not change result type`
  (`SQLState 0A000`) or `prepared statement "S_X" does not exist`
  (`SQLState 26000`). See
  [Troubleshooting: cached plan must not change result type](/documentation/troubleshooting/prepared-statement-cannot-change/)
  for the recovery options (`autosave=conservative`, prepared-cache
  eviction).

In short: the cache is per-connection, per-SQL-text, soft-bounded.
A long-lived connection accumulates statements until the cache fills;
a connection from a pool stays warm for the lifetime of that pooled
slot. A backend restart (failover, OOM kill) drops the whole
catalog, surfacing as the lifetime-end errors above.

#### `autosave`: auto-rollback around invalidation errors

When a cached plan goes stale mid-transaction (the
`cached plan must not change result type` / `prepared statement "S_X" does not exist`
errors above), the failed statement poisons the whole transaction
unless something rolls back to a savepoint first. The
[`autosave`](/documentation/reference/connection-properties/#prop-autosave)
property controls whether the driver places that savepoint for you:

- **`never`** (default): no savepoint is set, no rollback is
  attempted. A stale-plan error fails the transaction as usual.
- **`conservative`**: the driver sets a savepoint before each
  query and rolls back to it only for the specific class of errors
  caused by stale prepared statements; after the rollback the
  driver re-parses and retries the statement. This is the
  recommended setting when long-lived connections (especially
  pooled ones) coexist with schema migrations.
- **`always`**: set a savepoint before every query and roll back
  to it on *any* failure. Useful when porting code that expects
  PostgreSQL to behave like databases without transaction-level
  failure semantics, but every query pays for an extra
  savepoint/release round-trip.

The companion property
[`cleanupSavepoints`](/documentation/reference/connection-properties/#prop-cleanupsavepoints)
releases the savepoint immediately after the statement succeeds,
which avoids running the backend out of shared buffers when a
long-lived `autosave` transaction issues thousands of queries.

### Activation

Previous versions of the driver used PREPARE and EXECUTE to implement server-prepared statements.  
This is supported on all server versions beginning with 7.3, but produced application-visible changes in query results, 
such as missing ResultSet metadata and row update counts. The current driver uses the V3 protocol-level equivalents 
that avoid these changes in query results. The Extended Query protocol prepares a temporary "unnamed statement". 
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
It is hard to tell which behavior is expected, however pgJDBC tries to track `search_path` changes, and it invalidates
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
feature, and it could automatically rollback and retry the statement. The behavior is controlled via `autosave` property
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


##### Example 9.3. Using server side prepared statements

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
