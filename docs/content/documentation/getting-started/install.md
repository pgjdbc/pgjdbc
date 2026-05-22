---
title: "Quick start"
date: 2026-05-13T00:00:00Z
draft: false
weight: 1
toc: true
last_reviewed: "2026-05-13"
aliases:
    - "/documentation/quickstart/"
    - "/documentation/install/"
---

Add pgJDBC to your build, open a TLS-protected connection, run a query.
This page is the minimum viable path to a working application; the rest
of the documentation fills in the edges.

## Add the dependency

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
`Class.forName("org.postgresql.Driver")`; that has been unnecessary since
Java&nbsp;6.
{{< /callout >}}

## Open a connection

```java
String url = "jdbc:postgresql://db.example.com:5432/mydb";

Properties props = new Properties();
props.setProperty("user", "alice");
props.setProperty("password", System.getenv("PGPASSWORD"));

// TLS + channel-bound SCRAM. See "Configure SSL/TLS" for the rationale.
props.setProperty("sslmode",        "verify-full");
props.setProperty("sslrootcert",    "/etc/postgresql/ssl/ca.pem");
props.setProperty("channelBinding", "require");

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
ends up in logs and error messages. Use a `Properties` object (as shown
above) or a secrets manager instead.
{{< /callout >}}

## Configure SSL/TLS

For any non-local server, use **both** of these together:

- `sslmode=verify-full`: the driver validates the server's certificate
  chain against `sslrootcert` **and** verifies that the certificate's
  Subject Alternative Name matches the hostname you connected to. Any
  weaker mode (including the default `prefer`) leaves you open to a
  man-in-the-middle attack.
- `channelBinding=require`: binds the SCRAM authentication exchange to
  the established TLS channel. An attacker who terminated and
  re-established the TLS connection to the server cannot replay the SCRAM
  handshake; without channel binding, that attack works even when TLS is
  in use.

Channel binding requires PostgreSQL&nbsp;11 or newer **and** the server
must be configured for SCRAM-SHA-256 (the default since PG&nbsp;14). If
you must run against legacy infrastructure, fall back to
`channelBinding=prefer`, but only after explicitly auditing what the
target server supports.

{{< callout type="warning" title="`sslmode=require` is not enough" >}}
`require` encrypts the connection but does **not** validate the
certificate, so it cannot detect a MITM. It exists only for backward
compatibility with very old setups.
{{< /callout >}}

The SSL-related connection properties:

{{< param-table data="connection-properties" tag="ssl" >}}

## Common follow-ups

- **Connection pooling.** Use HikariCP, Tomcat JDBC, or your container's
  pool. Do not call `DriverManager.getConnection` per request; the
  driver was built for pool usage and the cost of opening a connection
  is significant. See [Connection pooling](/documentation/connect/connection-pooling/)
  for the recipes and sizing guidance.
- **Authentication.** SCRAM-SHA-256 is handled transparently. See
  [Authentication](/documentation/security/authentication/) for the
  full method list, the `requireAuth` allow-list / deny-list,
  channel-binding semantics, and the `AuthenticationPlugin` SPI
  for IAM / Vault / token-based credentials.
- **Performance.** Server-side prepared statements activate after
  `prepareThreshold` executions (default 5). See
  [Server-prepared statements](/documentation/query/prepared-statements/)
  for the full discussion, including binary transfer trade-offs.

The authentication-related connection properties:

{{< param-table data="connection-properties" tag="authentication" >}}

## What's next

- [Connection properties](/documentation/reference/connection-properties/): the full set of
  tunables.
- [SSL / TLS](/documentation/security/ssl-tls/): certificate setup, custom socket
  factories, CRL/OCSP.
- [DataSource and JNDI](/documentation/connect/datasource/): how
  to integrate with HikariCP and friends.
