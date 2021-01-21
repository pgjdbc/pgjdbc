---
layout: default_docs
title: Arrays
header: Chapter 9. PostgreSQL™ Extensions to the JDBC API
resource: /documentation/head/media
previoustitle: Physical and Logical replication API
previous: replication.html
nexttitle: Chapter 10. Using the Driver in a Multithreaded or a Servlet Environment
next: thread.html
---

PostgreSQL™ provides robust support for array data types as column types, function arguments
and criteria in where clauses. There are several ways to create arrays with pgjdbc.

The [java.sql.Connection.createArrayOf(String, Object\[\])](https://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html#createArrayOf-java.lang.String-java.lang.Object:A-) can be used to create an [java.sql.Array](https://docs.oracle.com/javase/8/docs/api/java/sql/Array.html) from `Object[]` instances (Note: this includes both primitive and object multi-dimensional arrays).
A similar method `org.postgresql.PGConnection.createArrayOf(String, Object)` provides support for primitive array types.
The `java.sql.Array` object returned from these methods can be used in other methods, such as [PreparedStatement.setArray(int, Array)](https://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html#setArray-int-java.sql.Array-).

The following types of arrays support binary representation in requests and can be used in `PreparedStatement.setObject`:

Java Type | Supported binary PostgreSQL™ Types | Default PostgreSQL™ Type
--- | --- | ---
`short[]`, `Short[]` | `int2[]` | `int2[]`
`int[]`, `Integer[]` | `int4[]` | `int4[]`
`long[]`, `Long[]` | `int8[]` | `int8[]`
`float[]`, `Float[]` | `float4[]` | `float4[]`
`double[]`, `Double[]` | `float8[]` | `float8[]`
`boolean[]`, `Boolean[]` | `bool[]` | `bool[]`
`String[]` | `varchar[]`, `text[]` | `varchar[]`
`byte[][]` | `bytea[]` | `bytea[]`
