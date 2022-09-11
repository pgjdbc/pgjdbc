# Security Policy

1) We value backward compatibility, so we expect that upgrading pgjdbc versions should not involve code changes nor it should it require configuration changes.
2) In the event that you are unable to upgrade, you might expect or ask for security fixes for the past versions as well. However, please raise the reason you unable to upgrade in the mailing list or in the issues

| Version  | Supported          |
| -------- | ------------------ |
| latest 42.x | security fixes, features, bug fixes |
| 42.2.x   | (the latest branch that supports Java 6, and 7): security fixes, critical bug fixes only. |
| all the other versions | security fixes (upon request) |

The intention is to separate «we are eager fixing bugs» from «we can roll security releases».
It would not be impossible for us to roll security fixes even for 9.4 versions if necessary.

## Reporting a Vulnerability

Please send reports of security issues to pgsql-jdbc-security@lists.postgresql.org
