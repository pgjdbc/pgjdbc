---
title: "Connection closed unexpectedly"
date: 2026-05-16T00:00:00Z
draft: false
weight: 6
toc: true
last_reviewed: "2026-05-21"
description: "Idle connections die between checkout and use — load balancer timeouts, server-side idle limits, OS keepalive defaults. What each error message means and which mitigation (pool validation, tcpKeepAlive, socketTimeout, server tcp_user_timeout) actually applies."
---

The error usually surfaces from `Statement.execute*` or
`PreparedStatement.execute*` on a connection your code thought was
fine, in one of a few canonical shapes:

```
org.postgresql.util.PSQLException: An I/O error occurred while sending to the backend.
  Caused by: java.io.EOFException
  Caused by: java.net.SocketException: Connection reset
  Caused by: java.net.SocketException: Broken pipe
  Caused by: java.net.SocketTimeoutException: Read timed out
```

All four wrap the same underlying truth: between the time the
connection was opened (or returned to the pool) and the time you
tried to use it, **something killed the TCP connection** and the JVM
only just noticed. The driver detects the dead socket on the next
read or write and translates it to `PSQLException` with `SQLState =
08006` (`CONNECTION_FAILURE`).

## Why the JVM didn't notice earlier

TCP is connection-oriented in theory; in practice, a JVM that holds
an idle socket has **no signal** that the remote endpoint is gone
until it tries to send or receive. There is no heartbeat on a vanilla
TCP socket. SO_KEEPALIVE exists, but the Linux defaults are
`tcp_keepalive_time = 7200` (2 hours of idle before the first probe)
plus 9 probes at 75 s apart, a full ~2 h 11 min before the kernel
declares a connection dead. Most failure modes that bite production
fire well below that ceiling.

## Where the kill comes from

In rough order of how often it shows up in real incidents:

- **Load balancer / NAT / firewall idle timeout.** AWS NLB defaults
  to 350 s of TCP idle (TLS terminates differently); GCP TCP LB
  defaults to 600 s; Kubernetes Service of type LoadBalancer
  inherits from the cloud provider; on-prem firewalls often sit at
  300–900 s. The middleware drops the connection silently (no RST
  is forwarded, sometimes nothing is), and both ends are left
  thinking the socket is still good.
