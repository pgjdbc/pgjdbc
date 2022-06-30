---
layout: default_docs
title: Performing Updates
header: Chapter 5. Issuing a Query and Processing the Result
resource: /documentation/head/media
previoustitle: Using the ResultSet Interface
previous: resultset.html
nexttitle: Creating and Modifying Database Objects
next: ddl.html
---

To change data (perform an `INSERT`, `UPDATE`, or `DELETE`) you use the
`executeUpdate()` method. This method is similar to the method `executeQuery()`
used to issue a `SELECT` statement, but it doesn't return a `ResultSet`; instead
it returns the number of rows affected by the `INSERT`, `UPDATE`, or `DELETE`
statement. [Example 5.3, “Deleting Rows in JDBC”](update.html#delete-example)
illustrates the usage.

<a name="delete-example"></a>
**Example 5.3. Deleting Rows in JDBC**

This example will issue a simple `DELETE` statement and print out the number of
rows deleted.

```java
int foovalue = 500;
PreparedStatement st = conn.prepareStatement("DELETE FROM mytable WHERE columnfoo = ?");
st.setInt(1, foovalue);
int rowsDeleted = st.executeUpdate();
System.out.println(rowsDeleted + " rows deleted");
st.close();
```
