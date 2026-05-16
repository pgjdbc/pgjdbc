---
title: "Getting results based on a cursor"
date: 2026-05-13T00:00:00Z
draft: false
weight: 30
toc: true
last_reviewed: "2026-05-13"
aliases:
    - "/documentation/query/#getting-results-based-on-a-cursor/"
---

By default, the driver collects all the results for the query at once. This can be inconvenient for large data sets so 
the JDBC driver provides a means of basing a `ResultSet` on a database cursor and only fetching a small number of rows.

A small number of rows are cached on the client side of the connection and when exhausted the next block of rows is 
retrieved by repositioning the cursor.

> **NOTE**
>
> Cursor based `ResultSets` cannot be used in all situations. There a number of restrictions which will make the driver 
> silently fall back to fetching the whole `ResultSet` at once.
>
> * The connection to the server must be using the V3 protocol. This is the default for (and is only supported by) 
> server versions 7.4 and later.
>
> * The `Connection` must not be in autocommit mode. The backend closes cursors at the end of transactions, so in 
> autocommit mode the backend will have closed the cursor before anything can be fetched from it.
>
> * The `Statement` must be created with a `ResultSet` type of `ResultSet.TYPE_FORWARD_ONLY`. This is the default,
> so no code will need to be rewritten to take advantage of this, but it also means that you cannot scroll backwards or 
> otherwise jump around in the `ResultSet`.
>
> * The query given must be a single statement, not multiple statements strung together with semicolons.

##### Example 5.2. Setting fetch size to turn cursors on and off.

Changing the code to use cursor mode is as simple as setting the fetch size of the `Statement` to the appropriate size. 
Setting the fetch size back to 0 will cause all rows to be cached (the default behaviour).

```java
// make sure autocommit is off
conn.setAutoCommit(false);
Statement st = conn.createStatement();

// Turn use of the cursor on.
st.setFetchSize(50);
ResultSet rs = st.executeQuery("SELECT * FROM mytable");
while (rs.next()) {
    System.out.print("a row was returned.");
}
rs.close();

// Turn the cursor off.
st.setFetchSize(0);
rs = st.executeQuery("SELECT * FROM mytable");
while (rs.next()) {
    System.out.print("many rows were returned.");
}
rs.close();

// Close the statement.
st.close();
```

## Bounding the result set by buffer size

When the right fetch size is hard to predict — wide rows, mixed
queries, JVMs with varying heap budgets — the
[`maxResultBuffer`](/documentation/reference/connection-properties/#prop-maxresultbuffer)
property caps how many bytes a single `ResultSet` may accumulate
before the driver raises an error. The value can be expressed in
two styles:

- **Byte sizes** with an optional unit suffix: `100` (bytes),
  `150M`, `300K`, `400G`, `1T`.
- **A percentage of the max heap**: `10p`, `15pct`, `20percent`.

Regardless of how it is written, the effective ceiling is 90 % of
the JVM's maximum heap — values above that are silently clamped to
the cap. The default is unset, meaning result-set reads are
unbounded.

### Adaptive fetch

[`adaptiveFetch`](/documentation/reference/connection-properties/#prop-adaptivefetch)
turns `maxResultBuffer` into a dynamic fetch sizer: the driver
divides the remaining buffer by the largest row it has seen so far
and uses that as the next round-trip's fetch size. The first
round-trip still uses `defaultRowFetchSize`, and the computed value
is clamped by
[`adaptiveFetchMinimum`](/documentation/reference/connection-properties/#prop-adaptivefetchminimum)
and
[`adaptiveFetchMaximum`](/documentation/reference/connection-properties/#prop-adaptivefetchmaximum).
Both `maxResultBuffer` and `defaultRowFetchSize` must be set for
adaptive fetch to take effect.
