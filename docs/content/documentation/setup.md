---
title: "Setting up the JDBC Driver"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 10
toc: true
aliases:
    - "/documentation/head/setup.html"
    - "/documentation/head/classpath.html"
    - "/documentation/80/setup.html"
    - "/documentation/80/classpath.html"
    - "/documentation/81/setup.html"
    - "/documentation/81/classpath.html"
    - "/documentation/82/setup.html"
    - "/documentation/82/classpath.html"
    - "/documentation/83/setup.html"
    - "/documentation/83/classpath.html"
    - "/documentation/84/setup.html"
    - "/documentation/84/classpath.html"
    - "/documentation/85/setup.html"
    - "/documentation/85/classpath.html"
    - "/documentation/90/setup.html"
    - "/documentation/90/classpath.html"
    - "/documentation/91/setup.html"
    - "/documentation/91/classpath.html"
    - "/documentation/92/setup.html"
    - "/documentation/92/classpath.html"
    - "/documentation/93/setup.html"
    - "/documentation/93/classpath.html"
    - "/documentation/94/setup.html"
    - "/documentation/94/classpath.html"
    - "/documentation/head/load.html"
    - "/documentation/80/load.html"
    - "/documentation/81/load.html"
    - "/documentation/82/load.html"
    - "/documentation/83/load.html"
    - "/documentation/84/load.html"
    - "/documentation/85/load.html"
    - "/documentation/90/load.html"
    - "/documentation/91/load.html"
    - "/documentation/92/load.html"
    - "/documentation/93/load.html"
    - "/documentation/94/load.html"
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

Loading the driver from within the application is covered in [Initializing the Driver](/documentation/setup/#loading-the-driver).

## Importing JDBC

Any source file that uses JDBC needs to import the `java.sql` package, using:

```java
import java.sql.*;
```

> **NOTE**
>
> You should not import the `org.postgresql` package unless you are using PostgreSQL® extensions to the JDBC API.

## Loading the Driver

Applications do not need to explicitly load the `org.postgresql.Driver` class because the pgJDBC driver jar supports the Java Service Provider mechanism. The driver will be loaded by the JVM when the application connects to PostgreSQL® (as long as the driver's jar file is on the classpath).

> **NOTE**
>
> Prior to Java 1.6, the driver had to be loaded by the application: either by calling `Class.forName("org.postgresql.Driver");` or by passing the driver class name as a JVM parameter `java -Djdbc.drivers=org.postgresql.Driver example.ImageViewer`

These older methods of loading the driver are still supported, but they are no longer necessary.
