---
title: "Timeouts"
weight: 12
toc: true
last_reviewed: "2026-05-16"
description: "The driver-side timeouts that bound the distinct phases of a connection — TCP connect, SSL / GSS upgrade, wall-clock login, socket reads, query cancel — and the JDBC-spec timeouts that layer on top. How they interact, where defaults leave gaps, and the operational pitfalls."
---

A pgJDBC connection walks through a handful of distinct phases, and each phase is bounded by its own timeout: the TCP handshake, the optional SSL or GSS upgrade, the authentication round-trips, individual socket reads once the connection is steady-state, individual query executions, and the out-of-band cancel side-channel. The defaults are not aligned with each other; misconfigured, the knobs overlap or leave gaps that hang threads for minutes.

This page maps each timeout to the phase it controls. For the **operational** side — why an idle connection dies between checkout and use and which fix actually applies — see [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/).

## A timeline of one connection

A single `DriverManager.getConnection(url, props)` call passes through these phases in order. The right column names the timeout that caps each one.

| # | Phase | Bounded by |
|---|---|---|
| 1 | TCP `connect()` to each candidate host | `connectTimeout` (default `10` s) |
| 2 | SSL upgrade, if requested | `sslResponseTimeout` (default `5000` ms), capped by remaining `connectTimeout` |
| 3 | GSS upgrade, if requested | `gssResponseTimeout` (default `5000` ms), same cap |
| 4 | Authentication round-trips, startup packet | no per-phase timeout |
| 5 | Steady-state socket reads (every read after the connection is established) | `socketTimeout` (default `0` = wait forever) |
| 6 | Per-statement query execution | `Statement.setQueryTimeout(int)` (default unset) |
| 7 | Out-of-band cancel for `Statement.cancel()` or a query-timeout fire | `cancelSignalTimeout` (default `10` s) |

Phases 1–4 are additionally wrapped by `loginTimeout` (default `0` = unlimited): the driver runs the whole connection attempt in a daemon thread and the calling thread waits on a condition variable for at most `loginTimeout`. Phases 5–7 only apply once the connection is in the application's hands.

## Driver-side properties

### `connectTimeout`

