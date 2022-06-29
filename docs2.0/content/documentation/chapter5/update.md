---
title: Performing Updates
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 15
menu:
  docs:
    parent: "chapter5"
    weight: 4
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
