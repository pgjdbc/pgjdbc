---
title: "PostgreSQL® Extensions to the JDBC API"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 190
toc: true
aliases:
    - "/documentation/head/ext.html"
    - "/documentation/head/parameterstatus.html"
    - "/documentation/80/ext.html"
    - "/documentation/81/ext.html"
    - "/documentation/82/ext.html"
    - "/documentation/83/ext.html"
    - "/documentation/84/ext.html"
    - "/documentation/85/ext.html"
    - "/documentation/90/ext.html"
    - "/documentation/91/ext.html"
    - "/documentation/92/ext.html"
    - "/documentation/93/ext.html"
    - "/documentation/94/ext.html"
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
