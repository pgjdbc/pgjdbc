---
title: "Kerberos, GSSAPI, SSPI"
weight: 15
toc: true
last_reviewed: "2026-05-16"
description: "Kerberos-based authentication for pgJDBC: JSSE GSSAPI on *nix and cross-platform, Windows-native SSPI via waffle-jna, the gsslib auto-mode dispatch, gssEncMode for GSS-encrypted connections, and the JAAS knobs."
---

When `pg_hba.conf` matches a connection with the `gss` or `sspi` method, the server sends an `AuthenticationReqGSS` or `AuthenticationReqSSPI` message and the driver responds via one of two stacks: **JSSE GSSAPI** (the Java built-in Kerberos client, available on every platform) or **Windows SSPI** (native single-sign-on through `waffle-jna`). The dispatch between the two is driven by the `gsslib` property; the underlying credential acquisition is driven by JAAS or by a system ccache.

This page covers the configuration model. For the overall auth picture (`requireAuth`, `channelBinding`, the `AuthenticationPlugin` SPI), see [Authentication](/documentation/security/authentication/). For the TLS layer underneath, see [SSL / TLS](/documentation/security/ssl-tls/).

## When this path runs

The server, not the client, picks the authentication method. Three concrete signals fire this code path:

- `pg_hba.conf` line is `host ... gss` (server-side: PG built with `--with-gssapi`).
- `pg_hba.conf` line is `host ... sspi` (server-side: PG built with `--with-gssapi` *and* runs on Windows).
- `gssEncMode` requested an encrypted GSS connection at startup (covered below).

