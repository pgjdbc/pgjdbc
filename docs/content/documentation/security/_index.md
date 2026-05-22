---
title: "Security"
weight: 30
toc: false
last_reviewed: "2026-05-16"
description: "TLS, authentication mechanisms, and the recommended security baseline for production connections."
---

TLS configuration ([SSL / TLS](/documentation/security/ssl-tls/)),
authentication mechanisms (SCRAM-SHA-256, MD5, cleartext password,
the `requireAuth` allow-list and `channelBinding` posture, the
`AuthenticationPlugin` SPI; see [Authentication](/documentation/security/authentication/)),
and Kerberos via JSSE GSSAPI or Windows-native SSPI
([Kerberos, GSSAPI, SSPI](/documentation/security/kerberos-gssapi/)).

For the connection-level security baseline a deployment should adopt,
see [Configure SSL/TLS (in Quick start)](/documentation/getting-started/install/#configure-ssltls).

## See also

The driver's release-disclosure surface lives outside the
documentation tree, on the site's top-level
[Security page](/security/):

- [Release verification](/security/#release-verification): PGP
  signing keys used for Maven Central uploads, with rollover
  fingerprints (the active key changed in 42.7.8).
- [Known vulnerabilities](/security/#security-advisories): the
  driver's CVE / GHSA history with impact, patched-in versions
  and workarounds.
- [Third-party CVE status statements](/security/#third-party-cve-status-statements):
  public statements about high-profile CVEs in adjacent libraries
  (e.g. Log4Shell) and whether pgJDBC is exposed.
