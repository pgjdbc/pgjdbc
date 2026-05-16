---
title: "Connection pooling"
weight: 13
toc: true
last_reviewed: "2026-05-16"
description: "Running pgJDBC under HikariCP, Tomcat JDBC, or c3p0 — pool sizing, validation, the per-connection state that survives checkouts (prepared-statement cache, metadata cache), tcpKeepAlive under pool ownership, and pgJDBC's interaction with server-side poolers like PgBouncer."
---

The driver was built to be checked out from a pool, used briefly, and returned. Opening a fresh connection is expensive — TCP handshake, optional TLS / GSS upgrade, authentication round-trips, startup packet, several internal `SET` queries — and the per-connection state the driver builds up (prepared-statement cache, metadata cache, server-side prepared statements) only pays off when the connection lives long enough to be reused. Every modern Java stack uses a connection pool in front of pgJDBC; the question is which one and how to configure it.

This page is the *configuration* counterpart to two neighbouring pages: [Connection Pools and Data Sources](/documentation/connect/datasource/) covers the JDBC `DataSource` / `ConnectionPoolDataSource` API contracts and pgJDBC's bundled implementations, while [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/) is the operational recipe for connections that die between checkouts (LB idle timeouts, server-side terminators, network blips). This page sits between them: how to spin up HikariCP / Tomcat JDBC / c3p0 around pgJDBC, what to set on the driver side under pool ownership, and which behaviour is per-pool vs per-connection.

## Pool sizing: fewer than you think

A PostgreSQL backend is one OS process per connection, with its own memory footprint (typically 5–15 MiB resident at minimum, plus `work_mem` per query). Saturating the server CPU with too many connections causes context-switching contention long before it produces useful concurrency. The HikariCP wiki's [pool sizing guide](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) lays out the math; the executive summary is **pool size ≈ `((core_count * 2) + effective_spindle_count)`** as an upper bound, often well below typical defaults.

Practical implication: most application servers want **10–20 connections**, not 100. If load suggests more, look first at:

- Query latency — are connections checked out for longer than they should be? `socketTimeout` and `Statement.setQueryTimeout` cap the worst case (see [Timeouts](/documentation/connect/timeouts/)).
- A separate **read-replica pool** with its own `ApplicationName` for the workload that does not need primary.
- A server-side pooler (PgBouncer, pgcat) in transaction mode for thousands of short-lived requests on a small fixed backend count — see below for the prepared-statement caveat.

## HikariCP

HikariCP is the recommended choice for new Java stacks. Its defaults are mostly correct for pgJDBC; the configuration below adds the pgJDBC-specific properties (`tcpKeepAlive`, `socketTimeout`, `ApplicationName`) and overrides idle-eviction to sit below typical cloud LB timeouts:

```java
HikariConfig cfg = new HikariConfig();
cfg.setJdbcUrl("jdbc:postgresql://db.internal:5432/app");
cfg.setUsername("app");
cfg.setPassword(System.getenv("PGPASSWORD"));
cfg.setMaximumPoolSize(15);
cfg.setMinimumIdle(2);
cfg.setIdleTimeout(Duration.ofMinutes(4).toMillis());   // below typical LB ceilings
cfg.setMaxLifetime(Duration.ofMinutes(30).toMillis());  // recycle proactively
cfg.setConnectionTimeout(Duration.ofSeconds(5).toMillis());

// pgJDBC connection properties
cfg.addDataSourceProperty("ApplicationName", "api-write");
cfg.addDataSourceProperty("tcpKeepAlive", "true");
cfg.addDataSourceProperty("socketTimeout", "30");       // seconds
cfg.addDataSourceProperty("loginTimeout", "10");        // seconds
// Optional: sslmode/sslrootcert for TLS — see Quick Start §3
cfg.addDataSourceProperty("sslmode", "verify-full");
cfg.addDataSourceProperty("sslrootcert", "/etc/ssl/rds-ca.pem");
cfg.addDataSourceProperty("channelBinding", "require");

HikariDataSource ds = new HikariDataSource(cfg);
```

