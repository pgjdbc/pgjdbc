---
title: "Recommended properties"
date: 2026-05-20T00:00:00Z
draft: false
weight: 7
toc: true
last_reviewed: "2026-05-21"
description: "Connection properties worth turning on in production: batch INSERT rewriting, TCP keep-alive, application_name tagging, skipping the startup version round-trip, and load-balancing across replicas."
---

The driver ships with conservative defaults — every behaviour change that
could surprise an existing application is off until you ask for it.
That is the right default for a library that has been around since
JDBC 1, but it also means a fresh install leaves several big wins on
the table.

The properties below are the ones worth setting deliberately on most
production deployments. Each comes with one specific caveat (what is
holding it back from being on by default), so you can decide rather
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

**Why it is off by default.** The `int[]` returned by
`PreparedStatement.executeBatch()` still complies with the JDBC
specification; however, application code may need updates if it
relied on accurate per-row update counts. With the rewrite, the
server returns a single aggregated row count for the whole multi-row
INSERT, which cannot be attributed back to the individual statements
in the batch. The driver therefore reports
[`Statement.SUCCESS_NO_INFO`](https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/Statement.html#SUCCESS_NO_INFO)
(`-2`) for each entry in the returned `int[]` instead of `1`. Code
that inspects the array to detect partial failures, count inserted
rows, or pair counts back with input rows needs to handle
`SUCCESS_NO_INFO` first (see
[`BatchResultHandler.uncompressUpdateCount`](https://github.com/pgjdbc/pgjdbc/blob/bd1af18230371879fb4127ae28800cf9a8a8c77d/pgjdbc/src/main/java/org/postgresql/jdbc/BatchResultHandler.java#L223)).
The full mechanics (when the rewriter applies, how it groups rows,
what the `executeBatch()` array looks like in practice, and the
`pg_stat_statements` trade-off) are covered in
[Batch INSERT rewriting](/documentation/query/batch-inserts/).
INSERTs the rewriter cannot handle (e.g. `RETURNING`) fall back to
one round-trip per row automatically, so the property is safe to
enable globally even for code paths that do not benefit.

## `tcpKeepAlive=true` — survive NAT and load balancers

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 225-233
{{< /review >}}

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
sockets actually die behind a load balancer, and which fix applies,
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

## `assumeMinServerVersion=9.0` — skip a startup round-trip

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 464-479
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 1096-1118
{{< /review >}}

[`assumeMinServerVersion`](/documentation/reference/connection-properties/#prop-assumeminserverversion)
tells the driver that the server is at least the given version. When
the value is `9.0` or higher, the driver bundles version-gated
startup parameters (most notably `application_name`) into the
initial startup message instead of issuing them later as separate
`SET` commands.

```properties
jdbc.url=jdbc:postgresql://db.example/app?assumeMinServerVersion=9.0&ApplicationName=orders-api
```

In practice this saves one `SET` round-trip per `getConnection()`.
On a same-DC network that is on the order of milliseconds per
connection; across a wider network or through a connection pool that
opens and discards sockets aggressively the savings stack up.

It also matters when the database sits behind PgBouncer in
**transaction pooling** mode: bundling everything into the startup
message avoids an extra post-connect `SET application_name` command
entirely, which keeps more of the session setup in the startup packet
rather than in follow-up SQL.

**Why it is off by default.** The driver cannot assume any minimum
version — pgJDBC still nominally talks to very old servers, and a
wrong `assumeMinServerVersion` would silently send startup parameters
the server does not understand. Pick the lowest version your fleet
actually runs (e.g. `9.0`, `10`, `14`); the exact value is not
important so long as it is `≥ 9.0` and `≤` your minimum deployed
server.

## `loadBalanceHosts=true` — spread reads across replicas

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- MultiHostChooser.java | pgjdbc/src/main/java/org/postgresql/hostchooser/MultiHostChooser.java | 42-54
- MultiHostChooser.java | pgjdbc/src/main/java/org/postgresql/hostchooser/MultiHostChooser.java | 57-93
{{< /review >}}

[`loadBalanceHosts`](/documentation/reference/connection-properties/#prop-loadbalancehosts)
randomises the order in which the driver walks the host list in a
multi-host URL. With `targetServerType=preferSecondary` (or
`secondary`), this is the difference between every read connection
landing on the first replica and reads being spread across all
suitable replicas.

```properties
jdbc.url=jdbc:postgresql://node1,node2,node3/app?targetServerType=preferSecondary&loadBalanceHosts=true
```

The randomisation is a single
[`Collections.shuffle`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collections.html#shuffle\(java.util.List\))
of the candidate list at the start of each `getConnection()` — not
weighted, not aware of replica lag, not aware of node load. It is
"good enough" load shedding, not a replacement for a real-routing
proxy.

**Why it is off by default.** Some deployments rely on the URL order
being meaningful — for example, "the first host is the bigger box,
always prefer it." Enabling `loadBalanceHosts` would silently change
that. The fail-over story this property participates in is on
[Connection fail-over](/documentation/connect/failover/).

## All at once

The properties above are independent — set the ones that apply, leave
the rest. A typical JDBC URL for a service with a single host ends up
looking like:

```
jdbc:postgresql://db.example/app?reWriteBatchedInserts=true&tcpKeepAlive=true&ApplicationName=orders-api&assumeMinServerVersion=10
```

For a service that talks to a replicated cluster:

```
jdbc:postgresql://node1,node2,node3/app?reWriteBatchedInserts=true&tcpKeepAlive=true&ApplicationName=orders-api-ro&assumeMinServerVersion=10&targetServerType=preferSecondary&loadBalanceHosts=true
```

For SSL, authentication, and timeouts the corresponding recommendations
live with the rest of those topics — see
[SSL / TLS](/documentation/security/ssl-tls/),
[Authentication](/documentation/security/authentication/), and
[Timeouts](/documentation/connect/timeouts/).

## Related

- [Connection properties reference](/documentation/reference/connection-properties/)
  — every property the driver recognises.
- [Batch INSERT rewriting](/documentation/query/batch-inserts/) — the
  mechanics behind `reWriteBatchedInserts`: when it applies, how rows
  are grouped, what `executeBatch()` returns, and the trade-off with
  `pg_stat_statements`.
- [executeBatch hangs without an error](/documentation/troubleshooting/executebatch-hangs/)
  — the per-batch socket-buffer deadlock and how
  `reWriteBatchedInserts` interacts with it.
- [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/)
  — the full recipe for `tcpKeepAlive` + `socketTimeout` + pool validation.
- [Connection fail-over](/documentation/connect/failover/) — multi-host
  URLs, `targetServerType`, and how the driver tracks per-host status
  between attempts.
- [Timeouts](/documentation/connect/timeouts/) — `loginTimeout`,
  `connectTimeout`, and the HikariCP interaction worth getting right
  before going to production.
