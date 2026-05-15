---
title: "PostgreSQL features"
weight: 60
toc: false
last_reviewed: "2026-05-13"
---

PostgreSQL-specific functionality exposed through pgJDBC extensions to
the standard JDBC API: `LISTEN`/`NOTIFY`, `COPY`, physical and logical
replication, parameter-status messages, and the `PGConnection`
extension surface.

Reached from `Connection.unwrap(PGConnection.class)`. The standard
JDBC interfaces have no equivalent; these are intentional pgjdbc
extensions, not portable to other drivers.
