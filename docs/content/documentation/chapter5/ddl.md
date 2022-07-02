---
title: Creating and Modifying Database Objects
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 16
menu:
  docs:
    parent: "chapter5"
    weight: 5
---

To create, modify or drop a database object like a table or view you use the
`execute()` method.  This method is similar to the method `executeQuery()`, but
it doesn't return a result. [Example 5.4, “Dropping a Table in JDBC](/documentation/chapter5/ddl#drop-table-example)
illustrates the usage.

**Example 5.4. Dropping a Table in JDBC**

This example will drop a table.

```java
Statement st = conn.createStatement();
st.execute("DROP TABLE mytable");
st.close();
```
