---
layout: default_docs
title: Chapter 6. Calling Stored Functions and Procedures
header: Chapter 6. Calling Stored Functions and Procedures
resource: /documentation/head/media
previoustitle: Creating and Modifying Database Objects
previous: ddl.html
nexttitle: Chapter 7. Storing Binary Data
next: binary-data.html
---

**Table of Contents**

* [Obtaining a `ResultSet` from a stored function](callproc.html#callfunc-resultset)
	* [From a Function Returning `SETOF` type](callproc.html#callfunc-resultset-setof)
	* [From a Function Returning a refcursor](callproc.html#callfunc-resultset-refcursor)
* [Calling stored procedure with transaction control](callproc.html#call-procedure-example)

PostgreSQL™ supports two types of stored objects, functions that can return a
result value and - starting from v11 - procedures that can perform transaction
control. Both types of stored objects are invoked using `CallableStatement` and
the standard JDBC escape call syntax `{call storedobject(?)}`. The
`escapeSyntaxCallMode` connection property controls how the driver transforms the
call syntax to invoke functions or procedures.

The default mode, `select`, supports backwards compatibility for existing
applications and supports function invocation only. This is required to invoke
a void returning function. For new applications, use
`escapeSyntaxCallMode=callIfNoReturn` to map `CallableStatement`s with return
values to stored functions and `CallableStatement`s without return values to
stored procedures.

<a name="call-function-example"></a>
**Example 6.1. Calling a built in stored function**

This example shows how to call a PostgreSQL™ built in function, `upper`, which
simply converts the supplied string argument to uppercase.

```java
CallableStatement upperFunc = conn.prepareCall("{? = call upper( ? ) }");
upperFunc.registerOutParameter(1, Types.VARCHAR);
upperFunc.setString(2, "lowercase to uppercase");
upperFunc.execute();
String upperCased = upperFunc.getString(1);
upperFunc.close();
```

<a name="callfunc-resultset"></a>
# Obtaining a `ResultSet` from a stored function

PostgreSQL's™ stored functions can return results in two different ways. The
function may return either a refcursor value or a `SETOF` some datatype.  Depending
on which of these return methods are used determines how the function should be
called.

<a name="callfunc-resultset-setof"></a>
## From a Function Returning `SETOF` type

Functions that return data as a set should not be called via the `CallableStatement`
interface, but instead should use the normal `Statement` or `PreparedStatement`
interfaces.

<a name="setof-resultset"></a>
**Example 6.2. Getting `SETOF` type values from a function**

```java
Statement stmt = conn.createStatement();
stmt.execute("CREATE OR REPLACE FUNCTION setoffunc() RETURNS SETOF int AS "
    + "' SELECT 1 UNION SELECT 2;' LANGUAGE sql");
ResultSet rs = stmt.executeQuery("SELECT * FROM setoffunc()");
while (rs.next())
{
    // do something
}
rs.close();
stmt.close();
```

<a name="callfunc-resultset-refcursor"></a>
## From a Function Returning a refcursor

When calling a function that returns a refcursor you must cast the return type of
`getObject` to a `ResultSet`

### Note
	  
> One notable limitation of the current support for a `ResultSet` created from
a refcursor is that even though it is a cursor backed `ResultSet`, all data will
be retrieved and cached on the client. The `Statement` fetch size parameter
described in the section called [“Getting results based on a cursor”](query.html#query-with-cursor)
is ignored. This limitation is a deficiency of the JDBC driver, not the server,
and it is technically possible to remove it, we just haven't found the time.

<a name="get-refcursor-from-function-call"></a>
**Example 6.3. Getting refcursor Value From a Function**

```java
// Setup function to call.
Statement stmt = conn.createStatement();
stmt.execute("CREATE OR REPLACE FUNCTION refcursorfunc() RETURNS refcursor AS '"
    + " DECLARE "
    + "    mycurs refcursor; "
    + " BEGIN "
    + "    OPEN mycurs FOR SELECT 1 UNION SELECT 2; "
    + "    RETURN mycurs; "
    + " END;' language plpgsql");
stmt.close();

// We must be inside a transaction for cursors to work.
conn.setAutoCommit(false);

// Function call.
CallableStatement func = conn.prepareCall("{? = call refcursorfunc() }");
func.registerOutParameter(1, Types.OTHER);
func.execute();
ResultSet results = (ResultSet) func.getObject(1);
while (results.next())
{
    // do something with the results.
}
results.close();
func.close();
```

It is also possible to treat the refcursor return value as a cursor name directly.
To do this, use the `getString` of `ResultSet`. With the underlying cursor name,
you are free to directly use cursor commands on it, such as `FETCH` and `MOVE`.

<a name="refcursor-string-example"></a>
**Example 6.4. Treating refcursor as a cursor name**

```java
conn.setAutoCommit(false);
CallableStatement func = conn.prepareCall("{? = call refcursorfunc() }");
func.registerOutParameter(1, Types.OTHER);
func.execute();
String cursorName = func.getString(1);
func.close();
```

<a name="call-procedure-example"></a>
**Example 6.5. Calling a stored procedure

This example shows how to call a PostgreSQL™ procedure that uses transaction control.

```java
// set up a connection
String url = "jdbc:postgresql://localhost/test";
Properties props = new Properties();
... other properties ...
// Ensure EscapeSyntaxCallmode property set to support procedures if no return value
props.setProperty("escapeSyntaxCallMode", "callIfNoReturn");
Connection con = DriverManager.getConnection(url, props);

// Setup procedure to call.
Statement stmt = con.createStatement();
stmt.execute("CREATE TEMP TABLE temp_val ( some_val bigint )");
stmt.execute("CREATE OR REPLACE PROCEDURE commitproc(a INOUT bigint) AS '"
    + " BEGIN "
    + "    INSERT INTO temp_val values(a); "
    + "    COMMIT; "
    + " END;' LANGUAGE plpgsql");
stmt.close();

// As of v11, we must be outside a transaction for procedures with transactions to work.
con.setAutoCommit(true);

// Procedure call with transaction
CallableStatement proc = con.prepareCall("{call commitproc( ? )}");
proc.setInt(1, 100);
proc.execute();
proc.close();
```
