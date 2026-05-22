---
title: "Unix sockets"
date: 2026-05-13T00:00:00Z
draft: false
weight: 15
toc: true
last_reviewed: "2026-05-22"
aliases:
    - "/documentation/use/#unix-sockets/"
---

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- socketFactory and socketFactoryArg properties | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 878-897
- socket factory instantiation | pgjdbc/src/main/java/org/postgresql/core/SocketFactoryFactory.java | 32-40
{{< /review >}}

By adding junixsocket you can obtain a socket factory that works with the driver.
Code can be found [here](https://github.com/kohlschutter/junixsocket) and instructions
[here](https://kohlschutter.github.io/junixsocket/dependency.html).

Dependencies for junixsocket are :

```xml
<dependency>
  <groupId>com.kohlschutter.junixsocket</groupId>
  <artifactId>junixsocket-core</artifactId>
  <version>2.10.1</version>
  <type>pom</type>
</dependency>
```

Use `localhost` as the connection host, and add
`?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg=[path-to-the-unix-socket]`
to the connection URL.

For example, if the server listens on `/tmp/.s.PGSQL.5432`, use:

```text
jdbc:postgresql://localhost/postgres?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg=/tmp/.s.PGSQL.5432
```

PostgreSQL normally defaults `unix_socket_directories` to `/tmp`, though that
can be changed at build time or in server configuration. Some packaged
installations use `/var/run/postgresql`, so the socket path may instead be
`/var/run/postgresql/.s.PGSQL.5432`.
