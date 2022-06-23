---
title: Using the Driver in a Multithreaded or a Servlet Environment
date: 2022-06-19T22:46:55+05:30
draft: true
weight: 1
---


The PostgreSQL™ JDBC driver is not thread safe.
The PostgreSQL server is not threaded. Each connection creates a new process on the server;
as such any concurrent requests to the process would have to be serialized.
The driver makes no guarantees that methods on connections are synchronized.
It will be up to the caller to synchronize calls to the driver.

A notable exception is org/postgresql/jdbc/TimestampUtils.java which is threadsafe.
