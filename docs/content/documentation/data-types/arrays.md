---
title: "Arrays"
description: "Working with PostgreSQL array columns from JDBC: `Connection.createArrayOf` and the `PGConnection` overload for primitive arrays, the Java-to-PG type mapping table, and which element types travel as binary in `PreparedStatement.setObject`."
date: 2026-05-13T00:00:00Z
draft: false
weight: 20
toc: true
last_reviewed: "2026-05-13"
aliases:
    - "/documentation/server-prepare/#arrays/"
---

PostgreSQL® provides robust support for array data types as column types, function arguments
and criteria in where clauses. There are several ways to create arrays with pgJDBC.

The [java.sql. Connection.createArrayOf(String, Object\[\])](https://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html#createArrayOf-java.lang.String-java.lang.Object:A-) can be used to create an [java.sql. Array](https://docs.oracle.com/javase/8/docs/api/java/sql/Array.html) from `Object[]` instances (Note: this includes both primitive and object multi-dimensional arrays).
A similar method `org.postgresql.PGConnection.createArrayOf(String, Object)` provides support for primitive array types.
The `java.sql.Array` object returned from these methods can be used in other methods, such as
[PreparedStatement.setArray(int, Array)](https://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html#setArray-int-java.sql.Array-).

The following types of arrays support binary representation in requests and can be used in `PreparedStatement.setObject`

|Java Type | Supported binary PostgreSQL® Types | Default PostgreSQL® Type|
|--- | --- | ---|
|`short[]` , `Short[]` | `int2[]` | `int2[]`|
|`int[]` , `Integer[]` | `int4[]` | `int4[]`|
|`long[]` , `Long[]` | `int8[]` | `int8[]`|
|`float[]` , `Float[]` | `float4[]` | `float4[]`|
|`double[]` , `Double[]` | `float8[]` | `float8[]`|
|`boolean[]` , `Boolean[]` | `bool[]` | `bool[]`|
|`String[]` | `varchar[]` , `text[]` | `varchar[]`|
|`byte[][]` | `bytea[]` | `bytea[]`|
