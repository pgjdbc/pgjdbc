---
title: "Compatibility"
date: 2026-05-15T00:00:00Z
draft: false
weight: 2
toc: true
last_reviewed: "2026-05-16"
description: "Which pgJDBC release line matches your Java runtime and PostgreSQL server, including the .jre6 / .jre7 classifier variants of 42.2.x."
---

Pick a driver version that matches the Java runtime your application
runs on and the PostgreSQL server it connects to.

## Release lines

For most users, install from the **Current** row of the table below.
Consult the other rows only if you are pinned to an older Java or
PostgreSQL version, or are reproducing an old environment:

- Avoid **Superseded** and **EOL** ranges unless you have a concrete
  reason. Start with a row whose Status is `Current` or
  `Security until YYYY-MM`.
- The `.jre6` and `.jre7` classifier builds are archival compatibility
  artifacts for Java 6 / 7; do not pick them for new deployments.

{{< release-history >}}

- **Version range** is the closed interval of patch versions sharing
  the same Java / PostgreSQL floor; a new row opens every time a
  patch raises one of those minimums. The **Released** date is the
  commit date of the last release in that range.
- **Java** and **PostgreSQL** show the lowest supported version with
  a trailing `+`: `9.1+` means "9.1 or newer". Releases past a
  breakpoint intentionally drop support for older servers, not just
  CI coverage.
- **Status** is anchored to the **next** minor line's `.0` commit
  date plus a five-year window: a line keeps receiving proactive
  backports for five years past the moment its successor ships,
  not five years past its own `.0`:
  - **Current**: the latest segment of the latest release line.
    No successor yet, so no end date attached; this is the row to
    choose for a new project.
  - **Superseded by&nbsp;X.Y+**: an earlier segment of an active
    line where a later patch raised the Java or PostgreSQL floor;
    new installs should pick the indicated successor.
  - **Security until&nbsp;YYYY-MM**: inside the support window;
    receives security backports proactively.
  - **EOL since&nbsp;YYYY-MM**: past the support window. Backports
    are still possible on request (see
    [SECURITY.md](https://github.com/pgjdbc/pgjdbc/blob/master/SECURITY.md)),
    but they no longer ship automatically.
- **Notes** carries the active CI matrix on the Current row. Other
  rows leave it blank: the surrounding columns already convey the
  relevant facts (the Java cell on a `.jre6` row says `6+`, the
  version range identifies the build).
- The `.jre6` / `.jre7` rows are
  [Maven classifiers](https://maven.apache.org/pom.html#dependencies)
  of the 42.2.x line, frozen at the last release that ran on Java 6
  and Java 7 respectively. Every other row uses the unclassified
  artifact.

## Filing a bug

Include in any bug report:

- pgJDBC version (e.g., `42.7.11`, or `42.2.29.jre7`);
- Java version (`java -version`);
- PostgreSQL server version (`SELECT version()`).

The first two are part of the version string the driver writes to its
log; the third comes from the server. Together they place your report
on a row of the table above. See
[CONTRIBUTING.md](https://github.com/pgjdbc/pgjdbc/blob/master/CONTRIBUTING.md)
for the full report template.

## What's next

- [Quick start](/documentation/getting-started/install/): Maven /
  Gradle dependency for the version you picked here.
- [Server preparation](/documentation/getting-started/server-prep/):
  TCP listener, `pg_hba.conf`, and database encoding checks before the
  first connection.
- [Connection properties](/documentation/reference/connection-properties/):
  every tunable, with the `introducedIn` badge showing which release
  added it.
