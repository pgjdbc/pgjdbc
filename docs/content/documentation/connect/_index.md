---
title: "Connect"
weight: 20
toc: false
last_reviewed: "2026-05-20"
description: "JDBC URL syntax, recommended non-default settings, timeouts, connection pooling, Unix sockets, multi-host fail-over, and integration with connection pools."
---

Establishing a JDBC connection to PostgreSQL: URL forms, the
[non-default properties worth turning on](/documentation/connect/recommended-properties/)
in production, the timeouts that bound each phase of the handshake
and the lifetime of an established connection, production
[connection pooling](/documentation/connect/connection-pooling/) with
HikariCP / Tomcat JDBC / c3p0, multi-host fail-over and load
balancing, Unix-domain sockets.

For TLS configuration and authentication see [Security](/documentation/security/);
for the complete reference of every connection property see
[Connection properties](/documentation/reference/connection-properties/).
