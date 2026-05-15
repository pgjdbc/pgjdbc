---
title: "Unix sockets"
date: 2026-05-13T00:00:00Z
draft: false
weight: 15
toc: true
last_reviewed: "2026-05-13"
aliases:
    - "/documentation/use/#unix-sockets/"
---

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
</content>
</invoke>