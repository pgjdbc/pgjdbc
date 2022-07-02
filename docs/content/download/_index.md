---
title: "Download"
date: 2022-06-20T01:17:28+05:30
draft: true
---

## About

Binary JAR file downloads of the JDBC driver are available here
and the current version with [Maven Repository](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.postgresql%22%20AND%20a%3A%22postgresql%22).
Because Java is platform neutral, it is a simple process of just
downloading the appropriate JAR file and dropping it into your
classpath.  Source versions are also available here for recent
driver versions.

{% for post in site.categories.new_release limit:1 %}
{% capture current_version %}{{ post.version }}{% endcapture %}
{% endfor %}


## Current Version *{{ current_version }}*

This is the current version of the driver.  Unless you have unusual
requirements (running old applications or JVMs), this is the driver
you should be using.  It supports PostgreSQL 8.2 or newer and
requires Java 6 or newer.  It contains support for SSL and the
javax.sql package.

* If you are using Java 8 or newer then you should use the JDBC 4.2 version.
* If you are using Java 7 then you should use the JDBC 4.1 version.
* If you are using Java 6 then you should use the JDBC 4.0 version.
* If you are using a Java version older than 6 then
you will need to use a JDBC3 version of the driver, which will by
necessity not be current, found in [Other Versions](#others).

[PostgreSQL JDBC 4.2 Driver, {{ current_version }}](/download/postgresql-{{ current_version }}.jar)

[PostgreSQL JDBC 4.1 Driver, {{ current_version }}.jre7](/download/postgresql-{{ current_version }}.jre7.jar)

[PostgreSQL JDBC 4.0 Driver, {{ current_version }}.jre6](/download/postgresql-{{ current_version }}.jre6.jar)