[`connectTimeout`](/documentation/reference/connection-properties/#prop-connecttimeout) (seconds, default `10`) is the timeout passed to the TCP `connect()` syscall on each candidate host. A value of `0` falls back to the kernel's SYN-retry behaviour (Linux: ~127 s before giving up).

For a multi-host URL the budget is **shared across hosts**: the driver tracks time elapsed since the start of `getConnection()` and the timeout passed to the next host is `max(0, connectTimeout − elapsed)`. With `connectTimeout=10` against three unreachable hosts, the first attempt gets up to 10 s, the second gets the remainder, and the third may get 0 s and fail immediately. There is no built-in way to get a fresh budget per host — `loginTimeout` is a separate wall-clock cap on the whole call, not a reset point. If per-host budgets matter, drive retries from a higher layer (a connection-pool retry policy, or your application's own `getConnection` loop) rather than relying on the driver to redistribute the time.

### `loginTimeout`

[`loginTimeout`](/documentation/reference/connection-properties/#prop-logintimeout) (seconds, default `0` = unlimited) is the wall-clock cap on the *entire* `getConnection()` call — TCP, SSL/GSS upgrade, authentication, and the startup packet exchange.

Enforcement is structural rather than cooperative: the driver spawns a daemon `Thread` that performs the actual connection work while the calling thread waits on a condition variable with the timeout. When the timeout fires:

- The caller gets `PSQLException` with `SQLState 08001` (`CONNECTION_UNABLE_TO_CONNECT`) and message `Connection attempt timed out.`.
- The daemon thread **keeps running** in the background. TCP retries against a non-responsive server are not interruptible; if the connection eventually succeeds the daemon closes it, and if it eventually fails the error is discarded.

The implication: a flood of login timeouts against a hung server leaves a comparable flood of daemon threads alive. Either set `connectTimeout` low enough that those daemons finish quickly, or accept the cost in thread count.

If neither the `loginTimeout` URL property nor a `Properties` entry is set, the driver falls back to `DriverManager.getLoginTimeout()` (the process-wide JDBC-spec setting).

### `sslResponseTimeout` and `gssResponseTimeout`

[`sslResponseTimeout`](/documentation/reference/connection-properties/#prop-sslresponsetimeout) and [`gssResponseTimeout`](/documentation/reference/connection-properties/#prop-gssresponsetimeout) (milliseconds, default `5000` each) bound the wait for the server's single-byte response to `SSLRequest` and `GSSENCRequest` respectively. Without them, a server that fails over mid-handshake leaves the client blocked on a one-byte read indefinitely.

Both are capped by the remaining `connectTimeout`: the driver picks `min(<this property>, remainingConnectTimeout)` so the wall-clock guarantee of `connectTimeout` is never violated by the upgrade step alone.

The broader GSS-encryption story (when it runs, how it interacts with `sslmode`) is on [Kerberos, GSSAPI, SSPI](/documentation/security/kerberos-gssapi/).

### `socketTimeout`

[`socketTimeout`](/documentation/reference/connection-properties/#prop-sockettimeout) (seconds, default `0` = wait forever) sets `SO_TIMEOUT` on the connected socket. Every read longer than this surfaces as `java.net.SocketTimeoutException`, which the driver wraps into a `PSQLException` with `SQLState 08006` (`CONNECTION_FAILURE`).

This is the only timeout that defends the **steady-state** of an open connection. A connection that survives `loginTimeout`, then sits idle in a pool while a load balancer kills the underlying TCP, then is checked out and used, only notices the kill if `socketTimeout` is set — otherwise the application waits indefinitely on the first read of the dead socket.

The operational details — what value to pick, interaction with pool validation and `tcpKeepAlive`, kernel keepalive defaults — are in [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/).

### `cancelSignalTimeout`

[`cancelSignalTimeout`](/documentation/reference/connection-properties/#prop-cancelsignaltimeout) (seconds, default `10`) is used as **both** the connect timeout and the socket timeout for the *out-of-band cancel command*. A `Statement.cancel()` (or a query-timeout fire — see below) opens a fresh TCP connection to the backend's cancel-key port, sends a `CancelRequest` message, and closes the socket; that whole flow is bounded by this property.

If the cancel side-channel cannot be reached — the cancel port is firewalled off, the backend has crashed, NAT has rewritten the cancel key — the cancel attempt itself times out without affecting the original connection. The query continues to run on the server until it finishes naturally or until some other timeout (`socketTimeout` on the client, `statement_timeout` on the server) intervenes.

## JDBC-spec timeouts

### `Statement.setQueryTimeout(int seconds)`

When you call `setQueryTimeout(N)`, the driver schedules a `StatementCancelTimerTask` on a shared `Timer`. If the query has not completed after `N` seconds, the task fires `Statement.cancel()`, which opens a cancel side-channel subject to `cancelSignalTimeout`.

The key thing to understand: `setQueryTimeout` is a **cancel request**, not a read deadline. The original connection's read is still blocked on the server's response. If the server is willing and able to cancel, the read unblocks with a `PSQLException` (`ERROR: canceling statement due to user request`). If the server is unreachable for the cancel — or the cancel side-channel itself times out — the read continues until `socketTimeout` fires, the server replies on its own, or the application thread is interrupted.

For belt-and-braces, layer `setQueryTimeout` with the server-side [`statement_timeout`](https://www.postgresql.org/docs/current/runtime-config-client.html#GUC-STATEMENT-TIMEOUT) GUC: the server enforces the latter unconditionally, independent of the client's ability to deliver a cancel.

### `DriverManager.setLoginTimeout(int seconds)`

The JDBC-spec API for "how long to wait for `getConnection()`". pgJDBC reads `DriverManager.getLoginTimeout()` at the start of every connection attempt and uses it as the `loginTimeout` default when no explicit `loginTimeout` URL parameter or `Properties` entry is provided. Setting either to `0` disables the wall-clock cap.

## Common pitfalls

- **Default `socketTimeout=0` plus default `loginTimeout=0` means a non-responsive server hangs the calling thread indefinitely.** A production checklist should set both to non-zero values consistent with the SLAs around the call site.
- **`loginTimeout` shorter than `connectTimeout`** is a usable pattern (the wall-clock budget caps the whole attempt regardless of per-phase configuration). The inverse — `loginTimeout` much longer than `connectTimeout + sslResponseTimeout + auth` — typically means `loginTimeout` never fires and the individual phase timeouts do all the work. Either is fine; just be deliberate about which is the load-bearing knob.
- **`setQueryTimeout` without verifying the cancel path** is a quiet failure. The timeout fires, the cancel is sent, but if the cancel cannot reach the backend's cancel port (separate connection on the same `host:port`, but it may traverse a different NAT or firewall rule) the original query keeps running on the server. Either confirm the cancel path is open in your network, or rely on the server-side `statement_timeout` instead.
- **Multi-host URLs share the `connectTimeout` budget.** With three unreachable hosts and `connectTimeout=10`, the third host may get only the leftover milliseconds. There is no driver-side switch for per-host budgets; retry from a higher layer if you need that.

## Related connection properties

{{< param-table data="connection-properties" tag="timeout" >}}

## Related

- [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/) — the operational side: idle connections being killed by network or server-side limits, and the recipe that combines `socketTimeout` with `tcpKeepAlive` and pool validation.
- [Connection properties reference](/documentation/reference/connection-properties/) — every property in one place.
