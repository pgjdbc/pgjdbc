---
title: "JDBC URL"
description: "JDBC URL forms the driver accepts (`jdbc:postgresql:...`), host / port / database defaults, IPv6 bracket syntax, percent-encoding of reserved characters, and passing PostgreSQL startup `options` through the URL."
date: 2026-05-13T00:00:00Z
draft: false
weight: 5
toc: true
last_reviewed: "2026-05-13"
aliases:
    - "/documentation/use/#connecting-to-the-database/"
    - "/documentation/head/connect.html"
---

With JDBC, a database is represented by a URL (Uniform Resource Locator). With PostgreSQL®, this takes one of the following forms:

* jdbc:postgresql:database
* jdbc:postgresql:/
* jdbc:postgresql://host/database
* jdbc:postgresql://host/
* jdbc:postgresql://host:port/database
* jdbc:postgresql://host:port/

The parameters have the following meanings:

* **`host`** = The host name of the server. Defaults to `localhost` . To specify an IPv6 address you must enclose the `host` parameter with square brackets, for example: `jdbc:postgresql://[::1]:5740/accounting`

* **`port`** = The port number the server is listening on. Defaults to the PostgreSQL® standard port number (5432).

* **`database`** = The database name. The default is to connect to a database with the same name as the user name used to connect to the server.

To connect, you need to get a `Connection` instance from JDBC. To do this, you use the `DriverManager.getConnection()` method:
 `Connection db = DriverManager.getConnection(url, username, password)`

> **Important**
> 
> Any reserved characters for URLs (for example, /, :, @, (, ), [, ], &, #, =, ?, and space) that appear in any part of the connection URL must be percent encoded. See [RFC 3986](https://www.rfc-editor.org/rfc/rfc3986#section-2) for details.

### Passing server `options` in the URL

The [`options`](/documentation/reference/connection-properties/#prop-options) connection property is forwarded to the backend as the `options` startup parameter, so the value follows the [Client Connection Defaults](https://www.postgresql.org/docs/current/runtime-config-client.html) syntax: multiple `-c name=value` flags separated by spaces, with `\` escaping a literal space and `\\` a literal backslash.

When passed via a `Properties` object the value is taken verbatim:

```java
props.setProperty("options", "-c search_path=test,public,pg_catalog -c statement_timeout=90000");
```

When the same value is embedded in a JDBC URL, every space must be percent-encoded as `%20`, otherwise the URL parser will treat the second `-c` as a separate connection parameter:

```
jdbc:postgresql://localhost:5432/postgres?options=-c%20search_path=test,public,pg_catalog%20-c%20statement_timeout=90000
```
