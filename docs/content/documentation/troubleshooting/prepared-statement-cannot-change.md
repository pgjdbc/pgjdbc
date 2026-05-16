---
title: "cached plan must not change result type"
date: 2026-05-16T00:00:00Z
draft: false
weight: 7
toc: true
last_reviewed: "2026-05-16"
description: "The two server-prepared-statement errors a JDBC client sees when the catalog moves under a cached plan — and the autosave / prepareThreshold / PgBouncer settings that resolve them."
---

Two server-side errors hit the same nerve in a JDBC application that
relies on server-prepared statements:

```
ERROR: cached plan must not change result type
ERROR: prepared statement "S_2" does not exist
```

Both arise when the **server-side prepared statement** stored against
a name (e.g., `S_2`) is no longer valid against the current catalog
or session state. The first is `SQLState = 0A000`
(`FEATURE_NOT_SUPPORTED`); the second is `SQLState = 26000`
(`INVALID_SQL_STATEMENT_NAME`). The driver recognises both as
"reparse-and-retry" candidates — see
[`QueryExecutorBase.willHealViaReparse`](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/core/QueryExecutorBase.java#L417)
for the exact decision.

## What server-prepared statements are

A `PreparedStatement` that executes more than
[`prepareThreshold`](/documentation/reference/connection-properties/#prop-preparethreshold)
times (default 5) gets a real `PARSE` on the backend; subsequent
executions only `BIND` and `EXECUTE`. The server holds onto the parsed
plan under a generated name (`S_1`, `S_2`, …), and so does the
driver, in `org.postgresql.core.v3.SimpleQuery.statementName`. The
plan now has a lifecycle: it can be invalidated by anything that
changes the catalog it was parsed against.

## When the cached plan no longer matches

In rough order of frequency:

- **Schema migration mid-flight.** `ALTER TABLE … ADD COLUMN`,
  `ALTER TABLE … ALTER COLUMN TYPE`, dropping a view and recreating
  it, renaming a column — anything that changes the result columns
  of a `SELECT` the application has already prepared makes the next
  execute fail with `cached plan must not change result type`.
- **`SEARCH_PATH` change.** The first execution resolved table
  `users` to `myapp.users`; a later `SET search_path = …` makes the
  same query resolve to a different relation. The driver detects
  `SET … search_path …` and bumps an internal `deallocateEpoch` so
  fresh PARSE messages are emitted — but only for the connection
  that issued the SET. A SET applied via a server-side trigger or by
  another session sharing the same prepared statement (rare) does
  not trigger the invalidation.
- **`DEALLOCATE ALL` or `DISCARD ALL` on the server.** Same
  mechanism: the driver watches the command tag, bumps the epoch,
  re-PARSEs on next execute. Useful when the application itself
  doesn't issue these, but a pool's `reset query` does.
- **PgBouncer in transaction or statement pooling mode.** A
  connection returned to the pool can be re-checked-out by a
  different session backed by a *different* server connection. The
  server-prepared statements live on the backend connection, not on
  the pooled client connection, so the next execute lands on a
  server that has never seen the prepared statement.
- **Server restart / failover.** The new backend has no prepared
  statements. Same surface error as PgBouncer.

## Fixes

These address the cause: either the prepared statement is not being
preserved across the pooling boundary, or the application is mutating
the catalog without telling its pool.

### Make PgBouncer keep server-prepared statements

PgBouncer 1.21 (December 2023) added
[`max_prepared_statements`](https://www.pgbouncer.org/config.html#max_prepared_statements).
With a non-zero value, PgBouncer tracks prepared statements per
client and replays the `PARSE` against whichever server connection
hands out next. If your stack runs an older PgBouncer in
transaction-pool mode, upgrade first; otherwise the prepared
statement cache is effectively a per-checkout cache and the error is
guaranteed to fire under load.

### Schema-migration hygiene

When the application is the one doing the migration, the cleanest
recovery is to **close and re-open the affected connections**. A
connection pool's `evictionPolicy` can be triggered manually after
the migration finishes; HikariCP exposes `softEvict()` for this.
The pool then rebuilds connections that never saw the pre-migration
catalog, so no cached plan exists to invalidate.

## Workarounds

When the root cause is out of reach — a third-party application
issues the migration, the PgBouncer upgrade is pending, the
deployment cannot evict pool connections — these driver-side knobs
let the driver tolerate the invalidation instead of resolving it.
They are not free: each pays a measurable cost on every statement,
not just the ones that would have failed.

### `autosave=conservative`

The most surgical of the three. With
[`autosave=conservative`](/documentation/reference/connection-properties/#prop-autosave),
the driver wraps every statement in a `SAVEPOINT`. When the next
statement fails with one of the recoverable errors (`cached plan must
not change result type`, `prepared statement "S_X" does not exist`),
the driver rolls back to the savepoint, re-PARSEs, and re-executes
transparently. From the application's perspective the statement
simply succeeds.

`willHealViaReparse` is the gate: it checks for either
`SQLState 26000` *or* `SQLState 0A000` paired with the
`RevalidateCachedQuery` / `RevalidateCachedPlan` server routine.
Random `0A000` errors that are not cached-plan invalidations are
**not** retried — savepoint overhead is paid, but unrelated failures
still surface to the application.

The CONSERVATIVE mode pays one round trip per statement to set up the
savepoint. `autosave=always` rolls back unconditionally on any error
(broader recovery, same per-statement cost); `autosave=never` (the
default) gives no recovery.

### `prepareThreshold=0`

[`prepareThreshold=0`](/documentation/reference/connection-properties/#prop-preparethreshold)
disables server-side preparation altogether. Every statement is
parsed afresh; there is nothing to invalidate. Useful when a
deployment migrates the schema often, doesn't see enough repeats of
each query to benefit from caching anyway, or is constrained by
PgBouncer's pooling mode (see above).

The cost is real: a query that would have been served from cache
pays full parse and plan cost on every execute. Benchmark before
flipping.

### `preferQueryMode=simple`

The most blunt option:
[`preferQueryMode=simple`](/documentation/reference/connection-properties/#prop-preferquerymode)
uses the simple PostgreSQL protocol — no `PARSE` / `BIND` /
`EXECUTE` cycle at all, parameters are interpolated on the client.
Server-prepared statements aren't possible, so neither is this
error. The trade-offs are larger than `prepareThreshold=0` (no
binary transfer, weaker parameter typing); pick simple mode only if
the deployment also blocks the extended protocol for other reasons.

## Related connection properties

{{< param-table data="connection-properties" tag="prepared_statements" >}}

## Related

- [Server-prepared statements](/documentation/query/prepared-statements/)
  — how the `prepareThreshold`, `preparedStatementCacheQueries`,
  `preparedStatementCacheSizeMiB`, and `binaryTransfer` properties
  interact in a working configuration.
- [Connection Pools and Data Sources](/documentation/connect/datasource/)
  — where PgBouncer and pool-eviction strategies fit in.
- [Connection properties reference](/documentation/reference/connection-properties/)
  — `autosave`, `prepareThreshold`, `preferQueryMode`,
  `cleanupSavepoints`.
