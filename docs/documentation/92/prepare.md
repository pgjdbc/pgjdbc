---
layout: default_docs
title: Preparing the Database Server for JDBC
header: Chapter 2. Setting up the JDBC Driver
resource: media
previoustitle: Setting up the Class Path
previous: classpath.html
nexttitle: Creating a Database
next: your-database.html
---

Because Java does not support using unix sockets the PostgreSQL™ server must be
configured to allow TCP/IP connections. Starting with server version 8.0 TCP/IP
connections are allowed from `localhost`. To allow connections to other interfaces
than the loopback interface, you must modify the `postgresql.conf` file's `listen_addresses`
setting.

For server versions prior to 8.0 the server does not listen on any interface by
default, and you must set `tcpip_socket = true` in the `postgresql.conf` file.

Once you have made sure the server is correctly listening for TCP/IP connections
the next step is to verify that users are allowed to connect to the server. Client
authentication is setup in `pg_hba.conf`. Refer to the main PostgreSQL™ documentation
for details. The JDBC driver supports the `trust`, `ident`, `password`, `md5`, and
`crypt` authentication methods.