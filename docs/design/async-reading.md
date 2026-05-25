# Async Protocol Reading — Design Document

## Problem

PgJDBC's `QueryExecutorImpl` uses a single thread for both sending and receiving protocol messages. During batch/pipeline execution, the driver must interleave sends and receives to avoid TCP deadlock (both client and server block on write with full buffers). The current approach estimates buffered response bytes and forces a Sync+drain when a threshold is exceeded (`MAX_BUFFERED_RECV_BYTES = 64KB`). This is:

1. **Conservative** — the 64KB estimate is far below typical OS buffer sizes (200KB+), limiting pipeline depth
2. **Fragile** — the estimate ignores async notifications, warnings, and variable-length responses
3. **Blocking** — the executor cannot send the next query while waiting for a response to the previous one

## Goal

Decouple the send and receive paths by introducing a dedicated reader thread that continuously drains the socket into a bounded in-memory queue. The executor thread sends freely and consumes responses from the queue, eliminating the deadlock risk entirely and enabling deeper pipelining.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   QueryExecutorImpl                       │
│                                                          │
│  Executor Thread                                         │
│  ┌──────────────┐         ┌──────────────────────────┐  │
│  │ sendQuery()  │         │ processResults()         │  │
│  │ sendSync()   │         │   nextMessageType()      │  │
│  │ sendExecute()│         │   ↓                      │  │
│  └──────┬───────┘         │   MessageQueue.take()    │  │
│         │                 └───────────┬──────────────┘  │
│         ▼                             │                  │
│    PGStream.send*()                   │                  │
│         │                             │                  │
└─────────┼─────────────────────────────┼──────────────────┘
          │                             ▲
          ▼                             │
┌─────────────────┐          ┌──────────┴──────────┐
│   TCP Socket    │          │    MessageQueue     │
│   (write side)  │          │  (ArrayBlockingQueue│
└─────────────────┘          │   capacity=1024)    │
                             └──────────▲──────────┘
                                        │
                             ┌──────────┴──────────┐
                             │  AsyncMessageReader │
                             │  (daemon thread)    │
                             │                     │
                             │  loop:              │
                             │    readFullMessage() │
                             │    queue.put(msg)   │
                             └──────────┬──────────┘
                                        │
                                        ▼
                             ┌─────────────────┐
                             │   TCP Socket    │
                             │   (read side)   │
                             └─────────────────┘
