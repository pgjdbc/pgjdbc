---
title: "SCRAM authentication failed"
date: 2026-05-16T00:00:00Z
draft: false
weight: 3
toc: true
last_reviewed: "2026-05-21"
description: "SCRAM-SHA-256 and channel-binding failures — what each error message means and which server or property change resolves it."
---

SCRAM-SHA-256 has been the default `password_encryption` since
PostgreSQL&nbsp;14 and is the only authentication method on which
channel binding works. This page lists the SCRAM and channel-binding
failures on that path: what each user-visible error means and which
property or server change fixes it.

For the recommended-default posture (`sslmode=verify-full`,
`channelBinding=require`), see
[Configure SSL/TLS (in Quick start)](/documentation/getting-started/install/#configure-ssltls).

## `Channel Binding is required, but SSL is not in use`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ChannelBinding.java | pgjdbc/src/main/java/org/postgresql/core/v3/ChannelBinding.java | 16-43
- ScramAuthenticator.java | pgjdbc/src/main/java/org/postgresql/core/v3/ScramAuthenticator.java | 99-131
- SslTest.java | pgjdbc/src/test/java/org/postgresql/test/ssl/SslTest.java | 539-575
{{< /review >}}

Raised by `ScramAuthenticator.getChannelBindingData` when
`channelBinding=require` is combined with `sslmode=disable` (or any
configuration that never negotiates TLS). Channel binding ties the
SCRAM exchange to the TLS session — without TLS there is no channel
to bind to.

Resolution: pick one or the other.

- The intended posture is `sslmode=verify-full` + `channelBinding=require`.
  This is the combination [Configure SSL/TLS (in Quick start)](/documentation/getting-started/install/#configure-ssltls)
  recommends, and the only one that defends against an attacker who can
  terminate-and-replay the TLS handshake.
- If TLS is genuinely impossible (a local Unix-domain socket bridge,
  a closed dev loop) drop channel binding too: `channelBinding=prefer`
  or `disable`.

## `Channel Binding is required, but server did not offer an authentication method that supports channel binding`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ScramAuthenticator.java | pgjdbc/src/main/java/org/postgresql/core/v3/ScramAuthenticator.java | 75-96
{{< /review >}}

The server selected SASL/SCRAM, but the SASL mechanism list did not
include a `-PLUS` mechanism. pgJDBC raises this after it receives
`AuthenticationSASL` and finds no advertised mechanism whose name ends
with `-PLUS`.

The usual cause is an older server that supports SCRAM-SHA-256 but
not SCRAM-SHA-256-PLUS. Channel binding needs the
`scram-sha-256-plus` SASL mechanism, added in PostgreSQL&nbsp;11. On
older servers, use `channelBinding=prefer` until you can upgrade.

## `Channel binding is required, but server requested 'md5' authentication`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 758-779
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 845-859
- SslTest.java | pgjdbc/src/test/java/org/postgresql/test/ssl/SslTest.java | 578-593
{{< /review >}}

With `channelBinding=require`, pgJDBC rejects any non-SASL
authentication request before responding to it. The method name in
the message is whatever the server selected: `md5`, `password`,
`gss`, `sspi`, or a numeric code for an unsupported method.

Common causes:

- **`pg_hba.conf` matched a non-SCRAM rule.** Reorder or replace the
  matching rule so this client uses `hostssl ... scram-sha-256`.
- **The user is stored with MD5 password encryption and `pg_hba.conf`
  matched `md5`.** Re-set the user password while
  `password_encryption = scram-sha-256` is in effect in
  `postgresql.conf` (the default since PostgreSQL&nbsp;14):

  ```sql
  ALTER USER alice WITH PASSWORD 'plaintext';  -- gets stored as scram-sha-256
  ```

  `\\password` in psql works too. Confirm with:

  ```sql
  SELECT rolname, rolpassword FROM pg_authid WHERE rolname = 'alice';
  ```

  The value should start with `SCRAM-SHA-256$`.

## `Channel binding is required, but server skipped authentication`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 845-859
{{< /review >}}

The server accepted the connection without running SCRAM, usually
because the matching `pg_hba.conf` rule uses `trust`. Channel binding
can only be completed after a SCRAM-SHA-256-PLUS exchange, so pgJDBC
rejects this before the connection is handed back to the application.

Resolution: replace or reorder the matching `trust` rule so this
client uses `hostssl ... scram-sha-256`. If you intentionally rely on
`trust` for a closed local development loop, set
`channelBinding=prefer` or `disable` for that connection.

## `Channel Binding is required, but could not extract channel binding data from SSL session`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ScramAuthenticator.java | pgjdbc/src/main/java/org/postgresql/core/v3/ScramAuthenticator.java | 99-131
{{< /review >}}

The TLS handshake succeeded, but pgJDBC could not read or encode the
peer certificate from the JSSE session while building the
`tls-server-end-point` binding. In current code this message is raised
when `SSLSession.getPeerCertificates()` reports an unverified peer or
when the peer certificate cannot be encoded.

This normally points at the TLS socket/session implementation rather
than a pgJDBC property. Inspect a custom `SSLSocketFactory` first; the
stock `org.postgresql.ssl.LibPQFactory` is expected to expose the
server's X.509 certificate.

There is no client-side property to bypass — channel binding cannot
work without a server certificate.

## `Server requested N SCRAM PBKDF2 iterations, which exceeds the client-side limit of M`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 835-850
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 997-1021
- ScramAuthenticator.java | pgjdbc/src/main/java/org/postgresql/core/v3/ScramAuthenticator.java | 151-171
- ScramTest.java | pgjdbc/src/test/java/org/postgresql/jdbc/ScramTest.java | 126-195
{{< /review >}}

A safety cap. pgJDBC accepts at most `scramMaxIterations` PBKDF2
rounds from the server (default 100,000); higher counts are rejected
before the expensive PBKDF2 computation runs. Without the cap, a
malicious or compromised server could force the client to burn CPU
on an attacker-controlled iteration count.

Resolution depends on context:

- **You trust the server and it legitimately uses a high count.**
  Raise [`scramMaxIterations`](/documentation/reference/connection-properties/#prop-scrammaxiterations).
  Set to `0` to disable the check entirely (not recommended).
- **You do not trust the server.** Do not raise the limit — the
  default is the protection, not the problem.

## `Authentication method is not allowed by requireAuth`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 807-821
- AuthMethod.java | pgjdbc/src/main/java/org/postgresql/core/AuthMethod.java | 34-75
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 794-798
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 864-1042
- RequireAuthTest.java | pgjdbc/src/test/java/org/postgresql/test/core/RequireAuthTest.java | 129-210
{{< /review >}}

You configured an explicit allow-list of authentication methods via
[`requireAuth`](/documentation/reference/connection-properties/#prop-requireauth)
and the server offered something else. The property accepts a
comma-separated list of `password, md5, gss, sspi, scram-sha-256,
none`, with a `!` prefix for negative entries
(`requireAuth=!password,!md5` forbids cleartext and MD5).

Resolution:

- **Add the offered method to the list,** if it was an oversight.
- **Fix the server** — if the server offers `md5` and you wrote
  `requireAuth=scram-sha-256`, the password is stored under a weaker
  scheme. Migrate to SCRAM (see the first case above).

The error fires before authentication completes, so no credentials
are ever sent under the unwanted method.

## Authentication-related connection properties

{{< param-table data="connection-properties" tag="authentication" >}}

## Related

- [Authentication](/documentation/security/authentication/) — the
  conceptual companion to this page: which methods the driver
  supports, how the server-driven negotiation resolves, and the
  levers (`requireAuth`, `channelBinding`, `scramMaxIterations`,
  `AuthenticationPlugin`) that bound it.
- [Configure SSL/TLS (in Quick start)](/documentation/getting-started/install/#configure-ssltls)
  — the recommended-default combination of `sslmode=verify-full`,
  `sslrootcert`, and `channelBinding=require`.
- [SSL / TLS connection errors](/documentation/troubleshooting/ssl-errors/)
  — failure modes on the TLS layer underneath SCRAM. Channel binding
  cannot succeed if the TLS handshake itself doesn't.
- [Compatibility](/documentation/getting-started/compatibility/) —
  channel binding requires PostgreSQL&nbsp;11+; SCRAM-SHA-256 is the
  default password encoding from PG&nbsp;14.
