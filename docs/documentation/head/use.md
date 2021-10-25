---
layout: default_docs
title: Chapter 3. Initializing the Driver
header: Chapter 3. Initializing the Driver
resource: /documentation/head/media
previoustitle: Creating a Database
previous: your-database.html
nexttitle: Chapter 3. Loading the Driver
next: load.html
---
		
**Table of Contents**


* [Importing JDBC](use.html#import)
* [Loading the Driver](load.html)
* [Connecting to the Database](connect.html)
   * [Connection Parameters](connect.html#connection-parameters)

This section describes how to load and initialize the JDBC driver in your programs.

<a name="import"></a>
# Importing JDBC

Any source that uses JDBC needs to import the `java.sql` package, using:

```java
import java.sql.*;
```

### Note

You should not import the `org.postgresql` package unless you are not using standard
PostgreSQL™ extensions to the JDBC API.
