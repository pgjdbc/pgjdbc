---
layout: default_docs
title: Parameter Status Messages
header: Chapter 9. PostgreSQL™ Extensions to the JDBC API
resource: /documentation/head/media
previoustitle: Parameter Status Messages
previous: server-prepare.html
nexttitle: Physical and Logical replication API
next: replication.html
---

# Parameter Status Messages

PostgreSQL supports server parameters, also called server variables or,
internally, Grand Unified Configuration (GUC) variables. These variables are
manipulated by the `SET` command, `postgresql.conf`, `ALTER SYSTEM SET`, `ALTER
USER SET`, `ALTER DATABASE SET`, the `set_config(...)` SQL-callable function,
etc. See [the PostgreSQL manual](https://www.postgresql.org/docs/current/config-setting.html).

For a subset of these variables the server will *automatically report changes
to the value to the client driver and application*. These variables are known
internally as `GUC_REPORT` variables after the name of the flag that enables
the functionality.

The server keeps track of all the variable scopes and reports when a variable
reverts to a prior value, so the client doesn't have to guess what the current
value is and whether some server-side function could've changed it.  Whenever
the value changes, no matter why or how it changes, the server reports the new
effective value in a *Parameter Status* protocol message to the client.  PgJDBC
uses many of these reports internally.

As of PgJDBC 42.2.6, it also exposes the parameter status information to user
applications via the PGConnection extensions interface.

## Methods

Two methods on `org.postgresql.PGConnection` provide the client interface to
reported parameters. Parameter names are case-insensitive and case-preserving.

* `Map PGConnection.getParameterStatuses()` - return a map of all reported
  parameters and their values.

* `String PGConnection.getParameterStatus()` - shorthand to retrieve one
  value by name, or null if no value has been reported.

See the `PGConnection` JavaDoc for details.

## Example

If you're working directly with a `java.sql.Connection` you can

    import org.postgresql.PGConnection;

    void my_function(Connection conn) {

        System.out.println("My application name is " +
            ((PGConnection)conn).getParameterStatus("application_name"));

    }

## Other client drivers

The `libpq` equivalent is the `PQparameterStatus(...)` API function.
