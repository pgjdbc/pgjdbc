---
layout: default_docs
title: Chapter 10. Using the Driver in a Multithreaded or a Servlet Environment
header: Chapter 10. Using the Driver in a Multithreaded or a Servlet Environment
resource: media
previoustitle: Server Prepared Statements
previous: server-prepare.html
nexttitle: Chapter 11. Connection Pools and Data Sources
next: datasource.html
---

A problem with many JDBC drivers is that only one thread can use a `Connection`
at any one time --- otherwise a thread could send a query while another one is
receiving results, and this could cause severe confusion.

The PostgreSQL™ JDBC driver is thread safe. Consequently, if your application
uses multiple threads then you do not have to worry about complex algorithms to
ensure that only one thread uses the database at a time.

If a thread attempts to use the connection while another one is using it, it
will wait until the other thread has finished its current operation.  If the
operation is a regular SQL statement, then the operation consists of sending the
statement and retrieving any `ResultSet` (in full). If it is a fast-path call
(e.g., reading a block from a large object) then it consists of sending and
retrieving the respective data.

This is fine for applications and applets but can cause a performance problem
with servlets. If you have several threads performing queries then each but one
will pause. To solve this, you are advised to create a pool of connections. When
ever a thread needs to use the database, it asks a manager class for a `Connection`
object. The manager hands a free connection to the thread and marks it as busy.
If a free connection is not available, it opens one.  Once the thread has
finished using the connection, it returns it to the manager which can then either
close it or add it to the pool. The manager would also check that the connection
is still alive and remove it from the pool if it is dead.  The down side of a
connection pool is that it increases the load on the server because a new session
is created for each `Connection` object.  It is up to you and your applications's
requirements.