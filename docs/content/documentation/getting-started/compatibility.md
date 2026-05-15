---
title: "Compatibility"
date: 2026-05-15T00:00:00Z
draft: false
weight: 2
toc: true
last_reviewed: "2026-05-15"
description: "Which pgJDBC release line matches your Java runtime and PostgreSQL server, including the .jre6 / .jre7 classifier variants of 42.2.x."
---

Pick a driver version that matches the Java runtime your application
runs on and the PostgreSQL server it connects to.

## Release lines

The table below is generated from `git` refs (release tags and
branches) merged with hand-curated minimum-Java / minimum-PostgreSQL
breakpoints. `First release` and `Last release` are the commit dates of
the line's `.0` tag and its latest tag respectively — they reflect what
has actually shipped, not what is planned for the next release.

{{< release-history >}}

`Min Java` is the lowest Java major version the line's bytecode runs
on. `Min PostgreSQL` is the lowest server version the line is tested
against — older servers may work but are not part of regression
coverage. The `.jre6` and `.jre7` rows are
[Maven classifiers](https://maven.apache.org/pom.html#dependencies) of
the 42.2.x line for runtimes older than Java 8; everything else uses
the unclassified artefact.

## Support policy

The driver project commits to:

- security fixes, features, and bug fixes on the **latest** 42.x line;
- security fixes (and only those) on the `42.2.x` line, which is the
  last branch supporting Java 6 and Java 7;
- security fixes on older lines **upon request**, when the reporter
  explains why an upgrade is not possible.

The authoritative wording lives in [SECURITY.md](https://github.com/pgjdbc/pgjdbc/blob/master/SECURITY.md).
Report security issues to
[pgsql-jdbc-security@lists.postgresql.org](mailto:pgsql-jdbc-security@lists.postgresql.org)
rather than the public issue tracker.

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

- [Quick Start](/documentation/getting-started/install/) — Maven /
  Gradle dependency for the version you picked here.
- [Server preparation](/documentation/getting-started/server-prep/) —
  TCP listener, `pg_hba.conf`, and database encoding checks before the
  first connection.
- [Connection Properties reference](/documentation/reference/connection-properties/) —
  every tunable, with the `introducedIn` badge showing which release
  added it.
