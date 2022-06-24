---
title: Large Objects
date: 2022-06-19T22:46:55+05:30
draft: false
menu:
  docs:
    parent: "chapter9"
    weight: 3
---

Large objects are supported in the standard JDBC specification. However, that
interface is limited, and the API provided by PostgreSQL™ allows for random
access to the objects contents, as if it was a local file.

The org.postgresql.largeobject package provides to Java the libpq C interface's
large object API. It consists of two classes, `LargeObjectManager`, which deals
with creating, opening and deleting large objects, and `LargeObject` which deals
with an individual object.  For an example usage of this API, please see
[Example 7.1, “Processing Binary Data in JDBC”](binary-data.html#binary-data-example).
