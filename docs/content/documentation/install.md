---
title: "Quick Start"
date: 2026-05-12T00:00:00Z
draft: false
weight: 1
toc: true
last_reviewed: "2026-05-12"
aliases:
    - "/documentation/quickstart/"
---

Add pgJDBC to your build, open a connection, run a query. This page covers
the 90% case; once you have something working, the rest of the documentation
fills in the edges.

## 1. Add the dependency

{{< code-tabs >}}
{{< code-tab name="Maven" lang="xml" >}}
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.11</version>
</dependency>
{{< /code-tab >}}
{{< code-tab name="Gradle (Kotlin)" lang="kotlin" >}}
implementation("org.postgresql:postgresql:42.7.11")
{{< /code-tab >}}
{{< code-tab name="Gradle (Groovy)" lang="groovy" >}}
implementation 'org.postgresql:postgresql:42.7.11'
{{< /code-tab >}}
{{< code-tab name="SBT" lang="scala" >}}
libraryDependencies += "org.postgresql" % "postgresql" % "42.7.11"
{{< /code-tab >}}
{{< /code-tabs >}}

{{< callout type="note" >}}
Driver loading is automatic via the Java `ServiceLoader`. You do not need
`Class.forName("org.postgresql.Driver")` — that has been unnecessary since Java&nbsp;6.
{{< /callout >}}

## 2. Open a connection

```java
String url = "jdbc:postgresql://localhost:5432/mydb";
Properties props = new Properties();
props.setProperty("user", "alice");
props.setProperty("password", System.getenv("PGPASSWORD"));
props.setProperty("sslmode", "verify-full");

try (Connection conn = DriverManager.getConnection(url, props);
     PreparedStatement ps = conn.prepareStatement(
         "SELECT name FROM users WHERE id = ?")) {
    ps.setLong(1, 42);
    try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
            System.out.println(rs.getString("name"));
        }
    }
}
```

{{< callout type="security" title="Don't put secrets in the URL" >}}
Reserved URL characters in passwords must be percent-encoded, and the URL
ends up in logs and error messages. Use a `Properties` object or a
secrets manager instead.
{{< /callout >}}

## 3. Configure SSL/TLS

For any non-local server, set `sslmode` to `verify-full` and point the
driver at the server's CA certificate via `sslrootcert`. Without
`verify-full`, the driver does not validate the server's hostname — see
[Using SSL](/documentation/ssl/) for the full discussion.

The SSL-related connection properties relevant to this page:

{{< param-table data="connection-properties" tag="ssl" >}}

## 4. Common follow-ups

- **Connection pooling.** Use HikariCP or Tomcat JDBC; do not call
  `DriverManager.getConnection` per request. The driver was built for
  pool usage.
- **Authentication.** SCRAM-SHA-256 is the default on PostgreSQL 14+;
  pgjdbc handles it transparently. For Kerberos/GSSAPI see [Authentication](/documentation/use/#connection-parameters).
- **Performance.** Server-side prepared statements activate after
  `prepareThreshold` executions (default 5); see
  [Server Prepared Statements](/documentation/server-prepare/).

## Shortcode reference (Phase 0 exemplar)

This section is for documentation contributors and is not part of the
user-facing Quick Start. It demonstrates the available shortcodes; once
authors are familiar with them, this section can be removed.

### Inline badges

A new feature: {{< since "42.7.4" >}}. A retired one:
{{< deprecated since="42.6.0" use="primary instead of master" >}}.

### Callouts

{{< callout type="tip" >}}
`tip` calls out a small but useful suggestion.
{{< /callout >}}

{{< callout type="warning" >}}
`warning` flags a foot-gun or a behavior change.
{{< /callout >}}

{{< callout type="deprecated" title="Old API" >}}
`deprecated` marks a paragraph about something being phased out.
{{< /callout >}}

### Property reference (full table, with filter)

{{< param-table data="connection-properties" >}}
