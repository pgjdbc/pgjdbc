---
title: "Batch INSERT rewriting"
date: 2026-05-21T00:00:00Z
draft: false
weight: 50
toc: true
last_reviewed: "2026-05-21"
description: "How reWriteBatchedInserts collapses N single-row INSERTs into a multi-row INSERT, the precise conditions the rewriter looks for, the power-of-two grouping that determines what executeBatch() returns, and the pg_stat_statements trade-off."
---

The JDBC `PreparedStatement.executeBatch()` API hides what is, in
naive form, an N-round-trip operation: N executions of the same
prepared INSERT, each with its own protocol round-trip. Setting
[`reWriteBatchedInserts=true`](/documentation/reference/connection-properties/#prop-rewritebatchedinserts)
turns that into a handful of multi-row INSERTs **without any
application changes**, provided the statements look the way the
rewriter expects and provided you can live with the change in
`executeBatch()`'s return shape.

If you are willing to change application code, the
PostgreSQL-specific [COPY (CopyManager)](/documentation/postgresql-features/copy/)
API is faster still for bulk loads: it streams rows in the COPY
protocol rather than as parameterised INSERTs, sidestepping the per-row
parse / plan / bind overhead entirely. The trade-off is exactly that:
you stop using `PreparedStatement` and write to a `CopyManager`
stream. `reWriteBatchedInserts` is the "I want a faster batch without
touching the code" knob; COPY is the "I am loading enough rows that
restructuring the load path is worth it" path.

The sections below cover what the rewriter actually does, when it
kicks in, what `executeBatch()` returns after enabling it, and the
`pg_stat_statements` trade-off. The
[Recommended properties](/documentation/connect/recommended-properties/#rewritebatchedinsertstrue--batch-insert-throughput)
entry points here for the mechanics.

## The naive batch path

Without rewriting, `executeBatch()` over `INSERT INTO t (a, b) VALUES
(?, ?)` issues N independent `Bind` / `Execute` pairs for the same
prepared statement:

```
prepare:  INSERT INTO t (a, b) VALUES ($1, $2)
bind 1:   ($1=1, $2='a')   execute
bind 2:   ($1=2, $2='b')   execute
bind 3:   ($1=3, $2='c')   execute
...
```

The driver pipelines the writes, so the wall-clock cost is not
literally `N × round-trip`. The cost is still high, though: before
server-prepare warms up there is per-execution parse work, and even
after that the response stream still carries one `CommandComplete` per
row. Inserting 100 000 rows that way is slow even on a low-latency
network.

## What the rewriter does

With `reWriteBatchedInserts=true`, the driver collapses K rows whose
INSERT statement has the same text into one multi-row INSERT:

```
INSERT INTO t (a, b) VALUES ($1, $2), ($3, $4), ($5, $6), ..., ($2k-1, $2k)
```

That is one parse, one plan, one execution, one `CommandComplete` per
group of K rows. For INSERT-heavy workloads the speed-up is 1–2 orders
of magnitude, and it is one of the biggest single-property wins pgJDBC
offers.

## When the rewriter applies

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- SqlCommand.java | pgjdbc/src/main/java/org/postgresql/core/SqlCommand.java | 64-74
- ParserTest.java | pgjdbc/src/test/java/org/postgresql/core/ParserTest.java | 216-239
{{< /review >}}

The rewriter is conservative; it activates only when **all** of the
following hold (see
[`SqlCommand.java`](https://github.com/pgjdbc/pgjdbc/blob/bd1af18230371879fb4127ae28800cf9a8a8c77d/pgjdbc/src/main/java/org/postgresql/core/SqlCommand.java#L67-L74)):

1. The statement is an **`INSERT`**. `UPDATE`, `DELETE`, `SELECT`,
   `MERGE` (and other PostgreSQL-15 forms) are not rewritten.
2. The statement contains a parsable `VALUES (...)` clause. Forms
   like `INSERT INTO t SELECT ...` are left alone.
3. The statement does **not** contain a `RETURNING` clause. The
   driver cannot reliably attribute the returned rows back to the
   per-batch parameter sets after rewriting, so it falls back.
4. The statement is the only command in the JDBC `Statement` (no
   prior queries in the same prepared statement text).

The property is safe to enable globally: a batch that does not meet
the conditions falls back to the naive one-INSERT-per-row path
automatically. `ON CONFLICT (...) DO NOTHING` is rewrite-compatible.
`ON CONFLICT (...) DO UPDATE` is only sometimes compatible: if the
`DO UPDATE` clause introduces extra bind parameters (for example
`SET col = ?`), the parser marks the statement as not rewriteable; if
the update side uses constants only, the rewrite still applies.

## How rows are grouped

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- BatchedQuery.java | pgjdbc/src/main/java/org/postgresql/core/v3/BatchedQuery.java | 47-66
- PgPreparedStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgPreparedStatement.java | 1816-1848
{{< /review >}}

The driver does not concatenate all K rows into a single statement.
Doing so would create a fresh prepared statement text for every batch
size, blowing up the server-side plan cache. Instead, it builds
prepared statements for **power-of-two batch sizes** (2, 4, 8, 16, 32,
64, 128; seven rewritten sizes in total, see
[`BatchedQuery.java`](https://github.com/pgjdbc/pgjdbc/blob/bd1af18230371879fb4127ae28800cf9a8a8c77d/pgjdbc/src/main/java/org/postgresql/core/v3/BatchedQuery.java#L47-L69))
and packs the requested rows into the smallest set of those, plus the
original 1-row statement when needed.

For example, a batch of 50 rows is split into:

```
32-row INSERT   (one execution)
16-row INSERT   (one execution)
 2-row INSERT   (one execution)
```

That is three server-side prepared statements and three `CommandComplete`
responses. For low-bind INSERTs, a batch of 200 rows splits into
`128 + 64 + 8` (three executions); a batch of 1024 rows splits into
eight 128-row executions. The largest group size is capped at 128, and
for statements with many bind parameters the effective cap can be lower
because the rewritten SQL still has to fit under the driver's maximum
parameter count.

This grouping is the mechanism behind two observable behaviours: the
`executeBatch()` return shape, and the proliferation of normalised
forms in `pg_stat_statements`.

## What `executeBatch()` returns

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- BatchResultHandler.java | pgjdbc/src/main/java/org/postgresql/jdbc/BatchResultHandler.java | 231-252
{{< /review >}}

The JDBC contract for `PreparedStatement.executeBatch()` is an `int[]`
of length N, where entry `i` is either the affected-row count for
parameter set `i` or one of the sentinel values `EXECUTE_FAILED` /
`SUCCESS_NO_INFO`. Without rewriting, pgJDBC returns per-row counts
(typically all `1` for plain INSERTs).

With rewriting enabled, the server returns one aggregate count per
group. The driver cannot attribute that aggregate back to individual
rows, so it fills the corresponding `int[]` slice with one of two
values:

- `Statement.SUCCESS_NO_INFO` (`-2`) when the aggregate is positive,
  meaning "this group executed, but per-row counts are not available."
- `0` when the aggregate is zero, meaning "definitely no rows from
  this group were affected." This works because the count is summed
  across the group, so a `0` aggregate uniquely implies all-zero
  per-row counts.

Concrete examples, with the parameter sets and the resulting `int[]`:

```java
// Empty table, three plain INSERTs, all succeed.
//   batch [1, 2, 3]  →  groups: [2-row] + [1-row]
//   2-row group → aggregate 2 → SUCCESS_NO_INFO, SUCCESS_NO_INFO
//   1-row group → fallback to non-rewritten path → 1
// int[] = [-2, -2, 1]
```

```java
// Table already has rows 1 and 2; INSERT ... ON CONFLICT DO NOTHING
// with batch [1, 2, 4] → groups [2-row] + [1-row]
//   2-row group → aggregate 0 → 0, 0
//   1-row group → fallback → 1
// int[] = [0, 0, 1]
```

```java
// Same setup, batch [1, 2, 4, 5] → groups [4-row] (power-of-two fit)
//   4-row group → aggregate 2 (rows 4 and 5 inserted)
//                 → cannot attribute, so SUCCESS_NO_INFO across the whole group
// int[] = [-2, -2, -2, -2]
```

Code that inspects the array (to count inserts, detect partial
failures, or pair counts back with input rows) has to either:

- handle `SUCCESS_NO_INFO` and `0` as the only outcomes for any
  row in a rewritten group, or
- not enable `reWriteBatchedInserts` on those code paths (the
  property is per-connection, so a separate `DataSource` is a clean
  way to scope it).

The driver's helper for the un-rewritten case,
[`BatchResultHandler.uncompressUpdateCount`](https://github.com/pgjdbc/pgjdbc/blob/bd1af18230371879fb4127ae28800cf9a8a8c77d/pgjdbc/src/main/java/org/postgresql/jdbc/BatchResultHandler.java#L223),
shows where the propagation happens.

## The `pg_stat_statements` trade-off

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- BatchedQuery.java | pgjdbc/src/main/java/org/postgresql/core/v3/BatchedQuery.java | 47-66
- PgPreparedStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgPreparedStatement.java | 1816-1848
{{< /review >}}

A side effect of the power-of-two grouping is that
`pg_stat_statements` can now store up to **eight** normalised forms of
the same logical INSERT: the original 1-row form plus the seven
rewritten power-of-two forms.

```
INSERT INTO t (a, b) VALUES ($1, $2)
INSERT INTO t (a, b) VALUES ($1, $2), ($3, $4)
INSERT INTO t (a, b) VALUES ($1, $2), ($3, $4), ($5, $6), ($7, $8)
... up to 128 rows ...
```

A query that was a single row in `pg_stat_statements` becomes a
fan-out of up to eight rows whose `calls` and `total_exec_time` each tell a
different fraction of the story. To get aggregate behaviour for a
logical INSERT, you have to group on the table / column list rather
than the statement text. This is mostly an inconvenience for
operations dashboards (the throughput win is much larger than the
analytics cost), but it is worth knowing about before you start
chasing "why did this query suddenly show up in our slow log".

## Migration checklist

Enabling `reWriteBatchedInserts` on an existing code base is a small
change with two non-trivial things to verify:

1. **Audit `executeBatch()` callers.** Any caller that inspects the
   returned `int[]` for per-row counts needs to tolerate
   `SUCCESS_NO_INFO` and `0`. Frameworks that wrap JDBC (Spring's
   `JdbcTemplate.batchUpdate`, MyBatis batch executors) typically
   pass the array through unchanged, so the check is at the
   application-code layer.
2. **Decide on `pg_stat_statements` scope.** If you rely on it for
   capacity planning or slow-query alerts, queue up the rewrite
   group-by adjustment (or live with the fan-out).

`RETURNING`, generated-key retrieval, and statements that mix multiple
commands in one `PreparedStatement` will silently keep using the
naive path. There is no warning; the driver just falls back, and the
batch runs at the un-accelerated speed.

## Related

- [Recommended properties](/documentation/connect/recommended-properties/#rewritebatchedinsertstrue--batch-insert-throughput):
  the short-form recommendation that points here for the mechanics.
- [COPY (CopyManager)](/documentation/postgresql-features/copy/): the
  bulk-load alternative for code paths you are willing to restructure;
  faster than `reWriteBatchedInserts`, at the cost of leaving the
  `PreparedStatement` API behind.
- [executeBatch hangs without an error](/documentation/troubleshooting/executebatch-hangs/):
  the TCP-buffer deadlock that very large batches can hit and how
  `reWriteBatchedInserts` reduces the per-row response volume the
  deadlock estimator has to account for.
- [Server-prepared statements](/documentation/query/prepared-statements/):
  how the rewritten power-of-two statement texts interact with the
  driver's prepared-statement cache.
- [`SqlCommand.java`](https://github.com/pgjdbc/pgjdbc/blob/bd1af18230371879fb4127ae28800cf9a8a8c77d/pgjdbc/src/main/java/org/postgresql/core/SqlCommand.java)
  / [`BatchedQuery.java`](https://github.com/pgjdbc/pgjdbc/blob/bd1af18230371879fb4127ae28800cf9a8a8c77d/pgjdbc/src/main/java/org/postgresql/core/v3/BatchedQuery.java):
  the driver code that enforces the activation conditions and the
  power-of-two grouping.
