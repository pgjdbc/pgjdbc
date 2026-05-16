---
title: "executeBatch hangs without an error"
date: 2026-05-16T00:00:00Z
draft: false
weight: 1
toc: true
last_reviewed: "2026-05-16"
description: "A large PreparedStatement.executeBatch() blocks indefinitely with no exception — both client and server are waiting on a full TCP buffer to drain. Tune the socket buffers or break the batch into smaller chunks."
---

The symptom is a `PreparedStatement.executeBatch()` (or
`Statement.executeBatch()`) call that never returns — no exception, no
log line, just a thread parked on `socket.write`. Thread dumps show
the JDBC thread in `SocketOutputStream.write`; the database side shows
the backend in `pq_putmessage` / send-side wait.

## Why it happens

The pgJDBC protocol is single-threaded. It writes batched messages to
the socket and then reads responses, in that order. Both directions
go through bounded TCP buffers:

```
driver ──────────────►  TCP send/receive buffers  ──────────────► server
       ◄──────────────                            ◄──────────────
```

When the client writes a large batch faster than the server can drain
its receive buffer, two things happen at once:

1. The server's receive buffer fills, the server starts writing
   responses to its send buffer to keep the loop moving, eventually
   the server's send buffer fills too. The server now blocks on
   `write()`, waiting for the driver to read.
2. The driver is still writing the rest of the batch — it can't
   start reading until it's done. Its send buffer fills against the
   server that has stopped reading. The driver blocks on `write()`.

Both ends are waiting for the other to read. No-one will. The
backend itself is healthy; nothing logs anything; the JVM thread is
parked. The full reasoning lives in
[QueryExecutorImpl.java's "Deadlock avoidance" block](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java#L567),
with historical detail in issues [#194](https://github.com/pgjdbc/pgjdbc/issues/194)
and [#195](https://github.com/pgjdbc/pgjdbc/issues/195).

## What the driver already does

To keep the obvious case from biting, pgJDBC estimates how much
response data the server will buffer up while reading the in-flight
batch, and forces a `Sync` + read whenever that estimate exceeds
**64,000 bytes** (`MAX_BUFFERED_RECV_BYTES` in `QueryExecutorImpl`).
The per-query estimate is coarse — 250 bytes for a "no data" query
plus the described row size — so it works for most workloads but
breaks down in edge cases:

- queries whose response size cannot be bounded (statements with
  unknown row counts get batching disabled for that query);
- async notifications, NOTICE / WARNING messages, large parameter
  data that the estimate doesn't account for;
- very small OS TCP buffers (containers, embedded systems) that fill
  before the driver has written enough to trigger a Sync.

## How to fix it

In order of preference:

### Break the batch into smaller chunks

The cleanest fix. A batch of 100,000 inserts hits this; a loop of
100 batches of 1,000 does not. The Sync the driver issues at the end
of each `executeBatch()` resets the estimate. Throughput is usually
unaffected if the per-chunk overhead is small.

### Raise the socket buffers

Both connection properties accept a byte count and call into the
underlying `Socket.set{Send,Receive}BufferSize`. The default `-1`
means "leave it at the OS default" (typically 16–64 KB on Linux,
larger on macOS).

{{< param-table data="connection-properties" tag="network" >}}

`sendBufferSize`, `receiveBufferSize` and `maxSendBufferSize` are the
three knobs that matter here. Setting `sendBufferSize` and
`receiveBufferSize` to e.g. 1 MB each is a reasonable starting point
for batch-heavy workloads.

The OS may cap the actual buffer below what you ask for. On Linux,
the ceiling is `net.core.{rmem,wmem}_max`; check the effective value
with `ss -i` on an open connection rather than trusting the property
alone.

### Enable `reWriteBatchedInserts`

For batches of simple `INSERT INTO t VALUES (?, ?, ...)` statements,
[`reWriteBatchedInserts=true`](/documentation/reference/connection-properties/#prop-rewritebatchedinserts)
collapses N statements into one multi-row insert, which both runs
faster and dramatically reduces the per-row response overhead the
deadlock estimator has to account for. It does not apply to other
statement shapes.

## Related

- [Connection properties reference](/documentation/reference/connection-properties/)
  — `sendBufferSize`, `receiveBufferSize`, `maxSendBufferSize`,
  `reWriteBatchedInserts`.
- [Server-prepared statements](/documentation/query/prepared-statements/)
  — when the cached plan / binary transfer setup happens to coincide
  with a batch boundary.
