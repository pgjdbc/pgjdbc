---
title: "Issuing a query"
date: 2026-05-13T00:00:00Z
draft: false
weight: 1
toc: true
last_reviewed: "2026-05-21"
aliases:
    - "/documentation/query/"
---

Any time you want to issue SQL statements to the database, you need a `Statement` or `PreparedStatement` instance. Once you have a `Statement` or `PreparedStatement`, you can issue a query. This will return a `ResultSet` instance, which contains the result rows fetched by the driver (see [Cursor-based fetching](/documentation/query/fetch-size/) for how to alter this behavior). [Example 5.1, “Processing a Simple Query in JDBC”](#example-51-processing-a-simple-query-in-jdbc) illustrates this process.

## Example 5.1. Processing a Simple Query in JDBC

This example will issue a simple query and print out the first column of each row using a `Statement`.

```java
Statement st = conn.createStatement();
ResultSet rs = st.executeQuery("SELECT * FROM mytable WHERE columnfoo = 500");
while (rs.next()) {
    System.out.print("Column 1 returned ");
    System.out.println(rs.getString(1));
}
rs.close();
st.close();
```

This example issues the same query as before but uses a `PreparedStatement` and a bind value in the query.

```java
int foovalue = 500;
PreparedStatement st = conn.prepareStatement("SELECT * FROM mytable WHERE columnfoo = ?");
st.setInt(1, foovalue);
ResultSet rs = st.executeQuery();
while (rs.next()) {
    System.out.print("Column 1 returned ");
    System.out.println(rs.getString(1));
}
rs.close();
st.close();
```

## Using the Statement or PreparedStatement Interface

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PgStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java | 280-299
- PgPreparedStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgPreparedStatement.java | 92-145
- Parser.java | pgjdbc/src/main/java/org/postgresql/core/Parser.java | 127-144
- CompositeQueryParseTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc3/CompositeQueryParseTest.java | 54-68
{{< /review >}}

The following must be considered when using the `Statement` or `PreparedStatement` interface:

* You can use a single `Statement` instance as many times as you want. You could create one as soon as you open the 
connection and use it for the connection's lifetime. But you have to remember that only one `ResultSet` can exist 
per `Statement` or `PreparedStatement` at a given time.

* If you need to perform a query while processing a `ResultSet`, you can simply create and use another `Statement`.

* If you are using threads, and several are using the database, you must use a separate `Statement` for each thread.
Refer to [DataSource and JNDI § Thread safety](/documentation/connect/datasource/#thread-safety) if you
are thinking of using threads, as it covers some important points.

* When you are done using the `Statement` or `PreparedStatement`, you should close it.

* In JDBC, the question mark (`?`) is the placeholder for the positional parameters of a `PreparedStatement`. 
There are, however, a number of PostgreSQL® operators that contain a question mark. To keep such question marks in an SQL 
statement from being interpreted as positional parameters, use two question marks (`??`) as the escape sequence.
You can also use this escape sequence in a `Statement`, but that is not required. Specifically, a single `?`
can be used as an operator only in a `Statement`.

## Using the ResultSet Interface

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PgStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java | 404-427
- PgStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java | 435-499
- PgResultSet.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgResultSet.java | 2271-2340
- PgResultSet.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgResultSet.java | 2391-2415
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 136-148
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 688-698
{{< /review >}}

The following must be considered when using the `ResultSet` interface:

* Before reading any values, you must call `next()`. This returns true if there is a result, but more importantly, 
it prepares the row for processing.

* You must close a `ResultSet` by calling `close()` once you have finished using it.

* Once you make another query with the `Statement` used to create a `ResultSet`, the currently open `ResultSet` instance 
is closed automatically.

* When the `PreparedStatement` API is used, the driver can switch supported column types to binary transfer once a statement
reaches the `prepareThreshold` execution count. The default threshold is five query executions, and the default
`binaryTransfer` setting enables binary transfer for supported built-in types when possible. See
[Server-prepared statements](/documentation/query/prepared-statements/) for details. This may cause unexpected behavior
when some methods are called. For example, `getString()` on non-string data types can be formatted differently once binary
transfer is used.

## Performing Updates

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PgStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java | 302-323
- PgStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java | 482-490
{{< /review >}}

To change data (perform an `INSERT`, `UPDATE`, or `DELETE`) you use the `executeUpdate()` method. This method is
similar to the `executeQuery()` method used to issue a `SELECT` statement, but it doesn't return a `ResultSet`;
instead, it returns the number of rows affected
by the `INSERT`, `UPDATE`, or `DELETE` statement. [Example 5.3, “Deleting Rows in JDBC”](#example-53-deleting-rows-in-jdbc)
illustrates the usage.

### Example 5.3. Deleting Rows in JDBC

This example will issue a simple `DELETE` statement and print out the number of rows deleted.

```java
int foovalue = 500;
PreparedStatement st = conn.prepareStatement("DELETE FROM mytable WHERE columnfoo = ?");
st.setInt(1, foovalue);
int rowsDeleted = st.executeUpdate();
System.out.println(rowsDeleted + " rows deleted");
st.close();
```

## Creating and Modifying Database Objects

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PgStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java | 240-270
- PgStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java | 325-332
{{< /review >}}

To create, modify, or drop a database object like a table or view, you use the `execute()` method. This method is similar
to the method `executeQuery()`, but for DDL statements like `DROP TABLE` it normally completes without returning a
`ResultSet`.
[Example 5.4, “Dropping a Table in JDBC”](#example-54-dropping-a-table-in-jdbc) illustrates the usage.

### Example 5.4. Dropping a Table in JDBC

This example will drop a table.

```java
Statement st = conn.createStatement();
st.execute("DROP TABLE mytable");
st.close();
```