```

## Components

### `ProtocolMessage` (new class)
- Package: `org.postgresql.core`
- Immutable value object: `int type` + `byte[] payload`
- Represents one complete wire message (type byte already consumed, length excluded from payload)
- `getWireSize()` returns `1 + 4 + payload.length`

### `MessageQueue` (new class)
- Package: `org.postgresql.core.v3`
- Wraps `ArrayBlockingQueue<Entry>` with capacity 1024
- `Entry` is either a `ProtocolMessage` or an `IOException`
- Blocking `put()`/`take()` for producer/consumer
- Non-blocking `poll()` and timed `poll(timeoutMs)` for optional use

### `AsyncMessageReader` (new class)
- Package: `org.postgresql.core.v3`
- Daemon thread named `pgjdbc-async-reader`
- Calls `PGStream.readFullMessage()` in a loop
- On `IOException`/`EOFException`, enqueues the error and stops
- `shutdown()` sets `running = false` and interrupts the thread

### `PGStream.readFullMessage()` (new method)
- Reads type byte, 4-byte length, then `length - 4` payload bytes
- Returns a `ProtocolMessage`
- Blocks until a complete message is available

### `QueryExecutorImpl` changes
- New connection property: `asyncReading` (default `false`)
- On init (after `readStartupMessages()`): creates `MessageQueue` + starts `AsyncMessageReader`
- `nextMessageType()`: pulls from queue when async, falls back to `pgStream.receiveChar()` when not
- Stores `currentAsyncMessage` + `asyncPayloadOffset` for payload consumption
- `close()` shuts down the reader thread

### `BaseDataSource` changes
- `getAsyncReading()` / `setAsyncReading(boolean)` for the new property

## Connection Property

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `asyncReading` | Boolean | `false` | Enable async protocol message reading via a dedicated reader thread |

## Current Status (Phase 1 — Complete)

- [x] `ProtocolMessage` value object
- [x] `MessageQueue` with backpressure
- [x] `AsyncMessageReader` daemon thread
- [x] `PGStream.readFullMessage()`
- [x] `nextMessageType()` integration in `processResults()` loop
- [x] Connection property + DataSource getter/setter
- [x] Graceful shutdown on `close()`

## Next Steps

### Phase 2 — Payload Consumption

The `processResults()` loop currently reads individual fields (integers, strings, byte arrays) directly from `PGStream` after consuming the type byte. When async reading is active, these reads must come from `currentAsyncMessage.getPayload()` instead.

Tasks:
- [ ] Create `AsyncPayloadStream` or adapter that wraps `byte[] payload` with the same read API as `PGStream` (receiveInteger4, receiveInteger2, receiveString, receive(n), etc.)
- [ ] Route all field reads in `processResults()` through the adapter when `asyncReadingEnabled`
- [ ] Handle `receiveTupleV3()` — DataRow messages contain the bulk of data
- [ ] Handle `receiveErrorResponse()` / `receiveNoticeResponse()` — variable-length field parsing

### Phase 3 — Remove Deadlock Prevention Heuristic

Once the reader thread fully drains the socket independently:
- [ ] Remove `estimatedReceiveBufferBytes` tracking
- [ ] Remove `flushIfDeadlockRisk()` — no longer needed since the reader thread prevents buffer bloat
- [ ] Simplify batch execution: send all queries, then Sync, then process all results
- [ ] Benchmark pipeline throughput improvement

### Phase 4 — True Async API (Optional/Future)

With the reader thread in place, the foundation exists for:
- Non-blocking `executeQuery()` that returns immediately
- `ResultSet.next()` blocks on the queue until a DataRow arrives
- `CompletableFuture`-based async API alongside the synchronous JDBC API
- Potential `java.util.concurrent.Flow` (reactive streams) adapter

## Design Decisions

### Why a blocking queue (not NIO/Netty)?
- PgJDBC is a pure-Java Type 4 driver with zero external dependencies
- A single reader thread + blocking queue is simple, debuggable, and sufficient
- NIO would require rewriting the entire stream layer and connection lifecycle
- The pgjdbc-ng project attempted Netty-based async and demonstrates the complexity

### Why capacity 1024?
- Each `ProtocolMessage` holds its payload in a `byte[]` — typical DataRow is 100B–10KB
- 1024 messages ≈ 100KB–10MB buffered, well within heap limits
- Provides enough runway for the executor to batch sends without the reader blocking
- Configurable in the future if needed

### Why opt-in (`asyncReading=false` by default)?
- Preserves backward compatibility — no behavior change for existing users
- The reader thread adds a thread per connection; some environments are thread-constrained
- Phase 2 (payload consumption) is incomplete — enabling now would break field reads
- Once fully wired and tested, we can consider changing the default

### Thread safety
- `MessageQueue` is thread-safe via `ArrayBlockingQueue`
- `AsyncMessageReader` only writes to the queue; executor only reads
- `PGStream` write methods are only called from the executor thread (no contention)
- The existing `ResourceLock` in `QueryExecutorImpl` prevents concurrent `execute()` calls

## Risks

1. **Payload consumption mismatch** — If the executor reads fields from `PGStream` while the reader thread has already consumed those bytes, data corruption occurs. Phase 2 must be completed atomically (all-or-nothing per message type).

2. **COPY protocol** — During COPY operations, the protocol switches to a streaming mode that doesn't follow the standard message framing. The reader thread must be paused or bypassed during COPY.

3. **SSL renegotiation** — Some SSL implementations may not be thread-safe for concurrent read/write on the same socket. Needs testing with different JDK SSL providers.

4. **Socket timeout** — `SO_TIMEOUT` on the socket will cause `SocketTimeoutException` in the reader thread. The reader must distinguish timeout (retry) from real errors.

5. **Memory pressure** — Large result sets could fill the queue with multi-MB DataRow messages. The bounded queue provides backpressure, but the 1024 capacity may need tuning or the queue could use a byte-size limit instead of message count.
