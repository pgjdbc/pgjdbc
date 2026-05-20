---
title: "Recommended properties"
date: 2026-05-20T00:00:00Z
draft: false
weight: 7
toc: true
last_reviewed: "2026-05-20"
description: "A short list of non-default connection properties that are off by default for backward-compatibility reasons but pay back almost everywhere — batch insert rewriting, TCP keep-alive, and tagging the backend session."
---

The driver ships with conservative defaults — every behaviour change that
could surprise an existing application is off until you ask for it.
That is the right default for a library that has been around since
JDBC 1, but it also means a fresh install leaves several big wins on
the table.

The properties below are the ones worth setting deliberately on most
production deployments. Each comes with one specific caveat — what is
holding it back from being on by default — so you can decide rather
than copy-paste.

## `reWriteBatchedInserts=true` — batch INSERT throughput

[`reWriteBatchedInserts`](/documentation/reference/connection-properties/#prop-rewritebatchedinserts)
collapses a batch of N single-row `INSERT INTO t VALUES (?, ?, ...)`
statements into a single multi-row `INSERT INTO t VALUES (?, ?, ...),
(?, ?, ...), ...` round-trip. For INSERT-heavy workloads it cuts
network round-trips and per-row protocol overhead by 1–2 orders of
magnitude and is one of the biggest single-property wins the driver
offers.

```properties
jdbc.url=jdbc:postgresql://db.example/app?reWriteBatchedInserts=true
```

**Why it is off by default.** The per-statement counts returned by
`PreparedStatement.executeBatch()` change shape. With the rewrite, the
server returns a single aggregated row count for the whole multi-row
INSERT, which cannot be attributed back to the individual statements
in the batch. The driver reports
[`Statement.SUCCESS_NO_INFO`](https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/Statement.html#SUCCESS_NO_INFO)
(`-2`) for each entry in the returned `int[]` instead of `1`. Code
that inspects the array to detect partial failures, count inserted
rows, or pair counts back with input rows needs to handle
`SUCCESS_NO_INFO` first — see
[`BatchResultHandler.uncompressUpdateCount`](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/jdbc/BatchResultHandler.java#L223).

The rewrite applies only to plain `INSERT … VALUES (…)` statements
that share a parameter list. INSERTs that use `RETURNING`, `ON
CONFLICT`-driven updates with `WHERE` clauses on the conflict target,
or other shapes the rewriter cannot collapse fall back to the
one-INSERT-per-row path automatically, so the property is safe to
enable globally even for code paths that do not benefit.

## `tcpKeepAlive=true` — survive NAT and load balancers

[`tcpKeepAlive`](/documentation/reference/connection-properties/#prop-tcpkeepalive)
enables `SO_KEEPALIVE` on the socket. The kernel then sends a tiny
probe periodically on otherwise-idle connections, which keeps
NAT-table and load-balancer connection-tracking entries from being
garbage-collected and lets the driver notice a half-open connection
within minutes rather than at the next query.

```properties
jdbc.url=jdbc:postgresql://db.example/app?tcpKeepAlive=true
```

**Why it is off by default.** Historical compatibility — JDBC has had
this property since 9.4 and turning it on by default would be a
silent behaviour change for very old deployments. There is no real
downside to enabling it: keepalive traffic is negligible, and any
network path that breaks under keepalive packets is broken in more
fundamental ways.

The keepalive interval and probe count are set by the OS, not the
driver. On Linux the defaults are `net.ipv4.tcp_keepalive_time=7200`
(two hours of idle before the first probe), which is longer than most
NAT timeouts; tune the sysctls or set the per-socket variants in your
container if you need a tighter bound. The companion topic of how
sockets actually die behind a load balancer — and which fix applies —
lives in [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/).

## `ApplicationName=my-service` — name the session for operators

[`ApplicationName`](/documentation/reference/connection-properties/#prop-applicationname)
sets the `application_name` GUC that the server stamps onto every row
of `pg_stat_activity`. Without it, every connection shows up as
`PostgreSQL JDBC Driver`, which makes triaging a "what is hammering
the database?" question much harder than it needs to be.

```properties
jdbc.url=jdbc:postgresql://db.example/app?ApplicationName=orders-api
```

It does not affect driver behaviour or performance — it is purely an
observability tag. Pick a name that maps cleanly to a service in your
deployment (the value of `ApplicationName` shows up in server logs
with `log_line_prefix = '%a'`, in `pg_stat_activity.application_name`,
and in extensions like `pg_stat_statements`).

**Why it is off by default.** The driver has no way to know what the
calling service is called; the default `"PostgreSQL JDBC Driver"` is
a no-op fallback.

## All three at once

The properties above are independent — set the ones that apply, leave
the rest. A typical JDBC URL ends up looking like:

```
jdbc:postgresql://db.example/app?reWriteBatchedInserts=true&tcpKeepAlive=true&ApplicationName=orders-api
```

For SSL, authentication, and timeouts the corresponding recommendations
live with the rest of those topics — see
[SSL / TLS](/documentation/security/ssl-tls/),
[Authentication](/documentation/security/authentication/), and
[Timeouts](/documentation/connect/timeouts/).

## Related

- [Connection properties reference](/documentation/reference/connection-properties/)
  — every property the driver recognises.
- [executeBatch hangs without an error](/documentation/troubleshooting/executebatch-hangs/)
  — the per-batch socket-buffer deadlock and how
  `reWriteBatchedInserts` interacts with it.
- [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/)
  — the full recipe for `tcpKeepAlive` + `socketTimeout` + pool validation.
