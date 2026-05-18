---
title: "SSL / TLS connection errors"
date: 2026-05-16T00:00:00Z
draft: false
weight: 2
toc: true
last_reviewed: "2026-05-16"
description: "Common TLS failure messages — PKIX path building failed, hostname mismatch, sslmode confusion, key-file format issues — with their cause and the exact connection-property fix."
---

The error surface around TLS spans two layers: the JDK's certificate /
hostname code (which raises generic `SSLHandshakeException`s) and
pgJDBC's own validators and key loaders (which raise
`PSQLException`s with project-specific wording). This page lists the
patterns you are most likely to see, paired with the property change
that resolves each one.

For the underlying setup — picking an `sslmode`, where keys and
certificates live on disk, how to provide a custom socket factory —
see [SSL / TLS](/documentation/security/ssl-tls/). For the safest
defaults on a fresh project, see the
[Configure SSL/TLS (in Quick start)](/documentation/getting-started/install/#configure-ssltls).

## `PKIX path building failed`

Full message, from the JDK (not pgJDBC):

```
javax.net.ssl.SSLHandshakeException: PKIX path building failed:
sun.security.provider.certpath.SunCertPathBuilderException: unable
to find valid certification path to requested target
```

The server's certificate chain does not terminate at a CA the JVM
trusts. Two paths to a fix:

- **Hand pgJDBC the CA explicitly.** Set
  [`sslrootcert`](/documentation/reference/connection-properties/#prop-sslrootcert)
  to a PEM file containing the CA (or chain) that signed the server
  certificate. This keeps the trust decision in your connection
  string and is the right choice for self-signed and internal CAs.
- **Import the CA into the JVM truststore.** Useful when many JVM
  processes need to trust the same CA. The recipe lives in
  [SSL / TLS § Configuring the Client](/documentation/security/ssl-tls/).

`sslmode=require` does **not** validate the chain — see [the install
page's callout](/documentation/getting-started/install/#configure-ssltls)
— so the error only fires under `verify-ca` and `verify-full`. If
you see it under `require`, the JVM is enforcing validation through
some other path (a `SocketFactory`, a security provider, an
`-Djavax.net.ssl.trustStore` system property).

## `The hostname X could not be verified by hostnameverifier Y`

pgJDBC's `MakeSSL` wraps the JDK's hostname check; the underlying
message in the log will be one of:

- `Server name validation failed: hostname X does not match common name Y`
- `Server name validation failed: certificate for hostname X has no DNS subjectAltNames…`
- `Hostname X is invalid`

The server certificate's Subject Alternative Names — or, as a
fallback, its Common Name — do not include the host you wrote in the
JDBC URL. Three resolutions, in decreasing preference:

- **Fix the certificate.** Issue a new server certificate whose SAN
  list contains every name clients use (DNS name, alias, public IP
  if you connect by IP). RFC 6125 / SAN is the modern requirement;
  CN-only certificates are accepted only as a fallback and are
  considered legacy.
- **Connect by a name the certificate covers.** If the certificate
  lists `db.example.com` and you connect with the IP, switch the URL
  to the hostname.
- **Drop to `sslmode=verify-ca`.** This still validates the chain
  but skips hostname matching. Accept it only when the connection
  target is not network-attacker-reachable (a Unix bridge, an
  in-cluster ClusterIP). On the public internet `verify-ca` does not
  protect against an attacker who can present a certificate signed
  by your CA.

## `The server does not support SSL`

Raised by `ConnectionFactoryImpl` when the client requires SSL
(`sslmode=require` or stricter) but the server has no SSL listener.
Likely causes:

- `ssl` is disabled in `postgresql.conf`. The `pg_hba.conf` `hostssl`
  rules also have no effect when the listener is off.
- The connection landed on a non-TLS port (PgBouncer / connection
  pooler with `server_tls_sslmode = disable`).
- The PostgreSQL distribution was built without OpenSSL. Common in
  some container images.

Confirm with `psql "host=… sslmode=require"`. If `psql` succeeds, the
problem is on the JDBC side (custom socket factory, security
provider). If `psql` also fails, it is genuinely a server-side issue.

## SSL key file errors

Three messages, all from the pgJDBC SSL key loaders:

- `Could not open SSL certificate file <path>`
- `Could not read SSL key file <path>`
- `Loading the SSL certificate <path> into a KeyManager failed.`

Together they cover the disk / format problem space:

- **File not found / unreadable.** Path is wrong or the JVM lacks
  read permission. The defaults are
  `${user.home}/.postgresql/postgresql.crt`,
  `…/postgresql.p12`, `…/root.crt`; override with `sslcert`,
  `sslkey`, `sslrootcert` to be explicit.
- **Wrong key format.** Modern pgJDBC expects a **PKCS-12** key
  (`.p12` or `.pfx` extension). PKCS-8 (`.pk8`) is still recognised
  but recent OpenSSL versions can no longer generate it. See
  [SSL / TLS](/documentation/security/ssl-tls/) for the
  `openssl pkcs12 -export` recipe.
- **PKCS-12 alias mismatch.** When generating the bundle, the alias
  passed to `openssl pkcs12 -export -name <alias>` must be `user`,
  otherwise pgJDBC won't find it in the keystore. The
  [`certdir/Makefile`](https://github.com/pgjdbc/pgjdbc/tree/master/certdir)
  in the repository carries a working example.

## SSL-related connection properties

All TLS knobs grouped together:

{{< param-table data="connection-properties" tag="ssl" >}}

## Related

- [SSL / TLS](/documentation/security/ssl-tls/) — setup-side
  documentation; this page is the diagnostic complement.
- [Configure SSL/TLS (in Quick start)](/documentation/getting-started/install/#configure-ssltls)
  — the recommended-default combination of `sslmode=verify-full`,
  `sslrootcert`, and `channelBinding=require`.
