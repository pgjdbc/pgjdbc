---
title: "Using the Statement or PreparedStatement Interface"
---

Any time you want to issue SQL statements to the database, you require a `Statement` or `PreparedStatement` instance. Once you have a `Statement` or `PreparedStatement` , you can use issue a query. This will return a `ResultSet` instance, which contains the entire result (see the section called [Getting results based on a cursor](/documentation/query/#getting-results-based-on-a-cursor) here for how to alter this behaviour). [Example 5.1, “Processing a Simple Query in JDBC”](/documentation/query/#example51processing-a-simple-query-in-jdbc) illustrates this process.

##### Example 5.1. Processing a Simple Query in JDBC

This example will issue a simple query and print out the first column of each row using a `Statement` .

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


The following must be considered when using the `Statement` or `PreparedStatement` interface:

* You can use a single `Statement` instance as many times as you want. You could create one as soon as you open the 
connection and use it for the connection's lifetime. But you have to remember that only one `ResultSet` can exist 
per `Statement` or `PreparedStatement` at a given time.

* If you need to perform a query while processing a `ResultSet`, you can simply create and use another `Statement` .

* If you are using threads, and several are using the database, you must use a separate `Statement` for each thread. 
Refer to [Using the Driver in a Multithreaded or a Servlet Environment](/documentation/thread/) if you are thinking of 
using threads, as it covers some important points.

* When you are done using the `Statement` or `PreparedStatement` you should close it.

* In JDBC, the question mark (`?`) is the placeholder for the positional parameters of a `PreparedStatement`. 
There are, however, a number of PostgreSQL® operators that contain a question mark. To keep such question marks in an SQL 
statement from being interpreted as positional parameters, use two question marks ( `??` ) as escape sequence. 
You can also use this escape sequence in a `Statement` , but that is not required. Specifically only in a `Statement` 
a single ( `?` ) can be used as an operator.

## Using the ResultSet Interface

The following must be considered when using the `ResultSet` interface:

* Before reading any values, you must call `next()`. This returns true if there is a result, but more importantly, 
it prepares the row for processing.

* You must close a `ResultSet` by calling `close()` once you have finished using it.

* Once you make another query with the `Statement` used to create a `ResultSet`, the currently open `ResultSet` instance 
is closed automatically.

* When PreparedStatement API is used,  `ResultSet` switches to binary mode after five query executions (this default is 
set by the `prepareThreshold` connection property, see [Server Prepared Statements](/documentation/server-prepare/#server-prepared-statements). 
This may cause unexpected behaviour when some methods are called. For example, results on method calls such as `getString()` 
on non-string data types, while logically equivalent, may be formatted differently after execution exceeds the set 
`prepareThreshold` when conversion to object method switches to the method with a return type matching the return mode.

## Performing Updates

To change data (perform an `INSERT` , `UPDATE` , or `DELETE` ) you use the `executeUpdate()` method. This method is 
similar to the method `executeQuery()`

used to issue a `SELECT` statement, but it doesn't return a `ResultSet` instead it returns the number of rows affected 
by the `INSERT` , `UPDATE` , or `DELETE` statement. [Example 5.3, “Deleting Rows in JDBC”](/documentation/query/#example53deleting-rows-in-jdbc) 
illustrates the usage.

##### Example 5.3. Deleting Rows in JDBC

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

To create, modify or drop a database object like a table or view you use the `execute()` method.  This method is similar
to the method `executeQuery()` , but it doesn't return a result.
[Example 5.4, “Dropping a Table in JDBC](/documentation/query/#example54dropping-a-table-in-jdbc) illustrates the usage.

##### Example 5.4. Dropping a Table in JDBC

This example will drop a table.

```java
Statement st = conn.createStatement();
st.execute("DROP TABLE mytable");
st.close();
```

