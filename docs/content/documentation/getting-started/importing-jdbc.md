---
title: "Importing JDBC"
---

This section describes how to load and initialize the JDBC driver in your programs.


Any source file that uses JDBC needs to import the `java.sql` package, using:

```java
import java.sql.*;
```

> **NOTE**
>
> You should not import the `org.postgresql` package unless you are using PostgreSQL® extensions to the JDBC API.

