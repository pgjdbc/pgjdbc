---
title: "Driver extensions API"
date: 2026-05-13T00:00:00Z
draft: false
weight: 1
toc: true
last_reviewed: "2026-05-13"
aliases:
    - "/documentation/server-prepare/#accessing-the-extensions/"
---

PostgreSQL® is an extensible database system. You can add your own functions to the server, which can then be called from queries, or even add your own data types. As these are facilities unique to PostgreSQL®, we support them from Java, with a set of extension APIs. Some features within the core of the standard driver actually use these extensions to implement Large Objects, etc.

To access some of the extensions, you need to use some extra methods in the `org.postgresql.PGConnection` class. In this case, you would need to cast the return value of `Driver.getConnection()` . For example:

```java
Connection db = Driver.getConnection(url, username, password);
// ...
// later on
Fastpath fp = db.unwrap(org.postgresql.PGConnection.class).getFastpathAPI();
```
