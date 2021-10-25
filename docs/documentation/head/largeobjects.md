---
layout: default_docs
title: Large Objects
header: Chapter 9. PostgreSQL™ Extensions to the JDBC API
resource: /documentation/head/media
previoustitle: Geometric Data Types
previous: geometric.html
nexttitle: Listen / Notify
next: listennotify.html
---

Large objects are supported in the standard JDBC specification. However, that
interface is limited, and the API provided by PostgreSQL™ allows for random
access to the objects contents, as if it was a local file.

The org.postgresql.largeobject package provides to Java the libpq C interface's
large object API. It consists of two classes, `LargeObjectManager`, which deals
with creating, opening and deleting large objects, and `LargeObject` which deals
with an individual object.  For an example usage of this API, please see
[Example 7.1, “Processing Binary Data in JDBC”](binary-data.html#binary-data-example).
