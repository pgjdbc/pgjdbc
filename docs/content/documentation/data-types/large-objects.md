---
title: "Large objects"
description: "Reading and writing PostgreSQL large objects (LO / OID) through the `LargeObjectManager` and `LargeObject` extension classes, which give random access to the object's contents beyond what standard `Blob` / `Clob` allow."
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
access to the object's contents, as if it were a local file.

The org.postgresql.largeobject package provides the libpq C interface's large
object API to Java. It consists of two classes: `LargeObjectManager`, which
deals with creating, opening, and deleting large objects, and `LargeObject`,
which deals with an individual object. For an example usage of this API, see
[Processing Binary Data in JDBC](/documentation/data-types/binary-bytea/#example71processing-binary-data-in-jdbc).