- **Server-side idle terminator.** PostgreSQL's
  [`idle_session_timeout`](https://www.postgresql.org/docs/current/runtime-config-client.html#GUC-IDLE-SESSION-TIMEOUT)
  (PG 14+) and
  [`idle_in_transaction_session_timeout`](https://www.postgresql.org/docs/current/runtime-config-client.html#GUC-IDLE-IN-TRANSACTION-SESSION-TIMEOUT)
  end sessions cleanly with a FATAL message; the next operation
  surfaces an EOF.
- **PgBouncer.** `server_idle_timeout` (default 600 s) and
  `server_lifetime` (default 3600 s) close pooled server
  connections; the client-side socket survives but is now backed by
  nothing.
- **Server crash / OOM kill.** The backend process dies; the OS
  closes the socket with RST. Other connections see "Connection
  reset" on next use.
- **Network blip.** Route change, NIC bounce, container migration.
  TCP eventually retransmits, fails, and surfaces as
  "Connection reset" or, when the server has been reachable but
  silent, as a `SocketTimeoutException` if `socketTimeout` is set.

## How to fix it

The fixes split along one axis: **whether you discover the dead
connection** *before* you try to use it (pool validation, periodic
ping) or *while* you are using it (timeouts that bound the wait).
Both are worth combining.

### Connection-pool validation (most impactful)

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PgConnection.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgConnection.java | 1560-1601
- IsValidTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc4/IsValidTest.java | 23-64
{{< /review >}}

A pool that hands out connections without validating them is the
single biggest source of this error in modern stacks. HikariCP,
Tomcat JDBC, and c3p0 all support pre-checkout validation; turn it
on.

- **HikariCP.** Validation is on by default; the pool calls
  `Connection.isValid()` on checkout. The optional
  `connectionTestQuery` (e.g., `SELECT 1`) is needed only for
  drivers older than JDBC 4.0, and pgJDBC since 9.x implements
  `isValid()` natively. Leave `connectionTestQuery` unset and trust
  `isValid`.
- **Tomcat JDBC.** Set `testOnBorrow=true` together with
  `validationQuery=SELECT 1` and a `validationInterval` short enough
  to fire when LBs would idle-kill (e.g., 30 s).
- **Idle eviction.** Set the pool's idle timeout (HikariCP
  `idleTimeout`, default 10 min) below the LB / PgBouncer idle
  ceiling so connections are closed by your code before the network
  closes them out from under you.

### `tcpKeepAlive`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 1101-1110
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 230-232
- PGStream.java | pgjdbc/src/main/java/org/postgresql/core/PGStream.java | 169-177
{{< /review >}}

The property [`tcpKeepAlive=true`](/documentation/reference/connection-properties/#prop-tcpkeepalive)
enables the OS-level keepalive on the socket. It is a noticeable
improvement but **does not by itself defend against a 5-minute LB
timeout** unless you also lower the kernel keepalive defaults:

```
# /etc/sysctl.d/99-tcp-keepalive.conf  (Linux example)
net.ipv4.tcp_keepalive_time = 60      # first probe after 60 s of idle
net.ipv4.tcp_keepalive_intvl = 10     # probe every 10 s
net.ipv4.tcp_keepalive_probes = 6     # ≈ 60 s of probing before declaring dead
```

JVM-level overrides are not possible for these on Linux; the kernel
defaults apply to the socket. Containers inherit the host's sysctl
unless explicitly overridden.

### `socketTimeout`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 900-911
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 221-225
- PGStream.java | pgjdbc/src/main/java/org/postgresql/core/PGStream.java | 752-755
- SocketTimeoutTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc2/SocketTimeoutTest.java | 23-39
{{< /review >}}

[`socketTimeout`](/documentation/reference/connection-properties/#prop-sockettimeout)
sets `SO_TIMEOUT` on the socket: any read longer than this surfaces
as `SocketTimeoutException`. Useful for bounding the wait when the
server stops responding **mid-query** (so requests do not hang
indefinitely), but it does not help with **idle pooled connections**,
since there is no read in progress on those to time out.

A common safe pairing: `socketTimeout` long enough to cover the
slowest legitimate query, plus pool validation to catch idle
deaths.

### Server-side `tcp_user_timeout`

Since PostgreSQL 12, the server respects the Linux
`TCP_USER_TIMEOUT` socket option via the
[`tcp_user_timeout`](https://www.postgresql.org/docs/current/runtime-config-connection.html#GUC-TCP-USER-TIMEOUT)
GUC. Setting it (e.g., `60000` ms) makes the **server side** of the
TCP stack give up after the configured time when it cannot get an
ACK from the client, even if SO_KEEPALIVE is off. The client side
remains a separate concern: set
`net.ipv4.tcp_user_timeout` via sysctl, or rely on the keepalive
recipe above.

## Related connection properties

{{< param-table data="connection-properties" tag="network" >}}

## Related

- [Timeouts](/documentation/connect/timeouts/): the configuration
  companion to this page covers which timeout caps each phase of a
  connection, and the JDBC-spec `setQueryTimeout` /
  `setLoginTimeout` layer on top of the driver knobs.
- [executeBatch hangs without an error](/documentation/troubleshooting/executebatch-hangs/):
  the different "I/O stuck" failure mode where the TCP socket is
  *not* dead but both ends are deadlocked on full buffers.
- [Connection pooling](/documentation/connect/connection-pooling/):
  HikariCP / Tomcat JDBC / c3p0 production recipes (validation
  defaults, idle eviction, `tcpKeepAlive` under pool ownership),
  pool sizing, and `ApplicationName` per pool.
- [DataSource and JNDI](/documentation/connect/datasource/):
  the JDBC `DataSource` / `ConnectionPoolDataSource` API
  contracts and pgJDBC's bundled implementations.
- [Connection properties reference](/documentation/reference/connection-properties/):
  `tcpKeepAlive`, `socketTimeout`, `loginTimeout`,
  `cancelSignalTimeout`.
