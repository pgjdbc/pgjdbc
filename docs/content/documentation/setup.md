---
title: "Setting up the JDBC Driver"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 1
toc: true
aliases:
    - "/documentation/head/setup.html"
---

This section describes the steps you need to take before you can write or run programs that use the JDBC interface.

## Getting the Driver

Precompiled versions of the driver can be downloaded from the [PostgreSQL® JDBC web site](https://jdbc.postgresql.org).

Alternatively you can build the driver from source, but you should only need to do this if you are making changes to the source code. To build the JDBC driver, you need gradle and a JDK (currently at least jdk1.8).

If you have several Java compilers installed, maven will use the first one on the path. To use a different one set `JAVA_HOME` to the Java version you wish to use. For example, to use a different JDK than the default, this may work:

```java
 JAVA_HOME=/usr/local/jdk1.8.0_45
 ```

To compile the driver simply run **`gradlew assemble`** or **`gradlew build`** if you want to run the tests in the top level directory.

> **NOTE**
>
> If you want to skip test execution, add the option `-DskipTests`. The compiled driver will be placed in `pgjdbc/build/libs/postgresql-MM.nn.pp.jar`

Where MM is the major version, nn is the minor version and pp is the patch version. Versions for JDBC3 and lower can be found [here](https://repo1.maven.org/maven2/org/postgresql/postgresql/9.2-1003-jdbc3/)

This is a very brief outline of how to build the driver. Much more detailed information can be found on the [github repo](https://github.com/pgjdbc/pgjdbc/blob/master/CONTRIBUTING.md)

Even though the JDBC driver should be built with Gradle, for situations, where use of Gradle is not possible, e.g.,
when building pgJDBC for distributions, the pgJDBC Gradle build provides a convenience source release artifact `*-src.tar.gz` - a Maven-based project.
The Maven-based project contains a version of the JDBC driver with complete functionality, which can be used in production and is still validly buildable
within the Maven build environment.

The Maven-based project is created with **`gradlew -d :postgresql:sourceDistribution -Prelease -Psigning.gpg.enabled=OFF`**.
The produced `*-src.tar.gz` can be then found in `pgjdbc/build/distributions/` directory. JDBC driver can be built from the Maven-based project with **mvn package** or,
when the tests are to be skipped, with **`mvn -DskipTests package`**.

Source files `*-src.tar.gz`'s are released in the [Maven central repository](https://repo1.maven.org/maven2/org/postgresql/postgresql/).

## Setting up the Class Path

To use the driver, the JAR archive named `postgresql-MM.nn.pp.jar` needs to be included in the class path, either by putting it in the `CLASSPATH` environment variable, or by using flags on the **java** command line.

For instance, assume we have an application that uses the JDBC driver to access a database, and that application is installed as `/usr/local/lib/myapp.jar` . The PostgreSQL® JDBC driver installed as `/usr/local/pgsql/share/java/postgresql-MM.nn.pp.jar` .
To run the application, we would use:

```bash
export CLASSPATH=/usr/local/lib/myapp.jar:/usr/local/pgsql/share/java/postgresql-42.5.0.jar:. java MyApp
```

Current Java applications will likely use maven, gradle or some other package manager. [Use this to search](https://mvnrepository.com/artifact/org.postgresql/postgresql) for the latest jars and how to include them in your project

Loading the driver from within the application is covered in [Initializing the Driver](/documentation/use/).

## Preparing the Database Server for JDBC

Out of the box, Java does not support unix sockets so the PostgreSQL® server must be configured to allow TCP/IP connections. Starting with server version 8.0 TCP/IP connections are allowed from `localhost` . To allow connections to other interfaces
than the loopback interface, you must modify the `postgresql.conf` file's `listen_addresses` setting.

Once you have made sure the server is correctly listening for TCP/IP connections the next step is to verify that users are allowed to connect to the server. Client authentication is setup in `pg_hba.conf` . Refer to the main PostgreSQL® [documentation](https://www.postgresql.org/docs/current/auth-pg-hba-conf.html) for details .

## Creating a Database

When creating a database to be accessed via JDBC it is important to select an appropriate encoding for your data. Many other client interfaces do not care what data you send back and forth, and will allow you to do inappropriate things, but Java makes sure that your data is correctly encoded.  Do not use a database that uses the `SQL_ASCII` encoding. This is not a real encoding and you will have problems the moment you store data in it that does not fit in the seven bit ASCII character set. If you do not know what your encoding will be or are otherwise unsure about what you will be storing the `UNICODE` encoding is a reasonable default to use.
