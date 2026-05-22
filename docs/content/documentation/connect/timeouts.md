---
title: "Timeouts"
weight: 12
toc: true
last_reviewed: "2026-05-21"
description: "Driver-side timeouts that bound each phase of a connection (TCP connect, SSL / GSS upgrade, wall-clock login, socket reads, query cancel), the JDBC-spec timeouts layered on top, and where the defaults leave gaps under a pool."
---

A pgJDBC connection walks through a handful of distinct phases, and each phase is bounded by its own timeout: the TCP handshake, the optional SSL or GSS upgrade, the authentication round-trips, individual socket reads once the connection is steady-state, individual query executions, and the out-of-band cancel side-channel. The defaults are not aligned with each other. Misconfigured, the knobs overlap or leave gaps that hang threads for minutes.

For the **operational** side (why an idle connection dies between checkout and use and which fix actually applies), see [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/).

## A timeline of one connection

A single `DriverManager.getConnection(url, props)` call passes through these phases in order. The right column names the timeout that caps each one.

| # | Phase | Bounded by |
|---|---|---|
| 1 | TCP `connect()` to each candidate host | `connectTimeout` (default `10` s) |
| 2 | One-byte response to `SSLRequest`, if requested | `sslResponseTimeout` (default `5000` ms), or a smaller active socket timeout |
| 3 | One-byte response to `GSSENCRequest`, if requested | `gssResponseTimeout` (default `5000` ms), or a smaller active socket timeout |
| 4 | Authentication round-trips, startup packet, post-upgrade handshake | `socketTimeout` if non-zero; otherwise no dedicated phase timeout |
| 5 | Steady-state socket reads (every read after the connection is established) | `socketTimeout` (default `0` = wait forever) |
| 6 | Per-statement query execution | `Statement.setQueryTimeout(int)` (default unset) |
| 7 | Out-of-band cancel for `Statement.cancel()` or a query-timeout fire | `cancelSignalTimeout` (default `10` s) |

Phases 1–4 are additionally wrapped by `loginTimeout` (default `0` = unlimited). The driver runs the whole connection attempt in a daemon thread, and the calling thread waits on a condition variable for at most `loginTimeout`. Once the connection is established, phases 5–7 are the knobs that matter in normal application use.

## Driver-side properties

### `connectTimeout`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 193-206
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 321-389
{{< /review >}}

