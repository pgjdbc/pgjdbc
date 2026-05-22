---
title: "Authentication"
weight: 10
toc: true
last_reviewed: "2026-05-21"
description: "Authentication methods pgJDBC supports — SCRAM-SHA-256, MD5, cleartext password, Kerberos / GSSAPI / SSPI — how the server-driven negotiation works, and the levers that harden it: requireAuth, channelBinding, scramMaxIterations, and the AuthenticationPlugin SPI for custom credentials."
---

The authentication step of a pgJDBC connection runs after the optional TLS / GSS upgrade and before the connection is handed back to the application. The server, not the client, picks the authentication method — based on the `pg_hba.conf` rule that matches the connection's source — and the driver responds to whatever message arrives. This page describes the methods the driver supports, how the negotiation resolves, and the connection properties that bound it (allow-list which methods are acceptable, require channel binding, swap in a custom credential source).

For specific error messages on this path — `Channel Binding is required, but SSL is not in use`, `Authentication method is not allowed by requireAuth`, and friends — see [SCRAM authentication failed](/documentation/troubleshooting/scram-failed/). For the TLS layer that sits underneath, see [SSL / TLS](/documentation/security/ssl-tls/). For the recommended-default posture, see [Configure SSL/TLS (in Quick start)](/documentation/getting-started/install/#configure-ssltls).

## Methods the driver supports

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- AuthMethod.java | pgjdbc/src/main/java/org/postgresql/core/AuthMethod.java | 16-30
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 807-820
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 758-779
{{< /review >}}

`org.postgresql.core.AuthMethod` enumerates the six methods the server may request:

| Method | Server-side trigger | Notes |
|---|---|---|
| `scram-sha-256` | `pg_hba.conf` `scram-sha-256`, or `md5` with a SCRAM-stored password | Recommended. Salted, iterated PBKDF2. The only password method that supports channel binding. New passwords are stored as SCRAM by default since PostgreSQL&nbsp;14. |
| `md5` | `pg_hba.conf` `md5` with an MD5-stored password | Hashed but weak by modern standards; offers no channel binding. Migrate the user's password to SCRAM by re-setting it while `password_encryption = scram-sha-256` is in effect. |
| `password` (cleartext) | `pg_hba.conf` `password` method | The password is sent verbatim in the startup exchange. Only safe over TLS (and even then SCRAM is strictly better). |
| `gss` | `pg_hba.conf` `gss` method | Kerberos via GSSAPI on \*nix (and on Windows when `gsslib=gssapi`). |
| `sspi` | `pg_hba.conf` `sspi` method | Windows native single-sign-on via SSPI. Requires `waffle-jna` on the classpath. |
| `none` | `pg_hba.conf` `trust` method | No credentials. Reasonable for a Unix-domain-socket dev loop, dangerous over the network. |

The two extension-style methods — `gss` and `sspi` — are dispatched to the Kerberos stack and are tuned by `gsslib`, `gssEncMode`, `kerberosServerName`, `jaasApplicationName`, `jaasLogin`, `gssUseDefaultCreds`, `useSpnego`, and `sspiServiceClass`. See [Kerberos, GSSAPI, SSPI](/documentation/security/kerberos-gssapi/) for the dispatch model, JAAS configuration, the `waffle-jna` compatibility matrix, and `gssEncMode` for GSS-encrypted connections.

## How the negotiation resolves

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 781-1046
- ScramAuthenticator.java | pgjdbc/src/main/java/org/postgresql/core/v3/ScramAuthenticator.java | 134-194
- AuthenticationPluginManager.java | pgjdbc/src/main/java/org/postgresql/core/v3/AuthenticationPluginManager.java | 38-88
{{< /review >}}

After the startup packet, the server sends an `AuthenticationRequest` message naming the method to use, picked by matching the connection against `pg_hba.conf`. The driver routes the message to a handler:

- For `md5` and `password`, `AuthenticationPluginManager.withPassword` is called to obtain the credential, sends it, and **zero-wipes** the `char[]` before the call returns.
- For `scram-sha-256` (SASL), `ScramAuthenticator` runs the four-message SCRAM exchange (`SASLInitialResponse` → `SASLContinue` → `SASLResponse` → `SASLFinal`) with the chosen mechanism — `SCRAM-SHA-256-PLUS` when channel binding is in play, plain `SCRAM-SHA-256` otherwise.
- For `gss` and `sspi`, the dedicated Kerberos / SSPI paths run.
- For `none` (trust), the driver sends nothing and proceeds to the next startup message.

The application has no direct hand in this — the choice is the server's. The driver's role is to (a) respond correctly to whatever the server asks, (b) refuse to respond if `requireAuth` forbids the requested method, and (c) decide whether channel binding can be negotiated for SCRAM.

## Recommended baseline

For any production connection over a public or shared network:

```
sslmode=verify-full          # validate cert chain and SAN; defense against MITM
sslrootcert=/path/to/ca.crt  # what we validate the chain against
channelBinding=require       # tie the SCRAM exchange to the TLS channel
```

This is the combination [Configure SSL/TLS (in Quick start)](/documentation/getting-started/install/#configure-ssltls) recommends. It defends against the credible attacker — one who can intercept and re-negotiate the TLS handshake but does not have the server's private key — by ensuring (a) the certificate presented is the one we expect, and (b) the SCRAM exchange is bound to that TLS session.

For an extra notch of defense in depth, add an explicit `requireAuth=scram-sha-256` so the driver refuses MD5 even if `pg_hba.conf` is later relaxed; see the next section.

## `requireAuth` — allow-list / deny-list

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 807-820
- AuthMethod.java | pgjdbc/src/main/java/org/postgresql/core/AuthMethod.java | 34-75
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 794-798
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 864-1042
{{< /review >}}

[`requireAuth`](/documentation/reference/connection-properties/#prop-requireauth) (introduced in 42.7.0) accepts a comma-separated list of method names matching the table above, with optional `!` prefix to flip each entry into a negative. The driver evaluates the list in one of two modes:

- **Allow-list** — entries have no `!`. The driver only accepts a method that appears in the list. Example: `requireAuth=scram-sha-256` rejects MD5, cleartext password, and trust.
- **Deny-list** — every entry has `!`. The driver accepts any method *not* in the list. Example: `requireAuth=!password,!md5,!none` rejects the three weak options and accepts SCRAM / GSS / SSPI.

Mixing the two — `requireAuth=scram-sha-256,!md5` — fails parsing with `requireAuth cannot mix positive and negative authentication methods` (see `AuthMethod.parseRequireAuth`). Pick a stance.

The check fires *before* the driver responds to the `AuthenticationRequest`, so no credentials are ever transmitted under a rejected method. A mismatch raises `PSQLException` with `SQLState 08004` (`CONNECTION_REJECTED`) and the message `Authentication method is not allowed by requireAuth` — see [SCRAM authentication failed § Authentication method is not allowed by requireAuth](/documentation/troubleshooting/scram-failed/#authentication-method-is-not-allowed-by-requireauth) for the runtime behaviour.

## `channelBinding`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 188-201
- ChannelBinding.java | pgjdbc/src/main/java/org/postgresql/core/v3/ChannelBinding.java | 16-43
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 845-859
- ScramAuthenticator.java | pgjdbc/src/main/java/org/postgresql/core/v3/ScramAuthenticator.java | 52-131
- SslTest.java | pgjdbc/src/test/java/org/postgresql/test/ssl/SslTest.java | 539-596
{{< /review >}}

[`channelBinding`](/documentation/reference/connection-properties/#prop-channelbinding) (introduced in 42.7.0, default `prefer`) controls whether the SCRAM exchange is tied to the TLS channel:

- **`require`** — refuse SCRAM-SHA-256 without `-PLUS`; refuse the connection entirely if the server doesn't offer a `-PLUS` mechanism or if no TLS session is in use. This is the production posture.
- **`prefer`** (default) — use channel binding when both client and server support it, otherwise fall back to plain SCRAM-SHA-256. Suitable for mixed environments where some servers are too old for `-PLUS`.
- **`disable`** — never request channel binding, even if available.

Channel binding works *only* with SCRAM-SHA-256 (the `tls-server-end-point` binding hashes the server's TLS certificate into the SCRAM exchange). With `prefer` or `disable`, MD5, cleartext password, GSS, SSPI, and trust proceed without a binding; with `require`, current drivers reject non-SASL authentication before credentials are sent. Before 42.7.7 the driver allowed `channelBinding=require` to silently pass when the server selected a non-SASL method — a downgrade attack vector that was fixed via [CVE-2025-49146](/security/#security-advisories); pair `channelBinding=require` with `requireAuth=scram-sha-256` on older driver versions, or upgrade to 42.7.7+.

For the specific channel-binding error strings, see [SCRAM authentication failed](/documentation/troubleshooting/scram-failed/).

## `scramMaxIterations`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 835-850
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 997-1021
- ScramAuthenticator.java | pgjdbc/src/main/java/org/postgresql/core/v3/ScramAuthenticator.java | 151-171
{{< /review >}}

[`scramMaxIterations`](/documentation/reference/connection-properties/#prop-scrammaxiterations) (default `100000`, introduced in 42.7.11) caps the PBKDF2 iteration count the driver will accept from the server. Without it, a malicious or compromised server could pick an arbitrarily high iteration count and force the client to burn CPU before the connection even completes — a quiet denial-of-service vector.

The cap is checked *before* PBKDF2 runs. A server advertising more than `scramMaxIterations` rounds is refused with the message `Server requested N SCRAM PBKDF2 iterations, which exceeds the client-side limit of M`. Raise the value only if you trust the server and have audited that it legitimately uses a higher count; setting `0` disables the check entirely (not recommended).

## `AuthenticationPlugin` — custom credentials

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 106-116
- AuthenticationPlugin.java | pgjdbc/src/main/java/org/postgresql/plugin/AuthenticationPlugin.java | 12-31
- AuthenticationRequestType.java | pgjdbc/src/main/java/org/postgresql/plugin/AuthenticationRequestType.java | 8-13
- AuthenticationPluginManager.java | pgjdbc/src/main/java/org/postgresql/core/v3/AuthenticationPluginManager.java | 38-128
- ObjectFactory.java | pgjdbc/src/main/java/org/postgresql/util/ObjectFactory.java | 20-69
{{< /review >}}

When the password belongs to a credential source that isn't a string in the URL — IAM-generated short-lived tokens, Vault, a hardware token, a keyring — implement [`org.postgresql.plugin.AuthenticationPlugin`](https://github.com/pgjdbc/pgjdbc/blob/bd1af18230371879fb4127ae28800cf9a8a8c77d/pgjdbc/src/main/java/org/postgresql/plugin/AuthenticationPlugin.java) and name the class in [`authenticationPluginClassName`](/documentation/reference/connection-properties/#prop-authenticationpluginclassname).

The contract is short:

```java
public interface AuthenticationPlugin {
  char @Nullable [] getPassword(AuthenticationRequestType type) throws PSQLException;
}
```

`type` is one of `CLEARTEXT_PASSWORD`, `MD5_PASSWORD`, `SASL` (SCRAM), `GSS`. Returning `null` refuses the request — useful for per-type policy ("we never serve cleartext").

Three contract points to honour:

1. **Return a fresh `char[]` on every call.** The driver overwrites the array with zeroes after use (see `AuthenticationPluginManager.withPassword`); reusing a cached array would leave the buffer wiped on the next call.
2. **The class must have a public constructor that `ObjectFactory` can use.** For authentication plugins the driver first looks for a public `Properties` constructor, then falls back to a public no-arg constructor. If instantiation fails, the driver raises `Unable to load Authentication Plugin ...` with SQLState `22023` (`INVALID_PARAMETER_VALUE`).
3. **Don't perform unbounded work in `getPassword`.** The method runs on the connection-establishing thread; a hang here looks identical to a server-side hang and is bounded by `loginTimeout` (see [Timeouts](/documentation/connect/timeouts/)) rather than by anything in the plugin.

The plugin is not consulted for `trust` auth — the server never asks for a password and the driver never invokes the plugin. For SCRAM, the plugin returns the cleartext password, which the driver feeds into the SCRAM client; the password leaves the JVM in salted/iterated form, not as cleartext.

## Security history

- **CVE-2025-49146 (fixed in 42.7.7)** — `channelBinding=require` was silently honoured even when the server selected a non-SASL authentication method (e.g. MD5 or trust), allowing a server-side downgrade to defeat the binding. Pre-42.7.7 driver versions should pair `channelBinding=require` with `requireAuth=scram-sha-256`; alternatively upgrade. The advisory is at [GHSA-hq9p-pm7w-8p54](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-hq9p-pm7w-8p54); see the [Security page](/security/#security-advisories) for the project's full disclosure history.

## Related connection properties

{{< param-table data="connection-properties" tag="authentication" >}}

## Related

- [Kerberos, GSSAPI, SSPI](/documentation/security/kerberos-gssapi/) — the GSS/SSPI dispatch model, JAAS configuration, the `waffle-jna` compatibility matrix, and `gssEncMode` for GSS-encrypted connections.
- [SCRAM authentication failed](/documentation/troubleshooting/scram-failed/) — error-message decoding for the SCRAM and channel-binding paths.
- [SSL / TLS](/documentation/security/ssl-tls/) — the layer underneath; without it, channel binding cannot apply and cleartext-password traffic is in the clear.
- [Configure SSL/TLS (in Quick start)](/documentation/getting-started/install/#configure-ssltls) — the recommended-default combination.
- [Security advisories](/security/#security-advisories) — driver-level CVE / GHSA history including authentication-related ones.
- [Connection properties reference](/documentation/reference/connection-properties/) — every property in one place.
