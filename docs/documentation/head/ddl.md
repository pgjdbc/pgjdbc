---
layout: default_docs
title: Creating and Modifying Database Objects
header: Chapter 5. Issuing a Query and Processing the Result
resource: /documentation/head/media
previoustitle: Performing Updates
previous: update.html
nexttitle: Using Java 8 Date and Time classes
next: java8-date-time.html
---

To create, modify or drop a database object like a table or view you use the
`execute()` method.  This method is similar to the method `executeQuery()`, but
it doesn't return a result. [Example 5.4, “Dropping a Table in JDBC](ddl.html#drop-table-example)
illustrates the usage.

<a name="drop-table-example"></a>
**Example 5.4. Dropping a Table in JDBC**

This example will drop a table.

```java
Statement st = conn.createStatement();
st.execute("DROP TABLE mytable");
st.close();
```