Notes:

- **Validation is on by default.** HikariCP validates connections via `Connection.isValid()` on checkout, with one nuance worth knowing: connections that were used within the `aliveBypassWindowMs` (default 500 ms) skip the check, on the assumption that anything used so recently is still alive. The net effect is that under steady load most checkouts pay no validation cost, while idle-or-rare checkouts do get the round-trip. pgJDBC's `isValid()` is a cheap protocol round-trip in any case. Do **not** set `connectionTestQuery` — that legacy property exists for drivers older than JDBC 4.0 and disables the faster `isValid()` path.
- **`idleTimeout < LB idle ceiling`.** Most cloud LBs drop idle TCP at 350–900 s; setting `idleTimeout` to 4 min has the pool close idle connections before the network does. Pair with `tcpKeepAlive=true` so any connections that *do* outlive `idleTimeout` (because traffic was steady) still survive (see [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/)).
- **`maxLifetime`** caps how long a connection lives even under continuous traffic. 30 min is a sane default; lower it if your DNS / TLS infrastructure rolls credentials on a tighter cadence.

## Tomcat JDBC

Tomcat JDBC pre-dates HikariCP and is the default for many Spring Boot deployments before HikariCP became the default in 2.0+. It does not enable validation out of the box; configure it explicitly:

```properties
url=jdbc:postgresql://db.internal:5432/app
username=app
password=${PGPASSWORD}

maxActive=15
minIdle=2
maxIdle=15

testOnBorrow=true
validationQuery=SELECT 1
validationInterval=30000        # ms — re-check no more than once every 30 s
validationQueryTimeout=2        # seconds

# Per-connection idle eviction
timeBetweenEvictionRunsMillis=60000
minEvictableIdleTimeMillis=240000   # 4 min, like HikariCP idleTimeout

# pgJDBC connection properties
connectionProperties=ApplicationName=api-write;tcpKeepAlive=true;socketTimeout=30;loginTimeout=10
```

Notes:

- `testOnBorrow=true` is the equivalent of HikariCP's default validation. The combination with `validationInterval=30000` avoids running `SELECT 1` on *every* checkout for a connection that was validated 5 s ago.
- Tomcat JDBC will also accept `validationQuery=` empty + a JDBC 4 driver to use `isValid()`, but the explicit `SELECT 1` is portable across pool versions.

## c3p0

c3p0 is older and slower; use it only when you cannot move to HikariCP or Tomcat JDBC. Bare-minimum production config:

```xml
<bean id="dataSource" class="com.mchange.v2.c3p0.ComboPooledDataSource">
    <property name="driverClass" value="org.postgresql.Driver"/>
    <property name="jdbcUrl" value="jdbc:postgresql://db.internal:5432/app"/>
    <property name="user" value="app"/>
    <property name="password" value="${PGPASSWORD}"/>

    <property name="maxPoolSize" value="15"/>
    <property name="minPoolSize" value="2"/>
    <property name="maxIdleTime" value="240"/>          <!-- seconds -->
    <property name="idleConnectionTestPeriod" value="60"/>

    <property name="preferredTestQuery" value="SELECT 1"/>
    <property name="testConnectionOnCheckout" value="true"/>

    <property name="properties">
        <props>
            <prop key="ApplicationName">api-write</prop>
            <prop key="tcpKeepAlive">true</prop>
            <prop key="socketTimeout">30</prop>
        </props>
    </property>
</bean>
```

c3p0 does not call `isValid()` even on JDBC 4 drivers — `preferredTestQuery` is mandatory. `testConnectionOnCheckout` is expensive but correct; for high-throughput workloads consider `idleConnectionTestPeriod` only and accept the occasional dead-connection retry.

## Per-connection state — what survives a checkout

Pooled connections are *reused*, which is the whole point — but the state the driver builds up per connection survives between checkouts unless explicitly reset. Knowing what survives shapes both the right pool size and the right tuning.

