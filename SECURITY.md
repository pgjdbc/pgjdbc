# Security Policy

We value backward compatibility — upgrading pgJDBC, including across
minor versions, should not require code or configuration changes. If
you cannot upgrade and need a fix backported, open an issue or write
to the mailing list with the reason you're stuck.

## Supported versions

| Version | We ship |
| ------- | ------- |
| Latest 42.x line | security releases, features, bug fixes |
| Every older 42.x line within the proactive-security window | security releases on the line's own minor |
| 42.2.x (last line supporting Java 6 / 7) | security releases, critical bug fixes |
| Every other version (past the window) | security backports on request |

The **proactive-security window** is five years past the `.0` of the
next minor. While a line is in the window, every CVE gets a dedicated
patch release on that same line — applying the fix never requires
moving to a newer minor. The latest line has no successor yet and
stays in full support indefinitely.

Lines past the window remain eligible for a backport on request —
open an issue with the reason you're stuck. We have rolled patches
as far back as 9.4 when the need was real.

## Reporting a Vulnerability

Please send reports of security issues to pgsql-jdbc-security@lists.postgresql.org.
