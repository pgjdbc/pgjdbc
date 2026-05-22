---
title: "Parameter status"
description: "Reading `GUC_REPORT` server parameters that PostgreSQL pushes via Parameter Status protocol messages, through the `PGConnection.getParameterStatus*` extension methods added in pgJDBC 42.2.7."
date: 2026-05-13T00:00:00Z
draft: false
weight: 3
toc: true
last_reviewed: "2026-05-13"
aliases:
    - "/documentation/server-prepare/#parameter-status-messages/"
---

PostgreSQL® supports server parameters, also called server variables or, internally, Grand Unified Configuration (GUC) variables.
These variables are manipulated by the `SET` command, `postgresql.conf` , `ALTER SYSTEM SET` , `ALTER USER SET`, ` ALTER DATABASE SET `,
the `set_config(...)` SQL-callable function, etc. See [The PostgreSQL manual](https://www.postgresql.org/docs/current/config-setting.html).

For a subset of these variables the server will *automatically report changes to the value to the client driver and application*.
These variables are known internally as `GUC_REPORT` variables after the name of the flag that enables the functionality.

The server keeps track of all the variable scopes and reports when a variable reverts to a prior value, so the client doesn't
have to guess what the current value is and whether some server-side function could have changed it. Whenever the value changes,
no matter why or how it changes, the server reports the new effective value in a *Parameter Status* protocol message to the client.
pgJDBC uses many of these reports internally.

As of pgJDBC 42.2.7, it also exposes the parameter status information to user applications via the PGConnection extensions interface.

{{< review date="2026-05-22" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- 42.2.7 changelog | docs/content/changelogs/2019-09-10-42.2.7-release.md | 10-12
- CHANGELOG.md | CHANGELOG.md | 876-881
{{< /review >}}

## Methods

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGConnection.java | pgjdbc/src/main/java/org/postgresql/PGConnection.java | 315-385
- PgConnection.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgConnection.java | 1958-1966
- QueryExecutorBase.java | pgjdbc/src/main/java/org/postgresql/core/QueryExecutorBase.java | 71-73
- QueryExecutorBase.java | pgjdbc/src/main/java/org/postgresql/core/QueryExecutorBase.java | 480-488
- QueryExecutorImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java | 3095-3112
{{< /review >}}

Two methods on `org.postgresql.PGConnection` provide the client interface to reported parameters. Parameter names are
case-insensitive and case-preserving.

* `Map PGConnection.getParameterStatuses()` - return a map of all reported parameters and their values.

* `String PGConnection.getParameterStatus()` - shorthand to retrieve one value by name, or null if no value has been reported.

See the `PGConnection` JavaDoc for details.

## Example

If you're working directly with a `java.sql.Connection`, you can:

```java
import org.postgresql.PGConnection;

void my_function(Connection conn) {
    System.out.println("My application name is " + ((PGConnection) conn).getParameterStatus("application_name"));
}
```

## Other client drivers

The `libpq` equivalent is the `PQparameterStatus(...)` API function.
