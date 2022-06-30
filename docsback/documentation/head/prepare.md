---
layout: default_docs
title: Preparing the Database Server for JDBC
header: Chapter 2. Setting up the JDBC Driver
resource: /documentation/head/media
previoustitle: Setting up the Class Path
previous: classpath.html
nexttitle: Creating a Database
next: your-database.html
---

Out of the box, Java does not support unix sockets so the PostgreSQL server must be 
configured to allow TCP/IP connections. Starting with server version 8.0 TCP/IP
connections are allowed from `localhost`. To allow connections to other interfaces
than the loopback interface, you must modify the `postgresql.conf` file's `listen_addresses`
setting.


Once you have made sure the server is correctly listening for TCP/IP connections
the next step is to verify that users are allowed to connect to the server. Client
authentication is setup in `pg_hba.conf`. Refer to the main PostgreSQL™ [documentation](https://www.postgresql.org/docs/current/auth-pg-hba-conf.html)
for details . 
