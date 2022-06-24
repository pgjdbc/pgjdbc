---
title: Preparing the Database Server for JDBC
date: 2022-06-19T22:46:55+05:30
draft: false
menu:
  docs:
    parent: "chapter2"
    weight: 3
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
