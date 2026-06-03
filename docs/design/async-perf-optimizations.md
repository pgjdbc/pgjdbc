# Async Reading Performance Optimizations — Implementation Plan

## Goal

Reduce per-message overhead in the async reading path by eliminating unnecessary allocations,
timeout arithmetic, and blocking-queue lock contention on the hot path.

## Optimizations

### 1. Use blocking `take()` when no timeout is configured

**Problem:** `nextMessageType()` always uses `queue.poll(pollMs)` with timeout calculation
even when `networkTimeoutRequested == 0` (infinite timeout — the common case). This means
every message incurs `System.nanoTime()` calls and arithmetic that are never used.

**Fix:** Fast-path with `queue.take()` when no timeout is set. Only fall into the
poll-with-timeout loop when `networkTimeoutRequested > 0`.

**File:** `QueryExecutorImpl.java`, `nextMessageType()` (~line 2670)

**Before:**
```java
long waitStart = System.nanoTime();
int socketTimeout = networkTimeoutRequested;
long pollMs = socketTimeout > 0 ? Math.min(socketTimeout, 1000) : 1000;
while (true) {
  entry = queue.poll(pollMs);
  ...
}
```

**After:**
```java
int socketTimeout = networkTimeoutRequested;
if (socketTimeout <= 0) {
  entry = queue.take();
} else {
  long waitStart = System.nanoTime();
  long pollMs = Math.min(socketTimeout, 1000);
  while (true) {
    entry = queue.poll(pollMs);
    if (entry != null) {
      break;
    }
    if (pgStream.isClosed() || (asyncReader != null && !asyncReader.isAlive())) {
      throw new IOException("Connection closed while waiting for protocol message");
    }
    long elapsed = (System.nanoTime() - waitStart) / 1_000_000;
    if (elapsed >= socketTimeout) {
      throw new SocketTimeoutException("Async read timed out after " + elapsed + "ms");
    }
    pollMs = Math.min(socketTimeout - elapsed, 1000);
    if (pollMs <= 0) {
      throw new SocketTimeoutException("Async read timed out after " + elapsed + "ms");
    }
  }
}
```

Also apply same pattern to `peekNextMessageType()`.

**Note:** `MessageQueue.take()` already handles the dead-reader check internally via its
1-second poll loop. We should also add a simpler `takeBlocking()` method that uses
`queue.take()` directly (only checking reader liveness on InterruptedException) for
maximum performance when no timeout is needed.

---

### 2. Eliminate Entry wrapper allocation

**Problem:** Every message enqueued by the reader thread creates `new Entry(message, null)`.
On a 10K-row SELECT, that's 10K+ allocations per query. Errors are extremely rare (one per
connection lifetime typically), so the wrapper exists almost exclusively to carry non-null
messages.

**Fix:** Change `MessageQueue` to use `BlockingQueue<ProtocolMessage>` directly. Use a
sentinel `ProtocolMessage` (type = -1, payload = serialized IOException) for errors, or
store the error in a separate `volatile IOException errorSignal` field that the consumer
checks when it receives the sentinel.

**File:** `MessageQueue.java`

**Design:**
```java
final class MessageQueue {
  private static final ProtocolMessage ERROR_SENTINEL = new ProtocolMessage(-1, new byte[0]);

  private final BlockingQueue<ProtocolMessage> queue;
  private volatile @Nullable IOException pendingError;

  void put(ProtocolMessage message) throws InterruptedException {
    queue.put(message);
  }

  void putError(IOException error) throws InterruptedException {
    this.pendingError = error;
    queue.put(ERROR_SENTINEL);
  }

  // Consumer checks: if (msg == ERROR_SENTINEL) throw pendingError;
}
```

This eliminates one object allocation per message on the hot path. The `Entry` class
is removed entirely.

**Impact on QueryExecutorImpl:** Replace `entry.isError()` / `entry.getMessage()` with
a sentinel identity check: `if (msg == MessageQueue.ERROR_SENTINEL)`.

---

### 3. Reuse PayloadReader instance

**Problem:** `nextMessageType()` and `peekNextMessageType()` both create
`new PayloadReader(msg.getPayload(), pgStream.getEncoding())` for every message. The
consumer is single-threaded, so there's no need for a fresh instance each time.

**Fix:** Add a `reset(byte[] data)` method to `PayloadReader` and keep a single instance
in `QueryExecutorImpl` that gets reset for each new message.

**File:** `PayloadReader.java`, `QueryExecutorImpl.java`

**PayloadReader change:**
```java
void reset(byte[] data) {
  this.data = data;
  this.pos = 0;
}
```

Remove `final` from the `data` field.

**QueryExecutorImpl change:**
```java
// Field (initialized once)
private final PayloadReader payloadReader = new PayloadReader(new byte[0], ...);

// In nextMessageType():
payloadReader.reset(msg.getPayload());
currentPayload = payloadReader;
```

**Complication:** The `encoding` field is set at construction time. If the encoding can
change mid-connection (`allowEncodingChanges=true`), the reset method should also accept
the current encoding or PayloadReader should read it from PGStream on demand. For simplicity,
add a `resetEncoding(Encoding)` or pass encoding to `reset()`:

```java
void reset(byte[] data, Encoding encoding) {
  this.data = data;
  this.pos = 0;
  this.encoding = encoding;
}
```

---

## Estimated Impact

| Optimization | Allocation saved | Lock/syscall saved | Frequency |
|---|---|---|---|
| Blocking take() | — | nanoTime × 2, timeout math | Every message |
| Remove Entry wrapper | 1 object (24 bytes) per message | — | Every message |
| Reuse PayloadReader | 1 object (32 bytes) per message | — | Every message |

For a 10K-row SELECT (10K DataRow messages + overhead), this saves ~20K object allocations
and ~20K `System.nanoTime()` calls per query.

## Verification

1. `./gradlew :postgresql:test --tests 'org.postgresql.test.jdbc2.BatchPipelineTest'`
2. `./gradlew :postgresql:test --tests 'org.postgresql.test.jdbc4.ConnectionValidTimeoutTest'`
3. `./gradlew :postgresql:test --tests 'org.postgresql.test.jdbc2.SocketTimeoutTest'`
4. `./gradlew :postgresql:test --tests 'org.postgresql.test.jdbc2.*'`
5. JMH benchmark comparison (before/after)

## Implementation Order

1. Optimization 3 (PayloadReader reuse) — simplest, no API change
2. Optimization 2 (remove Entry wrapper) — changes MessageQueue API
3. Optimization 1 (blocking take) — changes control flow in nextMessageType
