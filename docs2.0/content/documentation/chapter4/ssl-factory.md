---
title: Custom SSLSocketFactory
date: 2022-06-19T22:46:55+05:30
draft: false
menu:
  docs:
    parent: "chapter4"
    weight: 3
---

PostgreSQL™ provides a way for developers to customize how a SSL connection is
established. This may be used to provide a custom certificate source or other
extensions by allowing the developer to create their own `SSLContext` instance.
The connection URL parameters `sslfactory` allow the user to specify which custom
class to use for creating the `SSLSocketFactory`. The class name specified by `sslfactory`
must extend ` javax.net.ssl.SSLSocketFactory` and be available to the driver's classloader.

This class must have a zero argument constructor or a single argument constructor preferentially taking
a `Properties` argument. There is a simple `org.postgresql.ssl.DefaultJavaSSLFactory` provided which uses the
default java SSLFactory.

Information on how to actually implement such a class is beyond the scope of this
documentation. Places to look for help are the [JSSE Reference Guide](https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html)
and the source to the `NonValidatingFactory` provided by the JDBC driver.
