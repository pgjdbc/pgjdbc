---
title: "Security"
weight: 30
toc: false
last_reviewed: "2026-05-13"
description: "TLS, authentication mechanisms, and the recommended security baseline for production connections."
---

TLS configuration, authentication mechanisms (SCRAM, channel binding,
`requireAuth`, `AuthenticationPlugin`), and Kerberos / GSSAPI / SSPI
integration.

For the connection-level security baseline a deployment should adopt,
see [Quick Start § 3](/documentation/getting-started/install/#3-configure-ssltls).

## See also

The driver's release-disclosure surface lives outside the
documentation tree, on the site's top-level
[Security page](/security/):

- [Release verification](/security/#release-verification) — PGP
  signing keys used for Maven Central uploads, with rollover
  fingerprints (the active key changed in 42.7.8).
- [Known vulnerabilities](/security/#security-advisories) — the
  driver's CVE / GHSA history with impact, patched-in versions
  and workarounds.
