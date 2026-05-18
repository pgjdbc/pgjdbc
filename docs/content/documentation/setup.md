---
title: "Setting up the Driver (moved)"
date: 2026-05-13T00:00:00Z
draft: false
weight: 1
toc: false
last_reviewed: "2026-05-13"
aliases:
    - "/documentation/head/setup.html"
# Hidden from sidebar / section listings; hub + JS redirector for legacy
# deep links continues to serve at the old URL.
build:
  list: never
---

This page was split:

- **[Quick start](/documentation/install/)** — adding pgJDBC to a Maven
  or Gradle project, opening a TLS-protected connection.
- **[Server preparation](/documentation/getting-started/server-prep/)** —
  configuring `listen_addresses`, `pg_hba.conf`, and database encoding
  before connecting from JDBC.
- **[Building from source](/development/build-from-source/)** — Gradle
  build of the driver itself; only relevant if you are modifying
  pgJDBC.

The historic "Setting up the Class Path" section (CLASSPATH and
`java -cp` patterns) is no longer documented separately — modern
applications declare a Maven or Gradle dependency and have the
classpath handled by the build tool, which is covered in the
[Quick start](/documentation/install/).

{{< legacy-anchors hub="setup" >}}
