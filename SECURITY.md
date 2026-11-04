# Security Policy

## Supported versions

| Version | Status | What we release |
| ------- | ------ | --------------- |
| Newest 42.x line | Full support | Features, bug fixes, and security fixes |
| Older 42.x lines, for five years after the `.0` release of the next minor line | Security support | Security fixes; other fixes at the maintainers' discretion |
| All other versions | End of life | Backports on request, case by case |

Security support for a line ends five years after the `.0` release of the next minor line. For example, security
support for 42.6.x ends five years after the release of 42.7.0. The newest line has no end date: its five years start
only when the next minor line is released.

While a line has security support, we publish a patch release on that line for every vulnerability that affects it,
including a vulnerability in a library the driver bundles, without waiting for anyone to ask. A fix for 42.6.x ships as
a 42.6.x patch release, so applying it does not require upgrading to 42.7.x. We may also backport a fix for a bug we
judge too serious to leave on such a line.

Upgrading to a newer minor line should not require code or configuration changes. If you cannot upgrade and need a fix
backported to an older line, open an issue or write to the mailing list and explain what keeps you on that line. We
consider each request case by case, and a backport is not guaranteed.

## Reporting a vulnerability

Please send reports of security issues to pgsql-jdbc-security@lists.postgresql.org.
