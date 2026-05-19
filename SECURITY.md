# Security Policy

We value backward compatibility — upgrading pgJDBC should not require
code or configuration changes. If you cannot upgrade and need a fix
backported, please open an issue or write to the mailing list with the
reason you're stuck.

## Supported versions

| Version | Supported |
| ------- | --------- |
| latest 42.x line | security fixes, features, bug fixes |
| every older 42.x line within the proactive-security window | security fixes |
| 42.2.x (latest line supporting Java 6 / 7) | security fixes, critical bug fixes |
| every other version (past the window) | security fixes on request |

The **proactive-security window** is five years past the moment a
release line is superseded — i.e., a line gets security backports
until five years after the `.0` of the *next* minor ships. The
[Compatibility page](https://pgjdbc.github.io/documentation/getting-started/compatibility/)
shows the resolved date for every release line; rows still inside
the window are labelled `Security until YYYY-MM`.

The latest line has no successor yet and stays in full support
indefinitely. The intent is to separate "we are eager fixing bugs"
from "we can roll security releases": every line within the window
ships fixes for new CVEs; everything else stays eligible on request
— we have rolled security backports as far back as 9.4 when the need
was real.

## Reporting a Vulnerability

Please send reports of security issues to pgsql-jdbc-security@lists.postgresql.org.
