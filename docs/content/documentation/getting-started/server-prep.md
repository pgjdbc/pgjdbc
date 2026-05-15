---
title: "Server preparation"
date: 2026-05-13T00:00:00Z
draft: false
weight: 5
toc: true
last_reviewed: "2026-05-13"
description: "Three things to check on the PostgreSQL server before the first JDBC connection: TCP listener, pg_hba.conf, and database encoding."
aliases:
    - "/documentation/setup/#preparing-the-database-server-for-jdbc/"
    - "/documentation/setup/#creating-a-database/"
---

Three things to verify on the server side before a Java application can
connect. None of them are pgJDBC-specific — they are PostgreSQL
configuration tasks — but each one is a common reason for an otherwise
correct application to fail at startup.

## 1. The server listens on TCP

Java cannot connect over a Unix-domain socket without an extra
library (see [Unix sockets](/documentation/connect/unix-sockets/)). For
TCP, the server must be configured to accept it.

```bash
# postgresql.conf — accept connections on all interfaces
listen_addresses = '*'
```

The default is `localhost`, which is fine for an application running on
the same host as the database; for anything else, broaden the address
or list specific interfaces. Restart PostgreSQL after editing.

## 2. pg_hba.conf allows the connection

Even with the listener open, PostgreSQL refuses authentication for
client/database/host combinations that are not explicitly permitted.
The relevant file is `pg_hba.conf` in the server's data directory.

A minimal SCRAM-secured rule for a remote application:

```
# TYPE   DATABASE   USER   ADDRESS            METHOD
host     mydb       alice  10.0.0.0/16        scram-sha-256
hostssl  mydb       alice  0.0.0.0/0          scram-sha-256
```

The full reference is the PostgreSQL
[`pg_hba.conf` documentation](https://www.postgresql.org/docs/current/auth-pg-hba-conf.html).
Use `hostssl` (not `host`) for any non-local rule so plaintext rows
never apply over the network; pair it with `sslmode=verify-full` on
the client side (see [Quick Start § 3](/documentation/getting-started/install/#3-configure-ssltls)).

## 3. Database encoding is UTF-8

```sql
CREATE DATABASE mydb WITH ENCODING 'UTF8';
```

Java strings are UTF-16 internally and the driver converts to whatever
the database's `client_encoding` is. With a UTF-8 database the round-trip
is lossless for any text Java can represent.

Avoid `SQL_ASCII`. It is not a real encoding — it accepts any byte
sequence without validation — and you will hit corruption the first
time the application stores a character outside seven-bit ASCII.

## Next step

With those three boxes ticked, return to the
[Quick Start](/documentation/getting-started/install/) and open the
first connection.
