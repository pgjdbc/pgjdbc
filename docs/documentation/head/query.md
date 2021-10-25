---
layout: default_docs
title: Chapter 5. Issuing a Query and Processing the Result
header: Chapter 5. Issuing a Query and Processing the Result
resource: /documentation/head/media
previoustitle: Custom SSLSocketFactory
previous: ssl-factory.html
nexttitle: Using the Statement or PreparedStatement Interface
next: statement.html
---

**Table of Contents**

* [Getting results based on a cursor](query.html#query-with-cursor)
* [Using the `Statement` or `PreparedStatement` Interface](statement.html)
* [Using the `ResultSet` Interface](resultset.html)
* [Performing Updates](update.html)
* [Creating and Modifying Database Objects](ddl.html)
* [Using Java 8 Date and Time classes](java8-date-time.html)

Any time you want to issue SQL statements to the database, you require a `Statement`
or `PreparedStatement` instance. Once you have a `Statement` or `PreparedStatement`,
you can use issue a query. This will return a `ResultSet` instance, which contains
the entire result (see the section called [“Getting results based on a cursor”](query.html#query-with-cursor)
here for how to alter this behaviour). [Example 5.1, “Processing a Simple Query in JDBC”](query.html#query-example)
illustrates this process.

<a name="query-example"></a>
**Example 5.1. Processing a Simple Query in JDBC**

This example will issue a simple query and print out the first column of each
row using a `Statement`.

```java
Statement st = conn.createStatement();
ResultSet rs = st.executeQuery("SELECT * FROM mytable WHERE columnfoo = 500");
while (rs.next())
{
    System.out.print("Column 1 returned ");
    System.out.println(rs.getString(1));
}
rs.close();
st.close();
```

This example issues the same query as before but uses a `PreparedStatement` and
a bind value in the query.

```java
int foovalue = 500;
PreparedStatement st = conn.prepareStatement("SELECT * FROM mytable WHERE columnfoo = ?");
st.setInt(1, foovalue);
ResultSet rs = st.executeQuery();
while (rs.next())
{
    System.out.print("Column 1 returned ");
    System.out.println(rs.getString(1));
}
rs.close();
st.close();
```

<a name="query-with-cursor"></a>
# Getting results based on a cursor

By default the driver collects all the results for the query at once. This can
be inconvenient for large data sets so the JDBC driver provides a means of basing
a `ResultSet` on a database cursor and only fetching a small number of rows.

A small number of rows are cached on the client side of the connection and when
exhausted the next block of rows is retrieved by repositioning the cursor.

### Note

> Cursor based `ResultSets` cannot be used in all situations. There a number of
  restrictions which will make the driver silently fall back to fetching the
  whole `ResultSet` at once.

* The connection to the server must be using the V3 protocol. This is the default
	for (and is only supported by) server versions 7.4 and later.
* The `Connection` must not be in autocommit mode. The backend closes cursors at
	the end of transactions, so in autocommit mode the backend will have
	closed the cursor before anything can be fetched from it.
* The `Statement` must be created with a `ResultSet` type of `ResultSet.TYPE_FORWARD_ONLY`.
	This is the default, so no code will need to be rewritten to take advantage
	of this, but it also means that you cannot scroll backwards or otherwise
	jump around in the `ResultSet`.
* The query given must be a single statement, not multiple statements strung
	together with semicolons.

<a name="fetchsize-example"></a>
**Example 5.2. Setting fetch size to turn cursors on and off.**

Changing code to cursor mode is as simple as setting the fetch size of the
`Statement` to the appropriate size. Setting the fetch size back to 0 will cause
all rows to be cached (the default behaviour).

```java
// make sure autocommit is off
conn.setAutoCommit(false);
Statement st = conn.createStatement();

// Turn use of the cursor on.
st.setFetchSize(50);
ResultSet rs = st.executeQuery("SELECT * FROM mytable");
while (rs.next())
{
    System.out.print("a row was returned.");
}
rs.close();

// Turn the cursor off.
st.setFetchSize(0);
rs = st.executeQuery("SELECT * FROM mytable");
while (rs.next())
{
    System.out.print("many rows were returned.");
}
rs.close();

// Close the statement.
st.close();
```
