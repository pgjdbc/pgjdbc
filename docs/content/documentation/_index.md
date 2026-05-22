---
title: "Documentation"
description: "Connection setup, SQL execution, data-type mapping, PostgreSQL extensions, runtime concerns, troubleshooting recipes, and the full connection-property reference for pgJDBC."
date: 2026-05-20T00:00:00Z
draft: false
last_reviewed: "2026-05-20"
aliases:
    - "/documentation/head/index.html"
    - "/documentation/reading/"   # reading.md removed in Phase 1a
---

pgJDBC is the official JDBC driver for PostgreSQL®: pure Java, JDBC&nbsp;4.2,
production-grade. This documentation is organized by task: start with the
section that matches what you need to do.

If you already know your way around JDBC and just want the knobs, jump
straight to [Connection properties](/documentation/reference/connection-properties/)
for the complete catalog, or to
[Recommended properties](/documentation/connect/recommended-properties/)
for the short list of non-default settings worth turning on in
production.

If you are evaluating pgJDBC for a new project, go to
**[Getting started](/documentation/getting-started/install/)** for the
Maven/Gradle dependency and a TLS-protected first connection. To check
that your Java runtime and PostgreSQL server match a supported driver
line, see [Compatibility](/documentation/getting-started/compatibility/);
for the PostgreSQL server-side checks (TCP listener, `pg_hba.conf`,
database encoding), see [Server preparation](/documentation/getting-started/server-prep/).
