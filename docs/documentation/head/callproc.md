---
layout: default_docs
title: Chapter 6. Calling Stored Functions
header: Chapter 6. Calling Stored Functions
resource: media
previoustitle: Creating and Modifying Database Objects
previous: ddl.html
nexttitle: Chapter 7. Storing Binary Data
next: binary-data.html
---

**Table of Contents**

* [Obtaining a `ResultSet` from a stored function](callproc.html#callproc-resultset)
	* [From a Function Returning `SETOF` type](callproc.html#callproc-resultset-setof)
	* [From a Function Returning a refcursor](callproc.html#callproc-resultset-refcursor)

<a name="call-function-example"></a>
**Example 6.1. Calling a built in stored function**

This example shows how to call a PostgreSQL™ built in function, `upper`, which
simply converts the supplied string argument to uppercase.

```java
CallableStatement upperProc = conn.prepareCall("{? = call upper( ? ) }");
upperProc.registerOutParameter(1, Types.VARCHAR);
upperProc.setString(2, "lowercase to uppercase");
upperProc.execute();
String upperCased = upperProc.getString(1);
upperProc.close();
```

<a name="callproc-resultset"></a>
# Obtaining a `ResultSet` from a stored function

PostgreSQL's™ stored functions can return results in two different ways. The
function may return either a refcursor value or a `SETOF` some datatype.  Depending
on which of these return methods are used determines how the function should be
called.

<a name="callproc-resultset-setof"></a>
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

<a name="callproc-resultset-refcursor"></a>
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

// Procedure call.
CallableStatement proc = conn.prepareCall("{? = call refcursorfunc() }");
proc.registerOutParameter(1, Types.OTHER);
proc.execute();
ResultSet results = (ResultSet) proc.getObject(1);
while (results.next())
{
    // do something with the results.
}
results.close();
proc.close();
```

It is also possible to treat the refcursor return value as a cursor name directly.
To do this, use the `getString` of `ResultSet`. With the underlying cursor name,
you are free to directly use cursor commands on it, such as `FETCH` and `MOVE`.

<a name="refcursor-string-example"></a>
**Example 6.4. Treating refcursor as a cursor name**

```java
conn.setAutoCommit(false);
CallableStatement proc = conn.prepareCall("{? = call refcursorfunc() }");
proc.registerOutParameter(1, Types.OTHER);
proc.execute();
String cursorName = proc.getString(1);
proc.close();
```
