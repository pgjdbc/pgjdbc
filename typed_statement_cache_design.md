# Design: parameter-type-aware statement cache and cross-connection parse sharing

Status: draft for maintainer discussion.
Scope: two related changes to prepared-statement caching, staged so each lands separately.

- **Problem A.** One SQL text holds a single server-prepared handle. Alternating parameter-type
  signatures unprepare and re-parse it on every switch, losing the server plan each time.
- **Problem B** ([issue #345](https://github.com/pgjdbc/pgjdbc/issues/345)). The statement cache is
  per connection, so a pool of N connections stores N copies of every parsed query text.

Line references are against `master` as of commit `228f0e4a2` (the describe-result cache for
`getParameterMetaData()` is part of the baseline).

## 1. Current architecture

`QueryExecutorBase.statementCache` is an `LruCache<Object, CachedQuery>` keyed by the SQL text or a
thin wrapper (`CallableQueryKey`, `QueryWithReturningColumnsKey`); parameter types are not part of
the key (`QueryExecutorBase.java:336-361`, `CachedQuery.java:16-18`). `LruCache.borrow` removes the
entry and `releaseQuery` puts it back: a `CachedQuery` is mutable and owned by one statement at a
time (`LruCache.java:122-135`, `PgPreparedStatement.java:92-95`, `:216-220`).

`SimpleQuery` holds one server handle: `statementName`, `preparedTypes`, `unspecifiedParams`,
`deallocateEpoch`, plus describe results (`fields`, `statementDescribed`, `portalDescribed`,
`cachedMaxResultRowSize`, `needUpdateFieldFormats`, `hasBinaryFields`,
`resultSetColumnNameIndexMap`) and the `cleanupRef` phantom reference. When
`isPreparedFor(typeOIDs, deallocateEpoch)` fails in `sendParse`, the driver unprepares, clears
`fields`, and re-parses under a fresh `S_<n>` name (`QueryExecutorImpl.java:1754-1785`).

Parsing itself happens in `CachedQueryCreateAction.create` and depends on connection state:
`standardConformingStrings` (a server GUC), `serverVersionNum`, `escapeSyntaxCallMode`,
`preferQueryMode`, `reWriteBatchedInserts`, `quoteReturningIdentifiers`
(`CachedQueryCreateAction.java:28-73`).

## 2. Problem A: per-signature server handles

### 2.1 Approach

Keep the cache key unchanged. Extract the server-statement state of `SimpleQuery` into a
`ServerHandle` object and let one `SimpleQuery` own a small table of them:

```java
class SimpleQuery {
  private @Nullable ServerHandle currentHandle;   // fast path
  private ServerHandle @Nullable [] handles;      // lazily allocated, capacity K, MRU order
  private @Nullable ServerHandle unnamedHandle;   // oneshot / unnamed statement, never in the table
}
```

Control flow in `sendParse`:

1. `isPreparedFor` against `currentHandle` — byte-for-byte today's check, no allocation. The common
   case (same SQL, same signature) is unchanged.
2. On mismatch, scan the table (K ≤ 4-8) running the same `isPreparedFor` per handle. In-place
   `int[]` comparison; no key object is built. A hit becomes `currentHandle`.
3. On a table miss, create a new handle (this is where `typeOIDs` is cloned, as `setPrepareTypes`
   does today). If the table is full, evict the least recently used *unpinned* handle and queue a
   `Close` for it.

Matching is deliberately `isPreparedFor`, not key equality. A handle parsed with `Oid.UNSPECIFIED`
positions and refined by `ParameterDescription` must keep matching both the unspecified and the
resolved signature (`SimpleQuery.java`, the `isPreparedFor` javadoc example). Canonicalizing
signatures into hash keys cannot express that wildcard, which also rules out the alternative of
putting the signature into the statement-cache key. That alternative fails independently on the
statement lifecycle: the key is needed in the `PgPreparedStatement` constructor, before any
parameter is set.

### 2.2 What moves into `ServerHandle`

Everything that describes one named statement: `statementName`, `encodedStatementName`,
`preparedTypes`, `unspecifiedParams`, `deallocateEpoch`, `fields`, `needUpdateFieldFormats`,
`hasBinaryFields`, `portalDescribed`, `statementDescribed`, `cachedMaxResultRowSize`,
`resultSetColumnNameIndexMap`, `cleanupRef`.

What stays on `SimpleQuery`: `nativeQuery`, `transferModeRegistry`, `sanitiserDisabled`, and the
describe-result cache (`describeResults`). The describe cache records how the server resolves types
for the SQL text, not the state of a named statement; it intentionally survives `unprepare()` and is
invalidated by `deallocateEpoch` alone.

A side effect worth naming: `sendParse` currently clears `fields` on re-parse (the `XXX` comment
near `QueryExecutorImpl.java:2199`, bug #267). With per-handle metadata, switching signatures no
longer wipes the other signature's row description. The within-signature describe race stays and is
out of scope.

### 2.3 Ownership and resolution contract

`deallocateEpoch` is private to `QueryExecutorImpl`, so handle resolution must live there:

- Only `QueryExecutor` resolves or mutates handles, under its connection lock. The single entry
  point is `resolveHandle(params, epoch)`; it never returns a stale-epoch handle, and on the first
  stale hit it drops the whole table (all handles share the invalidation fate) and queues their
  `Close` refs.
- `sendOneQuery` resolves once and passes a handle snapshot down to `sendParse`, `sendBind`,
  `sendDescribeStatement`, `sendDescribePortal`, and `sendExecute`. Today `sendBind` and
  `sendDescribeStatement` re-read `query.getStatementName()`; those reads switch to the snapshot.
- Every pending-queue entry (`pendingParseQueue`, `pendingDescribeStatementQueue`,
  `pendingDescribePortalQueue`, `pendingExecuteQueue`) carries the handle snapshot. Server responses
  (`ParseComplete`, `ParameterDescription`, `RowDescription`) and the `ReadyForQuery` error cleanup
  update exactly the snapshot handle, not whatever `currentHandle` points to by then. The existing
  `DescribeRequest.statementName` snapshot is the precedent.
- Pre-describe checks on the JDBC layer (`PgStatement.java:519`, `:921-923` batch pre-describe)
  cannot see the epoch. They call a new executor method, `isStatementDescribed(Query,
  ParameterList)`, which takes the lock and resolves; the conservative fallback is issuing the
  describe-only execute unconditionally.
- Oneshot executions use `unnamedHandle`: no name, no phantom ref, no LRU participation, overwritten
  by each oneshot run. `resolveHandle` never mixes it with named variants. Note this split is a
  (deliberate) behavior change, not a pure refactoring: today a oneshot describe-only execution
  (`getParameterMetaData()` below `prepareThreshold`) leaves `statementDescribed` and `fields` on
  the shared query state, and a later *named* execution skips its Describe because of it — exactly
  the unnamed/named state mixing this design removes. The cost is one extra Describe on the first
  named execution after a oneshot describe; the tests in stage 2 pin this down.

### 2.4 Portals: pin, deferred close, and error paths

The backend closes all dependent portals when a statement is closed, so evicting a handle that an
open cursor depends on must not send `Close` yet. Today's only protection is GC reachability
(`Portal` holds the `SimpleQuery`, `Portal.java:60-64`), which does not cover the explicit
`unprepare()` on a signature switch — as far as we can tell, switching parameter types while a
`fetchSize` cursor is open already kills the cursor on master. That needs a confirming test before
this work starts; if confirmed, the pin machinery below fixes an existing bug rather than defending
a new design.

- `ServerHandle.pinCount`, mutated only under the executor lock.
- `Portal` gains a `handle` field next to `query`; pin++ happens at portal creation in
  `sendOneQuery`, *before* Bind is sent. Pinning at `BindComplete` would be too late: in a pipeline,
  a later query could evict the handle a pending Bind depends on.
- A single idempotent `releaseHandlePin()` with two deterministic paths into it:
  - success path: `BindComplete` registers the portal in `openPortalMap`; `processDeadPortals`
    sends `Close(portal)`, removes the map entry, and releases the pin;
  - error paths: the `ReadyForQuery` cleanup drains `pendingBindQueue` and `pendingExecuteQueue`
    and enqueues each drained portal's cleanup ref (the same mechanism `EmptyQueryResponse` uses),
    so the next `processDeadPortals` closes it and releases the pin. No path relies on GC; phantom
    references remain only as the leak backstop.
- Handle states: `ACTIVE` (in the table, participates in lookup and LRU) → `DEFERRED` (evicted while
  pinned: out of the table and out of lookup, still counted against the connection-wide limit) →
  `CLOSED` (unpin queues the `Close`). A handle is a member of exactly one structure at a time.
- Ordering is structural, not by convention: the deferred-`Close` FIFO receives `Close(portal)`
  first, the unpin it triggers may then append `Close(statement)`, so a statement can never be
  closed ahead of its portal in the same drain.

### 2.5 Limits, eviction, accounting

- Per-query capacity K, new connection property `preparedStatementCacheTypeVariants`. Default `1`
  reproduces today's behavior exactly and ships first; raising the default (likely to `4`) is a
  follow-up decision after benchmarks. `K > 1` consumes server memory only for workloads that
  alternate signatures — exactly the workloads that currently thrash.
- A connection-wide cap on live named handles (counting `DEFERRED`), since per-query LRU does not
  bound the sum. Pinned handles may exceed the soft cap; the server has to keep them anyway while
  their cursors are open.
- `prepareThreshold` accounting stays per `CachedQuery` (per SQL text): the threshold answers "is
  this SQL worth server-preparing", and once it is, each new signature prepares immediately.
  Per-signature counting is possible but delays the benefit; flagged as an open question.
- `CachedQuery.getSize()` gains the (small) per-handle overhead. Client-side cost is negligible;
  the real budget is server memory, controlled by K and the connection-wide cap.
- Statement kinds: the design sits below the cache-key level, so plain prepared statements,
  `CallableQueryKey`, and `QueryWithReturningColumnsKey` are covered uniformly. Batch rewriting
  works because `BatchedQuery extends SimpleQuery`: each derived block gets its own handle table;
  the per-connection cap bounds `K × log2(MAX_VALUE_BLOCK)` growth.

## 3. Problem B: shared parse cache

### 3.1 Approach

A new layer *below* the per-connection cache, touched only on a per-connection miss (first
`prepareStatement` of a given SQL on a given connection). The execute hot path never sees it.

```java
final class SharedParseCache {
  // key: ParserFingerprint + a canonical, deeply immutable copy of the query key
  // value: ParsedText — immutable parse output, shared across connections
  private final com.github.benmanes.caffeine.cache.Cache<SharedKey, ParsedText> cache;
}
```

`CachedQueryCreateAction.create` consults it around the `Parser.replaceProcessing` /
`modifyJdbcCall` / `parseJdbcSql` calls; `queryExecutor.wrap(queries)` stays per-connection and
builds fresh `SimpleQuery` objects with the connection's `transferModeRegistry`.

The sharing boundary:

- **Shared:** the parse output — native SQL text, bind positions, `SqlCommand` data, `isFunction`.
- **Never shared:** server handles, describe results and metadata, `executeCount`
  (`prepareThreshold`), epochs, phantom-reference state, and the describe-result cache (type
  resolution depends on session state: `search_path`, DDL, temp objects, function overloads).

### 3.2 Required groundwork

- **Deep immutability.** `NativeQuery.bindPositions` is a public mutable `int[]`, and the parser
  returns mutable lists. The shared value is a new immutable DTO; `wrap()` materializes
  per-connection `NativeQuery` instances from it. The dominant memory — SQL text strings — is shared;
  the small position arrays are copied per connection.
- **Canonical key.** `QueryWithReturningColumnsKey` stores the caller's `columnNames` array without
  a defensive copy, acceptable under one connection's lock, not in a global concurrent map. The
  shared key is a dedicated immutable class with copied arrays.
- **Fingerprint per lookup.** `standardConformingStrings` can change mid-session via
  `ParameterStatus`, so the fingerprint is read at lookup time, not cached per connection, and its
  fields are derived from the actual arguments of the three parser entry points so a new
  parse-affecting setting cannot be silently omitted.
- **Java 8.** The main artifact targets Java 8: plain final classes, no records.

### 3.3 Concurrency, sizing, lifecycle

Caffeine handles concurrent loads without a global lock; this addresses the throughput concern
raised in #345 directly, and the layer is read-mostly immutable data. Sizing via a weigher
(estimated bytes) and `maximumWeight` from a new property. Cache instances live in a driver-level
registry keyed by URL plus relevant properties, with weak or refcounted values so idle pools do not
pin memory. No invalidation is needed: entries are a pure function of (fingerprint, text).

Properties: `sharedParseCache` (boolean, default `false` in the first release) and
`sharedParseCacheSizeMiB`. Per-connection `LruCache` keeps weighing entries by their full text size
even though the text is shared; the double accounting is accepted and documented. The expected win
is pool memory, to be demonstrated with heap/RSS measurements rather than claimed; the CPU win from
skipped parsing is likely minor.

The per-connection `LruCache` itself is not migrated to Caffeine: it is single-threaded under the
connection lock, and its borrow-with-removal ownership semantics are load-bearing for the mutable
`CachedQuery`.

## 4. Compatibility

- A ships with `preparedStatementCacheTypeVariants=1`: bit-for-bit current behavior. Raising K only
  changes behavior for workloads that alternate signatures, where today's behavior is a
  re-parse-per-call pathology.
- B ships with `sharedParseCache=false`. Enabling it changes memory behavior, not semantics.
- No public API changes; `Query`/`QueryExecutor` additions are internal interfaces.

## 5. Test plan (headline scenarios)

1. Two type signatures in one pipeline with `Describe`, `RowDescription`, and an error between the
   responses (exercises the `ReadyForQuery` cleanup routing to snapshot handles).
2. `DEALLOCATE ALL` / DDL / `SET search_path` immediately before `forceBinaryTransfers` describe and
   batch pre-describe (stale-epoch rejection through `resolveHandle`).
3. Named portal with `fetchSize`: (a) on master, switch signatures mid-cursor and confirm the
   suspected existing breakage; (b) with the new design, cursor on variant A survives pressure from
   variants B..K+1, and no `Close` for A's statement is sent before the portal closes.
4. `BindComplete` then Execute error, then immediate variant pressure to the limit: portal `Close`
   and unpin happen at the next safe point without forced GC; no double `Close` when the portal
   object is later collected.
5. `getMetaData()` / `getParameterMetaData()` on wildcard and stale handles; oneshot isolation
   (`unnamedHandle` never leaks into the named table).
6. `K=1` plus eviction during queue drains; pin/unpin idempotency; single-membership invariant
   (table xor deferred list); `castNonNull` safety in `processDeadParsedQueries` (one name, one
   ref).
7. B: concurrent `prepareStatement` from pooled connections (contention), fingerprint change via
   `standardConformingStrings`, pool RSS measurement, 10k-unique-URL registry lifecycle.

## 6. Staged plan

1. **Extract `ServerHandle`** with all result state; a single permanent handle that `SimpleQuery`
   delegates to; behavior bit-for-bit (pure refactoring PR).
2. **Protocol routing:** handle snapshots in all pending queues, `ReadyForQuery`/error cleanup per
   handle, `resolveHandle` ownership in the executor, the `unnamedHandle` split (a behavior change,
   see 2.3), portal pin/deferred-close machinery; the test plan above.
3. **Variant table and limits:** per-query K, connection-wide cap, property with default `1`;
   JMH benchmarks (fast-path parity, alternating signatures, server memory); then the default-K
   decision.
4. **B groundwork:** immutable parse DTO, canonical shared key, fingerprint derivation.
5. **B:** `SharedParseCache` on Caffeine, driver-level registry, properties, concurrency and memory
   tests.
6. Later, separately: revisiting the default of `preparedStatementCacheTypeVariants`, and whether
   `sharedParseCache` can default to on.

## 7. Open questions for maintainers

1. `prepareThreshold`: keep per SQL text (proposed) or count per signature?
2. Default for `preparedStatementCacheTypeVariants` after benchmarks: stay at `1` or raise to `4`?
3. The suspected existing cursor-vs-signature-switch bug: fix on master first (small, targeted) or
   fold into stage 2?
4. Is a Caffeine dependency in the core driver acceptable (plain or shaded), given it is already
   under consideration for the codec API?