If `requireAuth` is set and does not allow `gss` or `sspi`, the driver refuses *before* responding; see [Authentication § `requireAuth`](/documentation/security/authentication/#requireauth-allow-list--deny-list).

## `gsslib`: GSSAPI vs SSPI dispatch

[`gsslib`](/documentation/reference/connection-properties/#prop-gsslib) (default `auto`) controls which stack handles the request, with three values:

| `gsslib` | Server requests `gss` | Server requests `sspi` |
|---|---|---|
| `auto` (default) | JSSE GSSAPI | SSPI on Windows with `waffle-jna`; otherwise JSSE GSSAPI |
| `gssapi` | JSSE GSSAPI | JSSE GSSAPI (forced even on Windows) |
| `sspi` | SSPI (or fail with `SSPI forced with gsslib=sspi, but SSPI not available`) | SSPI (same caveat) |

pgJDBC's default deliberately differs from libpq, which prefers Windows SSPI when available. The pgJDBC choice is to keep JSSE system properties (`java.security.krb5.conf`, JAAS configuration, `sun.security.jgss.*` debug flags) working uniformly; opting into SSPI is a conscious choice, not a side-effect of running on Windows. The trade-off is named in [`ConnectionFactoryImpl`](https://github.com/pgjdbc/pgjdbc/blob/bd1af18230371879fb4127ae28800cf9a8a8c77d/pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java) (search for "slightly different to libpq").

## JSSE GSSAPI configuration

JSSE GSSAPI is the cross-platform path; it works on \*nix, macOS, and Windows whenever `gsslib=gssapi` is forced or the server requests `gss`. Credential acquisition uses either JAAS or the system ccache.

### JAAS login (`jaasLogin=true`, default)

The driver creates a `LoginContext` named after [`jaasApplicationName`](/documentation/reference/connection-properties/#prop-jaasapplicationname) (default `pgjdbc`) and calls `lc.login()`. The application's JAAS configuration file (selected by the `java.security.auth.login.config` system property or installed in `~/.java.login.config`) supplies the login modules, typically `com.sun.security.auth.module.Krb5LoginModule`.

A minimal `jaas.conf`:

```
pgjdbc {
    com.sun.security.auth.module.Krb5LoginModule required
        useKeyTab=true keyTab="/etc/pgjdbc.keytab"
        principal="postgres-client@EXAMPLE.COM"
        doNotPrompt=true;
};
```

Run with `-Djava.security.auth.login.config=/path/to/jaas.conf`. The entry name must match `jaasApplicationName`.

### Using the system credential (`gssUseDefaultCreds=true`)

[`gssUseDefaultCreds`](/documentation/reference/connection-properties/#prop-gssusedefaultcreds) (default `false`) changes how the driver obtains the GSS credential inside the GSS context: with `true` the driver asks `GSSManager` for `INITIATE_ONLY` credentials without naming a principal, so JGSS pulls the JVM's *default* credential. That is typically the JAAS-provided Subject if JAAS ran, the system ccache when `-Dsun.security.jgss.native=true` is set (delegating to MIT Kerberos or Heimdal), or KCM on macOS. With `false` the driver constructs a `GSSName` from JAAS's principal and asks for a credential bound to that name explicitly.

`gssUseDefaultCreds=true` is the right setting when the default principal differs from `user@DEFAULT_REALM`, when ccache lives somewhere pure-Java JAAS doesn't reach, or when you want JGSS to negotiate the mechanism with the system Kerberos library.

### Skipping JAAS entirely (`jaasLogin=false`)

[`jaasLogin`](/documentation/reference/connection-properties/#prop-jaaslogin) (default `true`) controls whether the driver runs a JAAS `LoginContext.login()` step before authenticating. Set it to `false` when:

- The calling thread already runs inside a `Subject.doAs(...)` block (for example a Java EE container that completed JAAS login at deployment time), so the driver should reuse the current Subject rather than re-run JAAS.
- The application is configured for native GSS via `-Dsun.security.jgss.native=true` and `-Djavax.security.auth.useSubjectCredsOnly=false`, so JAAS is not the credential source.

To use the system ccache *exclusively*, combine `jaasLogin=false` with `gssUseDefaultCreds=true`: the first skips JAAS, the second tells JGSS to pull the default credential rather than one bound to a JAAS-supplied principal.

### `kerberosServerName`

[`kerberosServerName`](/documentation/reference/connection-properties/#prop-kerberosservername) (default `postgres`) is the SPN's service-class part: the SPN the client requests is `<kerberosServerName>/<host>@<realm>`. The default matches libpq's `PGKRBSRVNAME`; change it only if the server-side keytab is registered under a non-default SPN.

## SSPI configuration (Windows-native)

SSPI is the Windows-native path. It is dispatched when:

- `gsslib=auto` and the server sent `AuthenticationReqSSPI`, **and**
- The JVM is running on Windows, **and**
- The `waffle-jna` library and its `jna` dependency are on the classpath.

Without all three, the driver falls back to JSSE GSSAPI. With `gsslib=sspi` forced, missing waffle-jna fails the connection outright with `SSPI forced with gsslib=sspi, but SSPI not available`.

### Adding `waffle-jna`

pgJDBC **does not bundle** `waffle-jna` in its jar; every distribution declares it as an optional dependency and applications opt in by adding it. Maven:

```xml
<dependency>
    <groupId>com.github.waffle</groupId>
    <artifactId>waffle-jna</artifactId>
    <version>1.9.1</version>
    <optional>true</optional>
</dependency>
```

### `waffle-jna` compatibility matrix

| pgJDBC | `waffle-jna` |
|---|---|
| `< 42.7.1` | `1.x` only |
| `≥ 42.7.1` | `1.x`, `2.x`, or `3.x` |

`42.7.1` switched to a reflective `ManagedSecBufferDesc` lookup ([PR #2720](https://github.com/pgjdbc/pgjdbc/pull/2720)) so the post-2.0 waffle-jna binary contract is also accepted. Older pgJDBC versions hard-bind to the 1.x layout and break against `waffle-jna ≥ 2.0`.

### `useSpnego` and `sspiServiceClass`

- [`useSpnego`](/documentation/reference/connection-properties/#prop-usespnego) (default `false`) wraps the SSPI exchange in SPNEGO. Set this to `true` when the SSPI authentication request originates from a setup that negotiates the mechanism (e.g., interop with a non-Kerberos SSPI provider).
- [`sspiServiceClass`](/documentation/reference/connection-properties/#prop-sspiserviceclass) (default `POSTGRES`) is the service-class part of the SPN that SSPI requests. The default is correct for standard PostgreSQL builds; change it only if the server registers under a non-standard SPN. Ignored on non-Windows platforms.

## `gssEncMode`: GSS-encrypted connections (PG 12+)

PostgreSQL 12 added GSSAPI-encrypted connections as a parallel to TLS: the wire is encrypted using the GSS session key established during authentication, no certificates required. [`gssEncMode`](/documentation/reference/connection-properties/#prop-gssencmode) (default `allow`) controls whether the driver requests this upgrade:

| Mode | Behaviour |
|---|---|
| `disable` | Never request GSS encryption. |
| `allow` (default) | Connect without GSS encryption. Equivalent to `disable` on outbound: pgJDBC does not send `GSSENCRequest` and the PostgreSQL protocol has no server-initiated path to flip the connection to GSS mid-flight, so this mode never produces a GSS-encrypted connection. |
| `prefer` | Send `GSSENCRequest`; use GSS encryption if the server accepts, fall back to plain text if it refuses. |
| `require` | Send `GSSENCRequest`; fail the connection if the server refuses or errors. |

To actually get a GSS-encrypted connection you need `prefer` or `require`. Leaving `gssEncMode` at its default `allow` is functionally the same as `disable` for the encrypted-transport question; the mode exists for symmetry with `sslmode` rather than as a useful default. The two modes that do send `GSSENCRequest` differ only in what happens when the server refuses (`prefer`: reconnect plain; `require`: error out).

The driver attempts GSS encryption **before** TLS: if GSS-encrypted negotiation succeeds, the SSL upgrade step is skipped entirely. With `sslmode=require` and `gssEncMode=require` against a server that supports both, the connection ends up GSS-encrypted, not TLS-encrypted. Set `gssEncMode=disable` (or leave it at `allow`) if you need TLS-only.

The wait for the server's response byte to `GSSENCRequest` is bounded by [`gssResponseTimeout`](/documentation/reference/connection-properties/#prop-gssresponsetimeout) (default `5000` ms), capped by the remaining `connectTimeout`. See [Timeouts](/documentation/connect/timeouts/) for the broader budget.

## Debugging

The driver writes Kerberos / SSPI events at `Level.FINEST` on the `org.postgresql` logger (see [Driver logging](/documentation/runtime/logging/) for how to enable). For the layers underneath, three JVM system properties cover almost every diagnostic question:

```
-Dsun.security.krb5.debug=true     # Kerberos round-trips, ticket cache reads
-Dsun.security.jgss.debug=true     # JGSS mechanism selection, GSS context state
-Djava.security.debug=gssloginconfig,configfile,configparser
                                    # JAAS config file resolution
```

For SSPI, set the standard `waffle-jna` logger to `TRACE`. Common failure shapes:

- `No valid credentials provided (GSSException: No valid credentials provided ...)`. JAAS login produced no credential. Check `kinit -l` (\*nix), the keytab path in `jaas.conf`, and the principal spelling. If `gssUseDefaultCreds=true`, verify the ccache (`klist`).
- `Server not found in Kerberos database`. The SPN the client requested doesn't exist in the KDC. Match `kerberosServerName` (or `sspiServiceClass` on Windows) to whatever is registered server-side.
- `Clock skew too great`. Kerberos requires clocks within ~5 minutes by default. Sync NTP on both ends.
- `SSPI forced with gsslib=sspi, but SSPI not available`. Either you're not on Windows, or `waffle-jna` is missing from the classpath, or its JNA dependency failed to load native libraries (check JVM architecture matches the bundled JNA).

## Related connection properties

{{< param-table data="connection-properties" tag="kerberos_gss" >}}

## Related

- [Authentication](/documentation/security/authentication/): the conceptual umbrella over all auth methods (SCRAM, MD5, password, GSS, SSPI, trust) and the levers (`requireAuth`, `channelBinding`, `AuthenticationPlugin`).
- [Timeouts](/documentation/connect/timeouts/): `gssResponseTimeout` and how it fits the rest of the per-phase timeout budget.
- [SSL / TLS](/documentation/security/ssl-tls/): the alternative transport when GSS encryption is not in play.
- [Driver logging](/documentation/runtime/logging/): enabling the FINEST-level driver trace alongside the JVM Kerberos debug flags.
- [Connection properties reference](/documentation/reference/connection-properties/): every property in one place.
