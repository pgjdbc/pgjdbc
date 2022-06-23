---
title: Escape for outer joins
date: 2022-06-19T22:46:55+05:30
draft: true
weight: 2
---

You can specify outer joins using the following syntax: `{oj table (LEFT|RIGHT|FULL) OUTER JOIN (table | outer-join)
ON search-condition  }`

For example :

```java
ResultSet rs = stmt.executeQuery( "select * from {oj a left outer join b on (a.i=b.i)} ");
```
