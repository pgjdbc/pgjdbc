---
title: "Character encoding errors"
date: 2026-05-16T00:00:00Z
draft: false
weight: 8
toc: true
last_reviewed: "2026-05-16"
description: "pgJDBC hard-requires client_encoding=UTF8. Errors that surface when the server tries to change that, when the database itself is SQL_ASCII, and when the byte stream is not what UTF-8 expects — with the real fix and the workaround."
---

The driver hard-requires `client_encoding = UTF8` on every connection.
That's not a default — it's pinned in the startup message at
[`ConnectionFactoryImpl.java:460`](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java#L460)
— and the [`QueryExecutorImpl`](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java#L3072)
parameter-status handler enforces it for the lifetime of the
connection. Java strings are UTF-16 internally; pgJDBC converts to
UTF-8 at the wire, and any other client encoding breaks that
contract.

Three failure shapes follow from this:

```
PSQLException: The server's client_encoding parameter was changed to LATIN1.
  The JDBC driver requires client_encoding to be UTF8 for correct operation.

PSQLException: Invalid character data was found. This is most likely caused
  by stored data containing characters that are invalid for the character
  set the database was created in. The most common example of this is
  storing 8bit data in a SQL_ASCII database.

ERROR: invalid byte sequence for encoding "UTF8": 0xff
ERROR: character with byte sequence … in encoding "UTF8" has no equivalent
  in encoding "WIN1251"
```

The first comes from the driver and `SQLState 08006`
(`CONNECTION_FAILURE`); the second from `HStoreConverter`-style code
paths with `SQLState 22000` (`DATA_ERROR`); the third pair is from
the PostgreSQL server itself and arrives via the normal error
channel.

## `client_encoding` was changed to X

A `SET client_encoding = LATIN1` was issued somewhere — directly by
the application, by an `ALTER ROLE … SET client_encoding`, by an
`ALTER DATABASE … SET client_encoding`, or by a connection-pool
"init SQL" that copied a server-side recipe. The driver detects the
change in its parameter-status handler and closes the connection:
once the wire stops being UTF-8, no string the driver receives can
be trusted.

### Fix: undo the SET

Find the source. Common places:

```sql
-- Per-user override
ALTER ROLE alice RESET client_encoding;

-- Per-database override
ALTER DATABASE myapp RESET client_encoding;
```

If the override came from a pool's "init SQL" or a Spring
`DataSourceInitializer`, remove the entry. If from an
application-side `SET`, change the call site to not run it.

Server-side `client_encoding = UTF8` in `postgresql.conf` (or no
setting at all, since the default conversion is based on the
database's own encoding) is the right baseline; the per-role and
per-database overrides exist for libpq compatibility, not for pgJDBC.

### Workaround: `allowEncodingChanges=true`

[`allowEncodingChanges=true`](/documentation/reference/connection-properties/#prop-allowencodingchanges)
suppresses the connection-close. The driver downgrades the rejection
to a `Level.FINE` log line, swaps its internal `Encoding` to the new
value, and keeps going. The risk is real and the driver tells you
about it in the log:

> pgjdbc expects client_encoding to be UTF8 for proper operation.
> Actual encoding is LATIN1

Strings that round-trip Java → UTF-8 → server → LATIN1 (or back)
through code paths that assumed UTF-8 may silently corrupt outside
the ASCII range. Use this only when the application explicitly does
the encoding bookkeeping itself, or when none of the data goes
through `String` (you only operate on `bytea`).

## SQL_ASCII databases

`SQL_ASCII` is not an encoding. PostgreSQL treats it as "no
validation, any byte sequence is accepted and stored verbatim." Two
applications writing to the same `SQL_ASCII` database with different
encodings will mix their bytes silently; the bytes come back out as
they went in, and only their producer knows what they meant.

pgJDBC on a `SQL_ASCII` database operates as if the encoding were
`ASCII` / `US-ASCII` (see `Encoding.canonicalize`). Any text column
that contains an 8-bit byte (most of Latin-1, all UTF-8 multi-byte
sequences, any other 8-bit encoding) fails to decode and surfaces
as `Invalid character data was found …`.

### Fix: migrate the database to UTF-8

There is no in-place fix for `SQL_ASCII`. The recipe is:

```bash
# Dump the existing database
pg_dump --encoding=UTF8 -Fc myapp > myapp.dump

# Create a new database with UTF-8 encoding
createdb --encoding=UTF8 --locale=en_US.UTF-8 --template=template0 myapp_utf8

# Restore
pg_restore -d myapp_utf8 myapp.dump
```

The `--encoding=UTF8` flag on `pg_dump` may transcode 8-bit data on
the way out; if the source database mixes encodings, you need to
identify which rows are in which encoding before the dump. There is
no automated tool for this — it usually requires per-table or
per-application reasoning.

### Workaround: `bytea` for the affected columns

When the bytes in a `SQL_ASCII` table cannot be safely transcoded
(binary blobs, encrypted strings, opaque tokens that happen to be
stored in a `text` column), change the column type to `bytea`. The
driver then returns `byte[]` and never tries to decode the contents.
The application becomes responsible for any decoding that does
happen.

## Server-side encoding errors

The two messages that come from the server, not from pgJDBC, are
distinct from the cases above:

### `invalid byte sequence for encoding "UTF8"`

The data on the wire is not valid UTF-8. Since pgJDBC always sends
UTF-8 on the wire, this almost always means the **server is reading
bytes from an underlying source that is not UTF-8**:

- `COPY FROM` reading a file in a different encoding without
  `ENCODING 'LATIN1'` (or similar) in the `COPY` options.
- `pg_dump`/`psql` restoring a dump that was taken from a database
  with a different encoding.
- A trigger function constructing strings from `bytea` data.

The fix lives on whichever side is producing the non-UTF-8 bytes.

### `character … has no equivalent in encoding "X"`

The reverse — the server has a character that does not map to the
requested `client_encoding`. With pgJDBC this shouldn't occur (we
ask for UTF-8 and every Unicode code point has a UTF-8
representation), unless `allowEncodingChanges=true` was set AND a
mid-session `SET client_encoding = X` moved the wire to a narrower
encoding. In that case, return to UTF-8.

## Related

The driver exposes a single encoding-related knob,
[`allowEncodingChanges`](/documentation/reference/connection-properties/#prop-allowencodingchanges);
everything else on the JDBC side is fixed at startup or determined
by the server. The relevant external references:

- [Server preparation: Database encoding](/documentation/getting-started/server-prep/#database-encoding-is-utf-8)
  — why `CREATE DATABASE … WITH ENCODING 'UTF8'` is the only sane
  starting point.
- [PostgreSQL — Character Set Support](https://www.postgresql.org/docs/current/multibyte.html)
  — the server-side reference for encodings, conversion, and the
  full table of supported pairs.
