---
title: "cached plan must not change result type"
date: 2026-05-16T00:00:00Z
draft: false
weight: 7
toc: true
last_reviewed: "2026-05-21"
description: "The two server-prepared-statement errors a JDBC client sees when the catalogue or backend session moves under a cached plan, with the `flushCacheOnDdl`, `autosave`, `prepareThreshold`, and PgBouncer settings that resolve them."
---

Two server-side errors hit the same nerve in a JDBC application that
relies on server-prepared statements:

```
ERROR: cached plan must not change result type
ERROR: prepared statement "S_2" does not exist
```

Both arise when the **server-side prepared statement** stored against
a name (e.g., `S_2`) is no longer valid against the current catalogue
or session state. The first is `SQLState = 0A000`
(`FEATURE_NOT_SUPPORTED`); the second is `SQLState = 26000`
(`INVALID_SQL_STATEMENT_NAME`). The driver recognises both as
"reparse-and-retry" candidates; see
[`QueryExecutorBase.willHealViaReparse`](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/core/QueryExecutorBase.java#L418)
for the exact decision.

## What server-prepared statements are

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 688-698
- Server-prepared statements | docs/content/documentation/query/prepared-statements.md | 42-66
{{< /review >}}

A `PreparedStatement` that reaches
[`prepareThreshold`](/documentation/reference/connection-properties/#prop-preparethreshold)
executions (default 5) gets a real named `PARSE` on the backend;
subsequent executions only `BIND` and `EXECUTE` against that name.
The server holds onto the parsed plan under a generated name (`S_1`,
`S_2`, …), and the driver tracks the same name. The plan now has a
lifecycle: it can be invalidated by catalogue changes, backend-session
state changes, or the backend connection disappearing.

## When the cached plan no longer matches

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- QueryExecutorImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java | 2493-2538
- Connection pooling | docs/content/documentation/connect/connection-pooling.md | 178-185
- AutoRollbackTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc2/AutoRollbackTest.java | 154-166
{{< /review >}}

In rough order of frequency:

- **Schema migration mid-flight.** `ALTER TABLE … ADD COLUMN`,
  `ALTER TABLE … ALTER COLUMN TYPE`, dropping a view and recreating
  it, renaming a column: anything that changes the result columns of
  a `SELECT` the application has already prepared can make an old
  plan fail with `cached plan must not change result type`. In this
  branch the default [`flushCacheOnDdl`](#flushcacheonddl-default-true)
  setting invalidates the driver's prepared-statement cache when the
  same session observes a top-level `CREATE`, `DROP`, or `ALTER`
  command tag, so the next execute re-prepares instead of using the
  stale plan.
- **`SEARCH_PATH` change.** The first execution resolved table
  `users` to `myapp.users`; a later `SET search_path = …` makes the
  same query resolve to a different relation. The driver detects
  `SET … search_path …` and bumps an internal `deallocateEpoch` so
  fresh PARSE messages are emitted, but only for the connection
  that issued the SET. A SET applied via a server-side trigger or by
  another session sharing the same prepared statement (rare) does
  not trigger the invalidation.
- **`DEALLOCATE ALL` or `DISCARD ALL` on the server.** Same
  mechanism: the driver watches the command tag, bumps the epoch,
  re-PARSEs on next execute. Useful when the application itself
  doesn't issue these, but a pool's `reset query` does.
- **PgBouncer in transaction (or statement) pooling mode.** A
  connection returned to the pool can be re-checked-out by a
  different session backed by a *different* server connection. The
  server-prepared statements live on the backend connection, not on
  the pooled client connection, so the next execute lands on a
  server that has never seen the prepared statement.
- **Server restart / failover.** The new backend has no prepared
  statements. Same surface error as PgBouncer.

## Fixes

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 320-337
- PgConnection.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgConnection.java | 307-310
- QueryExecutor.java | pgjdbc/src/main/java/org/postgresql/core/QueryExecutor.java | 574-594
{{< /review >}}

These address the cause: either the prepared statement is not being
preserved across the pooling boundary, or the application is mutating
the catalogue without telling its pool.

### `flushCacheOnDdl` (default `true`)

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- QueryExecutorImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java | 2493-2510
- AutoRollbackTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc2/AutoRollbackTest.java | 388-400
{{< /review >}}

For DDL issued through the same pgJDBC connection, the default fix is
already enabled. When the backend returns a `CREATE`, `DROP`, or
`ALTER` command tag, pgJDBC invalidates its prepared-statement cache
so subsequent executions are re-parsed against the current catalogue.
Set `flushCacheOnDdl=false` only when you intentionally need the
legacy behaviour, where `cached plan must not change result type` can
surface and transparent recovery depends on `autosave`.

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

When DDL can happen outside the pgJDBC connection that owns the
prepared statements, close and re-open the affected connections after
the migration finishes. The pool then rebuilds connections that never
saw the pre-migration catalogue, so no cached plan exists to
invalidate.

## Workarounds

Sometimes the root cause is out of reach: a third-party application
issues the migration, the PgBouncer upgrade is pending, the
deployment cannot evict pool connections. The driver-side knobs
below let the driver tolerate the invalidation instead of resolving
it. They are not free: each pays a cost beyond the statements that
would have failed.

### `autosave=conservative`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- QueryExecutorImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java | 49-54
- QueryExecutorImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java | 480-571
- QueryExecutorBase.java | pgjdbc/src/main/java/org/postgresql/core/QueryExecutorBase.java | 418-454
{{< /review >}}

The most surgical of the three. With
[`autosave=conservative`](/documentation/reference/connection-properties/#prop-autosave),
the driver places an automatic savepoint before statements that can
hit this class of failure while a transaction is active. When the
statement fails with one of the recoverable errors (`cached plan must
not change result type`, `prepared statement "S_X" does not exist`),
the driver rolls back to the savepoint so the statement can be
reparsed and retried transparently. From the application's
perspective the statement simply succeeds.

`willHealViaReparse` is the gate: it checks for either
`SQLState 26000` *or* `SQLState 0A000` paired with the
`RevalidateCachedQuery` / `RevalidateCachedPlan` server routine.
Random `0A000` errors that are not cached-plan invalidations are
**not** retried. Savepoint overhead is paid, but unrelated failures
still surface to the application.

The CONSERVATIVE mode sends extra savepoint traffic for statements
that might fail this way in an active transaction. `autosave=always`
uses automatic savepoints more broadly and rolls back on any error;
`autosave=never` (the default) gives no savepoint-based recovery.

### `prepareThreshold=0`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- Server-prepared statements | docs/content/documentation/query/prepared-statements.md | 95-116
- Connection pooling | docs/content/documentation/connect/connection-pooling.md | 182-185
{{< /review >}}

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

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 646-662
- Server-prepared statements | docs/content/documentation/query/prepared-statements.md | 129-144
{{< /review >}}

The most blunt option:
[`preferQueryMode=simple`](/documentation/reference/connection-properties/#prop-preferquerymode)
uses the simple PostgreSQL protocol with no `PARSE` / `BIND` /
`EXECUTE` cycle at all; parameters are interpolated on the client.
Server-prepared statements aren't possible, so neither is this
error. The trade-offs are larger than `prepareThreshold=0` (no
binary transfer, weaker parameter typing); pick simple mode only if
the deployment also blocks the extended protocol for other reasons.

## Related connection properties

{{< param-table data="connection-properties" tag="prepared_statements" >}}

## Related

- [Server-prepared statements](/documentation/query/prepared-statements/):
  how the `prepareThreshold`, `preparedStatementCacheQueries`,
  `preparedStatementCacheSizeMiB`, and `binaryTransfer` properties
  interact in a working configuration.
- [DataSource and JNDI](/documentation/connect/datasource/):
  where PgBouncer and pool-eviction strategies fit in.
- [Connection properties reference](/documentation/reference/connection-properties/):
  `autosave`, `prepareThreshold`, `preferQueryMode`,
  `cleanupSavepoints`.
