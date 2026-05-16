---
title: "Connect"
weight: 20
toc: false
last_reviewed: "2026-05-16"
description: "JDBC URL syntax, timeouts, Unix sockets, multi-host fail-over, and integration with connection pools."
---

Establishing a JDBC connection to PostgreSQL: URL forms, the timeouts
that bound each phase of the handshake and the lifetime of an
established connection, multi-host fail-over and load balancing,
Unix-domain sockets, connection pooling with HikariCP or `DataSource`.

For TLS configuration and authentication see [Security](/documentation/security/);
for the complete reference of every connection property see
[Connection properties](/documentation/reference/connection-properties/).
