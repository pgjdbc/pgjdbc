---
title: Connection Pools and Data Sources
date: 2022-06-19T22:46:55+05:30
draft: true
weight: 1
---

JDBC 2 introduced standard connection pooling features in an add-on API known as
the JDBC 2.0 Optional Package (also known as the JDBC 2.0 Standard Extension).
These features have since been included in the core JDBC 3 API.

<a name="ds-intro"></a>
# Overview

The JDBC API provides a client and a server interface for connection pooling.
The client interface is `javax.sql.DataSource`, which is what application code
will typically use to acquire a pooled database connection. The server interface
is `javax.sql.ConnectionPoolDataSource`, which is how most application servers
will interface with the PostgreSQL™ JDBC driver.

In an application server environment, the application server configuration will
typically refer to the PostgreSQL™ `ConnectionPoolDataSource` implementation,
while the application component code will typically acquire a `DataSource`
implementation provided by the application server (not by PostgreSQL™).

For an environment without an application server, PostgreSQL™ provides two
implementations of `DataSource` which an application can use directly. One
implementation performs connection pooling, while the other simply provides
access to database connections through the `DataSource` interface without any
pooling. Again, these implementations should not be used in an application server
environment unless the application server does not support the `ConnectionPoolDataSource`
interface.