- **Prepared-statement cache.** [`preparedStatementCacheQueries`](/documentation/reference/connection-properties/#prop-preparedstatementcachequeries) (default 256) and [`preparedStatementCacheSizeMiB`](/documentation/reference/connection-properties/#prop-preparedstatementcachesizemib) (default 5 MiB) are **per connection**. With a pool of 15, the worst case is 15 × 256 ≈ 3,800 named server-side prepared statements and ~75 MiB of client cache. For pgJDBC's view of what activates these, see the [Execution model](/documentation/query/prepared-statements/#execution-model) section.
- **Field-metadata cache.** [`databaseMetadataCacheFields`](/documentation/reference/connection-properties/#prop-databasemetadatacachefields) (default 65,536) and [`databaseMetadataCacheFieldsMiB`](/documentation/reference/connection-properties/#prop-databasemetadatacachefieldsmib) (default 5 MiB) — also per connection. The cache keys are `(oid, mod, column index)` triples, so this scales with the number of distinct columns seen, not with query volume.
- **Session state.** `autoCommit`, transaction isolation, `readOnly`, `search_path`, `set` parameters issued by the application, the application's `ApplicationName` — all persist on the backend across checkouts. Reset what matters at borrow time (HikariCP does the JDBC-defined reset automatically; for application-set GUCs use `Connection.unwrap(PGConnection.class)` and `RESET ALL` if needed).

This is also why **pool size has a multiplier effect on server-side memory**: each pgJDBC connection owns up to `preparedStatementCacheQueries` named server-prepared statements, each of which holds plan memory on the backend. Pooling 100 connections × 256 cached statements = 25,600 server-side prepared statements, which is fine for PostgreSQL but matters for memory budgeting.

## `ApplicationName` for `pg_stat_activity`

Set [`ApplicationName`](/documentation/reference/connection-properties/#prop-applicationname) per logical pool — `api-write`, `api-read`, `batch`, `reporting` — so the DBA can attribute load in `pg_stat_activity`:

```sql
SELECT application_name, state, COUNT(*)
FROM pg_stat_activity
WHERE datname = 'app'
GROUP BY 1, 2 ORDER BY 1, 2;
```

This is a one-line change and the single highest-value piece of operational hygiene under a pool. With it set, slow-query post-mortems and lock-waits investigation start from the name of the workload that issued the query. Without it the `pg_stat_activity` row shows `application_name = ''` for every backend and the DBA is reading the IP-port pair instead.

## Validation

The dead-connection problem — checked-out from the pool, the underlying TCP died between checkout and use — has its own operational page: [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/). The short version: enable validation on the pool, leave HikariCP's defaults alone, set Tomcat JDBC's `testOnBorrow=true` and c3p0's `preferredTestQuery=SELECT 1`. The longer version — `tcpKeepAlive` plus the kernel sysctl recipe, why `socketTimeout` does not by itself rescue an idle pooled connection, server-side `tcp_user_timeout` — lives on that page.

## Timeouts under a pool

Each layer has its own knob:

| Layer | Knob | What it caps |
|---|---|---|
| pool checkout | HikariCP `connectionTimeout` / Tomcat `maxWait` / c3p0 `checkoutTimeout` | How long an application thread waits for a free connection. |
| pgJDBC connect | `loginTimeout` | Wall-clock for the *whole* `getConnection()` when the pool needs to add a new connection. |
| pgJDBC connect (per-phase) | `connectTimeout`, `sslResponseTimeout`, `gssResponseTimeout` | TCP / TLS / GSS phase budgets. |
| Steady-state | `socketTimeout` | Any read on an established socket; the only knob that defends a pooled connection in flight. |
| Per-statement | `Statement.setQueryTimeout` + server-side `statement_timeout` | Individual query cancel. |

See [Timeouts](/documentation/connect/timeouts/) for how the connection-side knobs layer. The pool's checkout timeout is independent — set it short enough that requests fail fast when the pool is exhausted rather than queueing forever.

## Server-side poolers (PgBouncer, pgcat)

PgBouncer in **transaction-pooling mode** breaks server-side prepared statements: the backend connection is swapped between client transactions, and the prepared statement the driver created in transaction A no longer exists when the next transaction lands on a different backend. The driver surfaces this as `prepared statement "S_X" does not exist` — see [Prepared statement cannot change](/documentation/troubleshooting/prepared-statement-cannot-change/).

Two ways to coexist with transaction pooling:

- **PgBouncer 1.21+ with `max_prepared_statements`.** PgBouncer tracks prepared statements per logical client and replays them as connections are swapped. This is the right answer for new deployments — set `max_prepared_statements` on the PgBouncer side and leave the pgJDBC defaults alone.
- **Disable server-prepared statements at the driver.** Set `prepareThreshold=0` (no server-side `PREPARE`; the driver still uses the extended protocol for `Bind`/`Execute` with parameters). Older PgBouncer versions require this.

Session pooling and statement pooling have different trade-offs; for the full discussion see the PostgreSQL `PgBouncer` documentation.

## What pgJDBC's bundled `PGPoolingDataSource` is for

pgJDBC ships its own `org.postgresql.ds.PGPoolingDataSource` in `connect/datasource`. It is intentionally minimal (no idle eviction, no connection-broken detection, no separate connection user) and the driver's own documentation says not to use it in production. It exists to give applications running outside an application server a *something* without forcing a dependency on HikariCP / Tomcat JDBC / c3p0; the moment the application can pull in any of those, it should.

## Connection properties worth setting under a pool

| Property | Recommended | Why |
|---|---|---|
| [`ApplicationName`](/documentation/reference/connection-properties/#prop-applicationname) | Distinct name per pool | `pg_stat_activity` attribution. |
| [`tcpKeepAlive`](/documentation/reference/connection-properties/#prop-tcpkeepalive) | `true` | Detect dead connections in conjunction with kernel keepalive. |
| [`socketTimeout`](/documentation/reference/connection-properties/#prop-sockettimeout) | non-zero seconds | Caps mid-query hangs; without it, a dead network silently blocks the pool slot. |
| [`loginTimeout`](/documentation/reference/connection-properties/#prop-logintimeout) | non-zero seconds | Caps pool growth attempts against a hung server (see [Timeouts](/documentation/connect/timeouts/)). |
| [`prepareThreshold`](/documentation/reference/connection-properties/#prop-preparethreshold) | default `5`, or `0` behind transaction-pooling PgBouncer < 1.21 | Trades CPU for memory; the default is good for most workloads. |
| [`preparedStatementCacheQueries`](/documentation/reference/connection-properties/#prop-preparedstatementcachequeries) | default 256 | Scale-down if the app has thousands of distinct queries and pool is large. |
| `sslmode`, `sslrootcert`, `channelBinding` | `verify-full`, full path, `require` | The recommended security baseline; see [Quick Start § 3](/documentation/getting-started/install/#3-configure-ssltls). |

## Related

- [Connection Pools and Data Sources](/documentation/connect/datasource/) — JDBC `DataSource` / `ConnectionPoolDataSource` API and pgJDBC's bundled implementations.
- [Connection closed unexpectedly](/documentation/troubleshooting/connection-closed-unexpectedly/) — the operational recipe for connections that die between checkouts; `tcpKeepAlive` + sysctl, `socketTimeout`, pool validation.
- [Timeouts](/documentation/connect/timeouts/) — per-phase budgets for `getConnection()` and steady-state reads under a pool.
- [Server-prepared statements](/documentation/query/prepared-statements/) — when `prepareThreshold` activates the cache and why per-connection cache size matters.
- [Prepared statement cannot change](/documentation/troubleshooting/prepared-statement-cannot-change/) — the failure shape behind PgBouncer transaction pooling and schema migration.
