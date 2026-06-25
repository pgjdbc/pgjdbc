---
title: "Large Objects"
aliases:
    - "/documentation/head/largeobjects.html"
    - "/documentation/80/largeobjects.html"
    - "/documentation/81/largeobjects.html"
    - "/documentation/82/largeobjects.html"
    - "/documentation/83/largeobjects.html"
    - "/documentation/84/largeobjects.html"
    - "/documentation/85/largeobjects.html"
    - "/documentation/90/largeobjects.html"
    - "/documentation/91/largeobjects.html"
    - "/documentation/92/largeobjects.html"
    - "/documentation/93/largeobjects.html"
    - "/documentation/94/largeobjects.html"
---


Large objects are supported in the standard JDBC specification. However, that
interface is limited, and the API provided by PostgreSQL® allows for random
access to the objects contents, as if it was a local file.

The org.postgresql.largeobject package provides to Java the libpq C interface's
large object API. It consists of two classes, `LargeObjectManager` , which deals
with creating, opening and deleting large objects, and `LargeObject` which deals
with an individual object.  For an example usage of this API, please see
[Processing Binary Data in JDBC](/documentation/binary-data/#example71processing-binary-data-in-jdbc).

