---
layout: default_docs
title: Escape for outer joins
header: Chapter 8. JDBC escapes
resource: /documentation/head/media
previoustitle: Chapter 8. JDBC escapes
previous: escapes.html
nexttitle: Date-time escapes
next: escapes-datetime.html
---

You can specify outer joins using the following syntax: `{oj table (LEFT|RIGHT|FULL) OUTER JOIN (table | outer-join)
ON search-condition  }`

For example :

```java
ResultSet rs = stmt.executeQuery( "select * from {oj a left outer join b on (a.i=b.i)} ");
```