[`connectTimeout`](/documentation/reference/connection-properties/#prop-connecttimeout) (seconds, default `10`) is the timeout passed to the TCP `connect()` syscall on each candidate host. A value of `0` leaves the connect timeout to the platform socket defaults.

For a multi-host URL the budget is **shared across hosts**. The driver tracks time elapsed since the start of `getConnection()`. The timeout passed to the next host is `max(0, connectTimeout − elapsed)`. With `connectTimeout=10` against three unreachable hosts, the first attempt gets up to 10 s, the second gets the remainder, and the third may get 0 s and fail immediately. There is no built-in way to get a fresh budget per host; `loginTimeout` is a separate wall-clock cap on the whole call, not a reset point. If per-host budgets matter, drive retries from a higher layer (a connection-pool retry policy, or your application's own `getConnection` loop) rather than relying on the driver to redistribute the time.

This shared-budget behaviour was introduced in **pgJDBC 42.7.7** ([commit `2a8772f0e`](https://github.com/pgjdbc/pgjdbc/commit/2a8772f0e)). Earlier versions started a fresh `connectTimeout` per host, so two unreachable hosts in a row would burn `2 × connectTimeout` before the driver gave up. On those drivers the wall-clock cap of the whole `getConnection()` call was `loginTimeout` only, and the standard remedy was to set `loginTimeout ≥ #hosts × connectTimeout + ε`. That value is still safe on 42.7.7+, and only becomes redundant once `connectTimeout` itself caps the whole TCP phase.

### `loginTimeout`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- Driver.java | pgjdbc/src/main/java/org/postgresql/Driver.java | 288-305
- Driver.java | pgjdbc/src/main/java/org/postgresql/Driver.java | 341-376
{{< /review >}}

[`loginTimeout`](/documentation/reference/connection-properties/#prop-logintimeout) (seconds, default `0` = unlimited) is the wall-clock cap on the *entire* `getConnection()` call: TCP, SSL/GSS upgrade, authentication, and the startup packet exchange.

Enforcement is structural rather than cooperative: the driver spawns a daemon `Thread` that performs the actual connection work while the calling thread waits on a condition variable with the timeout. When the timeout fires:

- The caller gets `PSQLException` with `SQLState 08001` (`CONNECTION_UNABLE_TO_CONNECT`) and message `Connection attempt timed out.`.
- The daemon thread **keeps running** in the background. TCP retries against a non-responsive server are not interruptible. If the connection eventually succeeds, the daemon closes it. If it eventually fails, the error is discarded.

The implication: a flood of login timeouts against a hung server leaves a comparable flood of daemon threads alive. Either set `connectTimeout` low enough that those daemons finish quickly, or accept the cost in thread count.

If neither the `loginTimeout` URL property nor a `Properties` entry is set, the driver falls back to `DriverManager.getLoginTimeout()` (the process-wide JDBC-spec setting).

### `sslResponseTimeout` and `gssResponseTimeout`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 568-587
- MakeSSL.java | pgjdbc/src/main/java/org/postgresql/ssl/MakeSSL.java | 37-54
{{< /review >}}

[`sslResponseTimeout`](/documentation/reference/connection-properties/#prop-sslresponsetimeout) and [`gssResponseTimeout`](/documentation/reference/connection-properties/#prop-gssresponsetimeout) (milliseconds, default `5000` each) bound the wait for the server's single-byte response to `SSLRequest` and `GSSENCRequest` respectively. Without them, a server that fails over at that point can leave the client blocked on a one-byte read indefinitely.

These properties only cover the one-byte "do you speak SSL/GSS encryption?" response. The driver temporarily sets `SO_TIMEOUT` to the response-timeout value for that read, unless an even smaller socket timeout is already active. They do **not** bound the rest of the TLS/GSS handshake. For TLS specifically, once the server replies `S`, pgJDBC hands control to JSSE. During `startHandshake()` the driver temporarily sets the SSL socket's read timeout from `connectTimeout`. After the handshake, it restores `socketTimeout`.

[Kerberos, GSSAPI, SSPI](/documentation/security/kerberos-gssapi/) covers the broader GSS-encryption story (when it runs, how it interacts with `sslmode`).

### `socketTimeout`

[`socketTimeout`](/documentation/reference/connection-properties/#prop-sockettimeout) (seconds, default `0` = wait forever) sets `SO_TIMEOUT` on the connected socket. During normal query execution and result reading, a read longer than this surfaces as `java.net.SocketTimeoutException`, which the driver wraps into a `PSQLException` with `SQLState 08006` (`CONNECTION_FAILURE`).

This is the load-bearing timeout for the **steady-state** of an open connection. When set via URL or `Properties` (before connect), it also bounds startup and authentication reads. Consider a connection that survives `loginTimeout`, then sits idle in a pool while a load balancer kills the underlying TCP. If `socketTimeout` is set, the application notices the kill on the first read after checkout. Otherwise it waits indefinitely on that read.

The operational details (what value to pick, interaction with pool validation and `tcpKeepAlive`, kernel keepalive defaults) are in [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/).

### `cancelSignalTimeout`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- QueryExecutorBase.java | pgjdbc/src/main/java/org/postgresql/core/QueryExecutorBase.java | 193-218
{{< /review >}}

[`cancelSignalTimeout`](/documentation/reference/connection-properties/#prop-cancelsignaltimeout) (seconds, default `10`) is used as **both** the connect timeout and the socket timeout for the *out-of-band cancel command*. A `Statement.cancel()` (or a query-timeout fire; see below) opens a fresh TCP connection to the same PostgreSQL `host:port`, sends a `CancelRequest` message, and closes the socket. The whole flow is bounded by this property.

If the cancel side-channel cannot be reached (the server port is unreachable, the backend has crashed, or the network path for the second TCP connection is broken), the cancel attempt itself times out without affecting the original connection. The query continues to run on the server until it finishes naturally or until some other timeout (`socketTimeout` on the client, `statement_timeout` on the server) intervenes.

## JDBC-spec timeouts

### `Statement.setQueryTimeout(int seconds)`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PgStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java | 616-645
- PgStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java | 1038-1058
- QueryExecutorBase.java | pgjdbc/src/main/java/org/postgresql/core/QueryExecutorBase.java | 193-218
{{< /review >}}

When you call `setQueryTimeout(N)`, the driver schedules a `StatementCancelTimerTask` on a shared `Timer`. If the query has not completed after `N` seconds, the task fires `Statement.cancel()`, which opens a cancel side-channel subject to `cancelSignalTimeout`.

The key thing to understand: `setQueryTimeout` is a **cancel request**, not a read deadline. The original connection's read is still blocked on the server's response. If the server is willing and able to cancel, the read unblocks with a `PSQLException` (`ERROR: canceling statement due to user request`). If the server is unreachable for the cancel (or the cancel side-channel itself times out), the read continues until `socketTimeout` fires, the server replies on its own, or the connection is otherwise closed or aborted.

For belt-and-braces, layer `setQueryTimeout` with the server-side [`statement_timeout`](https://www.postgresql.org/docs/current/runtime-config-client.html#GUC-STATEMENT-TIMEOUT) GUC: the server enforces the latter unconditionally, independent of the client's ability to deliver a cancel.

### `DriverManager.setLoginTimeout(int seconds)`

The JDBC-spec API for "how long to wait for `getConnection()`". pgJDBC reads `DriverManager.getLoginTimeout()` at the start of every connection attempt and uses it as the `loginTimeout` default when no explicit `loginTimeout` URL parameter or `Properties` entry is provided. Setting either to `0` disables the wall-clock cap.

## Interaction with a connection pool (HikariCP)

A pool layers a *client-side* wait ("how long the application thread will block in `pool.getConnection()`") on top of the driver-side timeouts. The two are not the same knob. On HikariCP the pool feeds its own `connectionTimeout` into the driver's login-timeout machinery, and the way it does so differs by configuration style. That difference in scope and precedence is a common source of "why didn't my timeout fire" tickets.

### HikariCP sets `loginTimeout` differently in its two configuration styles
{{< review date="2026-05-21" rev="bba167f0a28905e8e63083cd7b5cbf479263271a" >}}
- PoolBase.java | brettwooldridge/HikariCP | src/main/java/com/zaxxer/hikari/pool/PoolBase.java | 323-349
- PoolBase.java | brettwooldridge/HikariCP | src/main/java/com/zaxxer/hikari/pool/PoolBase.java | 639-643
- DriverDataSource.java | brettwooldridge/HikariCP | src/main/java/com/zaxxer/hikari/util/DriverDataSource.java | 127-162
{{< /review >}}

When HikariCP is configured with `dataSourceClassName=org.postgresql.ds.PGSimpleDataSource` (the [HikariCP-recommended](https://github.com/brettwooldridge/HikariCP#popular-datasource-class-names) form), it calls `DataSource.setLoginTimeout(seconds)` while initialising the underlying `DataSource`. The value comes from `HikariConfig.connectionTimeout`, computed as roughly `max(1, (connectionTimeout + 500) / 1000)`. Since `HikariConfig.connectionTimeout` defaults to **30 000 ms**, pgJDBC sees a `loginTimeout` of about `30 s` unless you set `HikariConfig.connectionTimeout` explicitly.

When HikariCP is configured with `jdbcUrl` (the Spring Boot autoconfiguration form), it still calls `setLoginTimeout`, but now on HikariCP's internal `DriverDataSource` wrapper. That wrapper forwards the value to `DriverManager.setLoginTimeout(seconds)` (process-wide) rather than storing it on a pgJDBC `DataSource` instance. pgJDBC then uses that value only as the fallback default when no explicit `loginTimeout` URL parameter or `Properties` entry is present.

The practical rule is still: **set `loginTimeout` explicitly as a driver property** (`spring.datasource.hikari.data-source-properties.loginTimeout=…` in Spring Boot, or via URL parameter). That makes the driver-side wall-clock cap explicit instead of depending on HikariCP's configuration style and `DriverManager` fallback semantics. On the `dataSourceClassName` path, size `HikariConfig.connectionTimeout` deliberately as well, because HikariCP derives the `DataSource`'s `loginTimeout` from it.

### Initial connectivity checks are outside the borrow timeout

`HikariConfig.connectionTimeout` is the client-side wait for `pool.getConnection()`: it bounds how long an application thread blocks waiting to **borrow** a pooled connection. It does **not** directly wrap a single physical JDBC connect attempt. HikariCP's fail-fast check (`initializationFailTimeout >= 0`, the default) and background pool-fill path create physical connections outside that borrow-timeout path. Those attempts are bounded by the driver-side timeouts (`connectTimeout`, `loginTimeout`), plus any translation HikariCP has already performed via `setLoginTimeout`. If those leave the driver waiting indefinitely on a half-open multi-host URL, application startup or refill can still hang irrespective of the pool's borrow timeout.

### Sizing `connectionTimeout` against `loginTimeout`

The key point is not that the two settings are equal; it is that they control different layers and HikariCP may map one onto the other. In practice:

- Keep `HikariConfig.connectionTimeout` aligned with how long callers should wait in `pool.getConnection()`.
- Set pgJDBC `loginTimeout` explicitly to the wall-clock cap you want for one physical connect attempt.
- On the `dataSourceClassName` path, remember that HikariCP also derives `DataSource.loginTimeout` from `connectionTimeout`, so an unexpectedly small `connectionTimeout` can shorten the driver's effective login budget.

## Host status tracker and `hostRecheckSeconds`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- GlobalHostStatusTracker.java | pgjdbc/src/main/java/org/postgresql/hostchooser/GlobalHostStatusTracker.java | 32-41
- GlobalHostStatusTracker.java | pgjdbc/src/main/java/org/postgresql/hostchooser/GlobalHostStatusTracker.java | 53-60
- MultiHostChooser.java | pgjdbc/src/main/java/org/postgresql/hostchooser/MultiHostChooser.java | 30-36
- MultiHostChooser.java | pgjdbc/src/main/java/org/postgresql/hostchooser/MultiHostChooser.java | 87-91
{{< /review >}}

For a multi-host URL, the driver caches each host's last-known status (`ConnectOK`, `ConnectFail`, `Primary`, `Secondary`) in a JVM-wide `GlobalHostStatusTracker` so subsequent connection attempts can skip hosts known to be unreachable instead of re-paying the full `connectTimeout`. The cache entry lives for [`hostRecheckSeconds`](/documentation/reference/connection-properties/#prop-hostrecheckseconds) (seconds, default `10`).

On the next `getConnection()`, hosts cached as `ConnectFail` are skipped until their `hostRecheckSeconds` TTL expires. With the default `10`, a dead host is typically re-probed after about ten seconds, not on every checkout. Raise the value if you want the driver to stay away from recently-failed hosts longer; lower it if you want it to re-probe sooner after failover or recovery.

`hostRecheckSeconds` is not itself a timeout. It is the TTL on cached host state. The right value depends on how aggressively you want the driver to re-probe failed hosts relative to your retry cadence and fail-over expectations. [Connection fail-over](/documentation/connect/failover/) covers the fail-over story it participates in.

## Common pitfalls

- **Default `socketTimeout=0` plus default `loginTimeout=0` means a non-responsive server hangs the calling thread indefinitely.** A production checklist should set both to non-zero values consistent with the SLAs around the call site.
- **`loginTimeout` shorter than `connectTimeout`** is a usable pattern (the wall-clock budget caps the whole attempt regardless of per-phase configuration). The inverse, `loginTimeout` much longer than `connectTimeout + sslResponseTimeout + auth`, typically means `loginTimeout` never fires and the individual phase timeouts do all the work. Either is fine; just be deliberate about which is the load-bearing knob.
- **`setQueryTimeout` without verifying the cancel path** is a quiet failure. The timeout fires, the cancel is sent, but if the second TCP connection cannot reach the server's normal listener the original query keeps running on the server. Either confirm the cancel path is open in your network, or rely on the server-side `statement_timeout` instead.
- **Multi-host URLs share the `connectTimeout` budget.** With three unreachable hosts and `connectTimeout=10`, the third host may get only the leftover milliseconds. There is no driver-side switch for per-host budgets; retry from a higher layer if you need that. On pre-42.7.7 drivers the opposite was true (a fresh `connectTimeout` per host), and the safe sizing was `loginTimeout ≥ #hosts × connectTimeout + ε`.
- **`hostRecheckSeconds` is host-status TTL, not an attempt timeout.** It controls how long a failed host is skipped on subsequent connection attempts. With the default `10`, the driver will usually re-probe a dead host after about ten seconds. Tune it based on how aggressively you want to revisit recently-failed nodes. See [Host status tracker and `hostRecheckSeconds`](#host-status-tracker-and-hostrecheckseconds).
- **HikariCP's `connectionTimeout` is not the driver's `loginTimeout`.** The pool may translate `connectionTimeout` into a driver login timeout, but it does so differently in `dataSourceClassName` and `jdbcUrl` configurations. Its fail-fast / refill connection attempts also run outside the borrow-timeout path. Set `loginTimeout` as a driver property if you want a deterministic wall-clock cap, and size `connectionTimeout` intentionally rather than treating it as a synonym. See [Interaction with a connection pool (HikariCP)](#interaction-with-a-connection-pool-hikaricp).

## Related connection properties

{{< param-table data="connection-properties" tag="timeout" >}}

## Related

- [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/): the operational side, idle connections being killed by network or server-side limits, and the recipe that combines `socketTimeout` with `tcpKeepAlive` and pool validation.
- [Connection fail-over](/documentation/connect/failover/): multi-host URLs, `targetServerType`, `loadBalanceHosts`, and what the host status tracker actually caches.
- [Connection properties reference](/documentation/reference/connection-properties/): every property in one place.
