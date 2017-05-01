---
layout: default_docs
title: Setting up the Class Path
header: Chapter 2. Setting up the JDBC Driver
resource: media
previoustitle: Chapter 2. Setting up the JDBC Driver
previous: setup.html
nexttitle: Preparing the Database Server for JDBC
next: prepare.html
---

To use the driver, the JAR archive named `postgresql.jar` if you built from source,
otherwise it will likely be (named with the following convention: `postgresql-*[server version]*.*[build number]*.jdbc*[JDBC version]*.jar`,
for example `postgresql-8.0-310.jdbc3.jar`) needs to be included in the class path,
either by putting it in the `CLASSPATH` environment variable, or by using flags on
the **java** command line.

For instance, assume we have an application that uses the JDBC driver to access
a database, and that application is installed as `/usr/local/lib/myapp.jar`. The
PostgreSQL™ JDBC driver installed as `/usr/local/pgsql/share/java/postgresql.jar`.
To run the application, we would use:

```bash
export CLASSPATH=/usr/local/lib/myapp.jar:/usr/local/pgsql/share/java/postgresql.jar:.
java MyApp
```

Loading the driver from within the application is covered in [Chapter 3, Initializing the Driver](use.html).
