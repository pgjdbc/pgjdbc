---
name: pgjdbc-testkit-use
description: How to use pgjdbc's `TestUtil` and `BaseTest4` helpers when writing JUnit 5 tests that open connections or create PostgreSQL schema. Apply when adding or editing tests under `pgjdbc/src/test/`.
---

# Using the pgjdbc test kit

Tests run against a live PostgreSQL server provided by the test
harness. The helpers in `org.postgresql.test.TestUtil` and the
`BaseTest4` lifecycle base class encode several conventions the suite
relies on. Apply them whenever a test opens a connection or touches
schema.

When the test extends `BaseTest4`, override `setUp()` / `tearDown()`
**without** adding a second `@BeforeEach`/`@AfterEach` annotation —
`BaseTest4` already wires the JUnit hooks (annotating again breaks
JUnit 5's override semantics).

## Always go through TestUtil for schema

Naked `CREATE TABLE foo(...)` leaks objects when a previous run died
between create and drop. Every TestUtil creator drops first; every
TestUtil dropper is `IF EXISTS … CASCADE` aware:

- Creators: `createTable`, `createTempTable`, `createUnloggedTable`,
  `createView`, `createMaterializedView`, `createSchema`,
  `createEnumType`, `createCompositeType`, `createDomain`.
- Droppers: `dropTable`, `dropView`, `dropMaterializedView`,
  `dropSchema`, `dropType`, `dropDomain`, `dropSequence`,
  `dropFunction(name, argTypes)`, `dropReplicationSlot`.

`dropObject` issues `DROP … IF EXISTS … CASCADE` in autocommit and
plain `DROP … CASCADE` inside a transaction (because `IF EXISTS`
would swallow an error that should abort the tx). Pick the matching
`dropXxx()` per created object — do not hand-roll `DROP TABLE …`.

There is no `createFunction` helper. Create functions with
`TestUtil.execute(con, "CREATE OR REPLACE FUNCTION …")` and drop them
with `TestUtil.dropFunction(con, name, "<arg types>")`. The argument
string must match the signature, since PostgreSQL keys functions by
`(name, argtypes)`.

## Connections

- `TestUtil.openDB()` / `TestUtil.openDB(props)` — the standard test
  connection. Properties layer on top of system properties and
  `build.properties`; do not reconstruct the JDBC URL by hand.
- `TestUtil.openPrivilegedDB()` — connection as the privileged role,
  required for tests that load C functions, create extensions, or
  terminate backends.
- `TestUtil.closeDB(conn)` — null-safe close. Use in `@AfterEach` /
  `@AfterAll` / `finally` so a partial setup still tears down.

## Version gating

Skip — don't branch — when a feature requires a newer server:

- `TestUtil.assumeHaveMinimumServerVersion(ServerVersion.v15)` —
  static, opens its own privileged connection. Use from `@BeforeAll`.
- `BaseTest4.assumeMinimumServerVersion(ServerVersion.v15)` — uses
  the already-open `con` field. Use from `setUp()` or test methods.

`if (haveMinimumServerVersion(...))` silently passes the test on
older servers and hides regressions; `assumeTrue` records a skip
instead.
