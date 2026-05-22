---
title: "SSL / TLS connection errors"
date: 2026-05-16T00:00:00Z
draft: false
weight: 2
toc: true
last_reviewed: "2026-05-21"
description: "Common TLS failure messages (PKIX path building failed, hostname mismatch, `sslmode` confusion, key-file format issues), with the cause and the exact connection-property fix for each."
---

The error surface around TLS spans two layers: the JDK's certificate /
hostname code (which raises generic `SSLHandshakeException`s) and
pgJDBC's own validators and key loaders (which raise
`PSQLException`s with project-specific wording). This page lists the
patterns you are most likely to see, paired with the property change
that resolves each one.

For the underlying setup (picking an `sslmode`, where keys and
certificates live on disk, how to provide a custom socket factory),
see [SSL / TLS](/documentation/security/ssl-tls/). For the safest
defaults on a fresh project, see the
[Configure SSL/TLS (in Quick start)](/documentation/getting-started/install/#configure-ssltls).

## `PKIX path building failed`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- SslMode.java | pgjdbc/src/main/java/org/postgresql/jdbc/SslMode.java | 55-60
- LibPQFactory.java | pgjdbc/src/main/java/org/postgresql/ssl/LibPQFactory.java | 147-201
- MakeSSL.java | pgjdbc/src/main/java/org/postgresql/ssl/MakeSSL.java | 49-54
{{< /review >}}

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

`sslmode=require` does **not** validate the chain (see [the install
page's callout](/documentation/getting-started/install/#configure-ssltls)),
so the error only fires under `verify-ca` and `verify-full`. If
you see it under `require`, the JVM is enforcing validation through
some other path (a `SocketFactory`, a security provider, an
`-Djavax.net.ssl.trustStore` system property).

## `The hostname X could not be verified by hostnameverifier Y`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- MakeSSL.java | pgjdbc/src/main/java/org/postgresql/ssl/MakeSSL.java | 60-95
- PGjdbcHostnameVerifier.java | pgjdbc/src/main/java/org/postgresql/ssl/PGjdbcHostnameVerifier.java | 80-222
{{< /review >}}

pgJDBC's `MakeSSL` wraps the JDK's hostname check; the underlying
message in the log will be one of:

- `Server name validation failed: hostname X does not match common name Y`
- `Server name validation failed: certificate for hostname X has no DNS subjectAltNames…`
- `Hostname X is invalid`

The server certificate's Subject Alternative Names (or, as a
fallback, its Common Name) do not include the host you wrote in the
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

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- SslMode.java | pgjdbc/src/main/java/org/postgresql/jdbc/SslMode.java | 51-52
- ConnectionFactoryImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java | 644-718
{{< /review >}}

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

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- LibPQFactory.java | pgjdbc/src/main/java/org/postgresql/ssl/LibPQFactory.java | 74-145
- LibPQFactory.java | pgjdbc/src/main/java/org/postgresql/ssl/LibPQFactory.java | 220-235
- LazyKeyManager.java | pgjdbc/src/main/java/org/postgresql/ssl/LazyKeyManager.java | 142-286
- PKCS12KeyManager.java | pgjdbc/src/main/java/org/postgresql/ssl/PKCS12KeyManager.java | 47-85
- PEMKeyManager.java | pgjdbc/src/main/java/org/postgresql/ssl/PEMKeyManager.java | 42-101
- BaseX509KeyManager.java | pgjdbc/src/main/java/org/postgresql/ssl/BaseX509KeyManager.java | 60-67
- certdir/Makefile | certdir/Makefile | 19-21
{{< /review >}}

Common messages from the pgJDBC SSL key loaders include:

- `Could not open SSL certificate file <path>`
- `Could not read SSL key file <path>`
- `Loading the SSL certificate <path> into a KeyManager failed.`
- `Could not initialize PEMKeyManager.`
- `Could not load the private key`
- `Could not load cert chain`

Together they cover the disk / format problem space:

- **File not found / unreadable.** Path is wrong or the JVM lacks
  read permission. The defaults are
  `${user.home}/.postgresql/postgresql.crt`,
  `…/postgresql.pk8`, `…/root.crt`; override with `sslcert`,
  `sslkey`, `sslrootcert` to be explicit.
- **Wrong key format.** pgJDBC selects the key loader from the
  `sslkey` filename: `.p12` / `.pfx` use the PKCS-12 loader,
  `.key` / `.pem` use the PEM loader, and other extensions use the
  PKCS-8 DER loader. Recent OpenSSL versions no longer support the
  older PKCS-8 conversion recipe, so PKCS-12 is the most portable
  choice. See [SSL / TLS](/documentation/security/ssl-tls/) for the
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

- [SSL / TLS](/documentation/security/ssl-tls/): setup-side
  documentation; this page is the diagnostic complement.
- [Configure SSL/TLS (in Quick start)](/documentation/getting-started/install/#configure-ssltls):
  the recommended-default combination of `sslmode=verify-full`,
  `sslrootcert`, and `channelBinding=require`.
