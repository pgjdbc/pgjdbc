---
title: "Connecting to the Database"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 30
toc: true
aliases:
    - "/documentation/use/"
    - "/documentation/head/use.html"
    - "/documentation/head/connect.html"
    - "/documentation/80/use.html"
    - "/documentation/80/connect.html"
    - "/documentation/81/use.html"
    - "/documentation/81/connect.html"
    - "/documentation/82/use.html"
    - "/documentation/82/connect.html"
    - "/documentation/83/use.html"
    - "/documentation/83/connect.html"
    - "/documentation/84/use.html"
    - "/documentation/84/connect.html"
    - "/documentation/85/use.html"
    - "/documentation/85/connect.html"
    - "/documentation/90/use.html"
    - "/documentation/90/connect.html"
    - "/documentation/91/use.html"
    - "/documentation/91/connect.html"
    - "/documentation/92/use.html"
    - "/documentation/92/connect.html"
    - "/documentation/93/use.html"
    - "/documentation/93/connect.html"
    - "/documentation/94/use.html"
    - "/documentation/94/connect.html"
---

With JDBC, a database is represented by a URL (Uniform Resource Locator). With PostgreSQL®, this takes one of the following forms:

* jdbc:postgresql:database
* jdbc:postgresql://
* jdbc:postgresql://host/database
* jdbc:postgresql://host/
* jdbc:postgresql://host:port/database
* jdbc:postgresql://host:port/

The parameters have the following meanings:

* **`host`** = The host name of the server. Defaults to `localhost` . To specify an IPv6 address your must enclose the `host` parameter with square brackets, for example: `jdbc:postgresql://[::1]:5740/accounting`

* **`port`** = The port number the server is listening on. Defaults to the PostgreSQL® standard port number (5432).

* **`database`** = The database name. The default is to connect to a database with the same name as the user name used to connect to the server.

To connect, you need to get a `Connection` instance from JDBC. To do this, you use the `DriverManager.getConnection()` method:
 `Connection db = DriverManager.getConnection(url, username, password)`

> **Important**
> 
> Any reserved characters for URLs (for example, /, :, @, (, ), [, ], &, #, =, ?, and space) that appear in any part of the connection URL must be percent encoded. See [RFC 3986](https://www.rfc-editor.org/rfc/rfc3986#section-2) for details.

### Unix sockets

By adding junixsocket you can obtain a socket factory that works with the driver.
Code can be found [here](https://github.com/kohlschutter/junixsocket) and instructions 
[here](https://kohlschutter.github.io/junixsocket/dependency.html)

Dependencies for junixsocket are :

```xml
<dependency>
  <groupId>com.kohlschutter.junixsocket</groupId>
  <artifactId>junixsocket-core</artifactId>
  <version>2.5.1</version>
</dependency>
```

Simply add  `?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg=[path-to-the-unix-socket]` 
to the connection URL.

For many distros the default path is /var/run/postgresql/.s.PGSQL.5432
