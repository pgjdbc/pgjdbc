---
title: "Connection fail-over"
description: "Multi-host JDBC URLs for HA: candidate iteration in URL order, `targetServerType` for primary or standby selection, the `loadBalanceHosts` shuffle, and the host-status cache that remembers failed nodes between attempts."
date: 2026-05-13T00:00:00Z
draft: false
weight: 20
toc: true
last_reviewed: "2026-05-22"
aliases:
    - "/documentation/use/#connection-fail-over/"
---

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- Driver.java (multi-host URL parsing) | pgjdbc/src/main/java/org/postgresql/Driver.java | 584-608
- ConnectionFactoryImpl.java (host iteration) | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 316-454
- MultiHostChooser.java | pgjdbc/src/main/java/org/postgresql/hostchooser/MultiHostChooser.java | 24-138
- HostRequirement.java (targetServerType) | pgjdbc/src/main/java/org/postgresql/hostchooser/HostRequirement.java | 13-78
- GlobalHostStatusTracker.java (host-status cache) | pgjdbc/src/main/java/org/postgresql/hostchooser/GlobalHostStatusTracker.java | 21-85
- PGProperty.TARGET_SERVER_TYPE | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 1091-1099
- PGProperty.LOAD_BALANCE_HOSTS | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 473-479
- PGProperty.HOST_RECHECK_SECONDS | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 429-435
{{< /review >}}

To support simple connection fail-over it is possible to define multiple endpoints (host and port pairs) in the connection
URL separated by commas. The driver will try to connect to each suitable endpoint in candidate order until the connection
succeeds. If none succeeds a normal connection exception is thrown.

Candidate order is URL order by default; with `loadBalanceHosts=true` the candidates are shuffled randomly before each
connection attempt. The `targetServerType` setting further filters which hosts are considered, and for the `prefer*`
variants it reorders the list so the preferred role is tried first.

The syntax for the connection URL is: `jdbc:postgresql://host1:port1,host2:port2/database`

The simple connection fail-over is useful when running against a high-availability PostgreSQL installation that has
replicated data on each node, for example a streaming-replication cluster fronted by Patroni, repmgr, or a
cloud-managed HA service.

For example, an application can create two connection pools. One data source is for writes, another for reads. The
write pool limits connections only to a primary node: `jdbc:postgresql://node1,node2,node3/accounting?targetServerType=primary`.

And the read pool balances connections between secondary nodes, but also allows connections to a primary if no
secondaries are available: `jdbc:postgresql://node1,node2,node3/accounting?targetServerType=preferSecondary&loadBalanceHosts=true`

`targetServerType` accepts:

| Value | Selected hosts | Falls back to the other role? |
|---|---|---|
| `any` (default) | Any reachable host. | n/a |
| `primary` | Only a host accepting writes (not in recovery, not read-only). | No. Fails if no primary is available. |
| `secondary` | Only a read-only standby. | No. Fails if no standby is available. |
| `preferPrimary` | Primaries first. | Yes. Falls back to secondaries. |
| `preferSecondary` | Secondaries first. | Yes. Falls back to primaries. |

The legacy aliases `master`, `slave`, `preferSlave` are still accepted but deprecated; use the modern names.

With `targetServerType=preferSecondary` (or `preferPrimary`), available preferred candidates are tried before the
fallback role. If no secondaries are available the primary is tried (and vice versa for `preferPrimary`). Strict
`targetServerType=secondary` (or `primary`) does not fall back to the other role: the connection fails if no host of
the required role is available.

Host status is cached process-wide in `GlobalHostStatusTracker` and re-checked after `hostRecheckSeconds`
(default `10`): a host marked "can't connect" is retried automatically once that window elapses. If every host is
currently marked "can't connect", the driver attempts every host in the URL anyway, in URL order (or shuffled if
`loadBalanceHosts=true`), as a last resort before giving up.
