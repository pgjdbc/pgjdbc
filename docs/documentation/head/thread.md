---
layout: default_docs
title: Chapter 10. Using the Driver in a Multithreaded or a Servlet Environment
header: Chapter 10. Using the Driver in a Multithreaded or a Servlet Environment
resource: /documentation/head/media
previoustitle: Server Prepared Statements
previous: server-prepare.html
nexttitle: Chapter 11. Connection Pools and Data Sources
next: datasource.html
---


The PostgreSQL™ JDBC driver should not be considered to be thread safe. 
The PostgreSQL server is not threaded. Each connection creates a new process on the server; 
as such any concurrent requests to the process would have to be serialized.
Most classes and methods in the PostgreSQL™ JDBC driver are not thread safe. For instance, you may not call methods on the same connection object from multiple threads without synchronizing access to the connection yourself. However it is often more convenient and performant to open a separate connection for each thread or use a connection pooling library.

The following classes and methods are guaranteed to be thread-safe:
* org.postgresql.Driver
* Getter methods on DataSource implementations, including methods getConnection and getPooledConnection. Setter methods are not thread-safe.
* org.postgresql.jdbc.TimestampUtils
 
