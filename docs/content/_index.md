---
title: "Home"
date: 2022-06-19T22:46:55+05:30
draft: false
---

This example will issue a simple query and print out the first column of each row using a Statement.

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
