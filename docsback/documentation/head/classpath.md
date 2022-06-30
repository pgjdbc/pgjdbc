---
layout: default_docs
title: Setting up the Class Path
header: Chapter 2. Setting up the JDBC Driver
resource: /documentation/head/media
previoustitle: Chapter 2. Setting up the JDBC Driver
previous: setup.html
nexttitle: Preparing the Database Server for JDBC
next: prepare.html
---

To use the driver, the JAR archive named `postgresql-MM.nn.pp.jar` needs to be included in the class path,
either by putting it in the `CLASSPATH` environment variable, or by using flags on
the **java** command line.

For instance, assume we have an application that uses the JDBC driver to access
a database, and that application is installed as `/usr/local/lib/myapp.jar`. The
PostgreSQL™ JDBC driver installed as `/usr/local/pgsql/share/java/postgresql-MM.nn.pp.jar`.
To run the application, we would use:

```bash
export CLASSPATH=/usr/local/lib/myapp.jar:/usr/local/pgsql/share/java/postgresql-42.2.15.jar:.
java MyApp
```

Current Java applications will likely use maven, gradle or some other package manager.
[Use this to search](https://mvnrepository.com/artifact/org.postgresql/postgresql) for the 
latest jars and how to include them in your project


Loading the driver from within the application is covered in [Chapter 3, Initializing the Driver](use.html).
