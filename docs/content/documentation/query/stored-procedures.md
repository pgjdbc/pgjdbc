---
title: "Stored functions and procedures"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 20
toc: true
last_reviewed: "2026-05-22"
aliases:
    - "/documentation/callproc/"
---

PostgreSQL® supports two types of stored objects, functions that can return a result value and - starting from v11 - procedures
that can perform transaction control. Both types of stored objects are invoked using `CallableStatement` and the standard
JDBC escape call syntax `{call storedobject(?)}` . The `escapeSyntaxCallMode` connection property controls how the driver
transforms the call syntax to invoke functions or procedures.

The default mode, `select` , supports backwards compatibility for existing applications and supports function invocation
only. This is required to invoke a function returning void.

For new applications, use `escapeSyntaxCallMode=callIfNoReturn` to map `CallableStatements` with return values to stored
functions and `CallableStatements` without return values to stored procedures.

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- escapeSyntaxCallMode property | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 301-318
- EscapeSyntaxCallMode enum | pgjdbc/src/main/java/org/postgresql/jdbc/EscapeSyntaxCallMode.java | 8-18
- callIfNoReturn tests | pgjdbc/src/test/java/org/postgresql/test/jdbc3/EscapeSyntaxCallModeCallIfNoReturnTest.java | 24-85
- void function fixture | pgjdbc/src/test/java/org/postgresql/test/jdbc3/Jdbc3CallableStatementTest.java | 44-60
- void function call test | pgjdbc/src/test/java/org/postgresql/test/jdbc3/Jdbc3CallableStatementTest.java | 1077-1088
{{< /review >}}

##### Example 6.1. Calling a built-in stored function

This example shows how to call the PostgreSQL® built-in function, `upper`, which simply converts the supplied string
argument to uppercase.

```java
CallableStatement upperFunc = conn.prepareCall("{? = call upper( ? ) }");
upperFunc.registerOutParameter(1, Types.VARCHAR);
upperFunc.setString(2, "lowercase to uppercase");
upperFunc.execute();
String upperCased = upperFunc.getString(1);
upperFunc.close();
```

## Obtaining a `ResultSet` from a stored function

PostgreSQL's™ stored functions can return results in two different ways. The function may return either a refcursor value
or a `SETOF` some datatype. Depending on which of these return methods are used determines how the function should be called.

### From a Function Returning `SETOF` type

Functions that return data as a set should not be called via the `CallableStatement` interface, but instead should use
the normal `Statement` or `PreparedStatement` interfaces.

##### Example 6.2. Getting `SETOF` type values from a function

```java
Statement stmt = conn.createStatement();
stmt.execute("CREATE OR REPLACE FUNCTION setoffunc() RETURNS SETOF int AS " +
    "' SELECT 1 UNION SELECT 2;' LANGUAGE sql");
ResultSet rs = stmt.executeQuery("SELECT * FROM setoffunc()");
while (rs.next()) {
    // do something
}
rs.close();
stmt.close();
```

## From a Function Returning a refcursor

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- refcursor getObject handling | pgjdbc/src/main/java/org/postgresql/jdbc/PgResultSet.java | 279-305
- default fetch size inheritance | pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java | 170-182
- cursor fetch-size gate | pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java | 469-480
- refcursor fetch behavior test | pgjdbc/src/test/java/org/postgresql/test/jdbc2/RefCursorFetchTest.java | 118-164
{{< /review >}}

When calling a function that returns a refcursor you must cast the return type of `getObject` to a `ResultSet`.

> **NOTE**
>
> A `ResultSet` created from a refcursor is itself cursor-backed. The connection-level
> [`defaultRowFetchSize`](/documentation/query/fetch-size/) is honored; the driver executes
> `FETCH ALL IN <cursor>` with a statement that inherits that size, so the entire set is not eagerly materialized on
> the client. `Statement.setFetchSize()` and `ResultSet.setFetchSize()` set on the calling
> `CallableStatement` are **not** yet propagated through to the inner fetch; the open work is tracked
> by the `TODO` in `RefCursorFetchTest.java`.

##### Example 6.3. Getting refcursor Value From a Function

```java
// Setup function to call.
Statement stmt = conn.createStatement();
stmt.execute("CREATE OR REPLACE FUNCTION refcursorfunc() RETURNS refcursor AS '" +
    " DECLARE " +
    "    mycurs refcursor; " +
    " BEGIN " +
    "    OPEN mycurs FOR SELECT 1 UNION SELECT 2; " +
    "    RETURN mycurs; " +
    " END;' language plpgsql");
stmt.close();

// We must be inside a transaction for cursors to work.
conn.setAutoCommit(false);

// Function call.
CallableStatement func = conn.prepareCall("{? = call refcursorfunc() }");
func.registerOutParameter(1, Types.OTHER);
func.execute();
ResultSet results = (ResultSet) func.getObject(1);
while (results.next()) {
    // do something with the results.
}
results.close();
func.close();
```

It is also possible to treat the refcursor return value as a cursor name directly.
To do this, use the `getString` of `CallableStatement` . With the underlying cursor name,
you are free to directly use cursor commands on it, such as `FETCH` and `MOVE` .

##### Example 6.4. Treating refcursor as a cursor name

```java
conn.setAutoCommit(false);
CallableStatement func = conn.prepareCall("{? = call refcursorfunc() }");
func.registerOutParameter(1, Types.OTHER);
func.execute();
String cursorName = func.getString(1);
func.close();
```

##### Example 6.5. Calling a stored procedure

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- procedure transaction setup | pgjdbc/src/test/java/org/postgresql/test/jdbc3/ProcedureTransactionTest.java | 38-56
- procedure transaction behavior | pgjdbc/src/test/java/org/postgresql/test/jdbc3/ProcedureTransactionTest.java | 131-160
{{< /review >}}

This example shows how to call a PostgreSQL® procedure that uses transaction control.

```java
// set up a connection
String url = "jdbc:postgresql://localhost/test";
Properties props = new Properties();
...other properties...
    // Ensure EscapeSyntaxCallmode property set to support procedures if no return value
    props.setProperty("escapeSyntaxCallMode", "callIfNoReturn");
Connection con = DriverManager.getConnection(url, props);

// Setup procedure to call.
Statement stmt = con.createStatement();
stmt.execute("CREATE TEMP TABLE temp_val ( some_val bigint )");
stmt.execute("CREATE OR REPLACE PROCEDURE commitproc(a INOUT bigint) AS '" +
    " BEGIN " +
    "    INSERT INTO temp_val values(a); " +
    "    COMMIT; " +
    " END;' LANGUAGE plpgsql");
stmt.close();

// As of v11, we must be outside a transaction for procedures with transactions to work.
con.setAutoCommit(true);

// Procedure call with transaction
CallableStatement proc = con.prepareCall("{call commitproc( ? )}");
proc.setInt(1, 100);
proc.execute();
proc.close();
```
