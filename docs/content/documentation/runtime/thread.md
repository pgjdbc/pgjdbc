---
title: "Using the Driver in a Multithreaded or a Servlet Environment"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 9
toc: false
aliases:
    - "/documentation/head/thread.html"
    - "/documentation/80/thread.html"
    - "/documentation/81/thread.html"
    - "/documentation/82/thread.html"
    - "/documentation/83/thread.html"
    - "/documentation/84/thread.html"
    - "/documentation/85/thread.html"
    - "/documentation/90/thread.html"
    - "/documentation/91/thread.html"
    - "/documentation/92/thread.html"
    - "/documentation/93/thread.html"
    - "/documentation/94/thread.html"
---

The PostgreSQL® JDBC driver is not thread safe. The PostgreSQL server is not threaded. Each connection creates a new process
on the server as such any concurrent requests to the process would have to be serialized. The driver makes no guarantees
that methods on connections are synchronized. It will be up to the caller to synchronize calls to the driver.

A notable exception is `org/postgresql/jdbc/TimestampUtils.java` which is threadsafe.
