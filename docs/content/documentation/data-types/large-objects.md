---
title: "Large Objects"
date: 2026-05-13T00:00:00Z
draft: false
weight: 50
toc: true
last_reviewed: "2026-05-13"
aliases:
    - "/documentation/server-prepare/#large-objects/"
---

Large objects are supported in the standard JDBC specification. However, that
interface is limited, and the API provided by PostgreSQL® allows for random
access to the objects contents, as if it was a local file.

The org.postgresql.largeobject package provides to Java the libpq C interface's
large object API. It consists of two classes, `LargeObjectManager` , which deals
with creating, opening and deleting large objects, and `LargeObject` which deals
with an individual object.  For an example usage of this API, please see
[Processing Binary Data in JDBC](/documentation/data-types/binary-bytea/#example71processing-binary-data-in-jdbc).
