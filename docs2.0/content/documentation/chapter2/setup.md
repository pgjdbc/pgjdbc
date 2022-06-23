---
title: Setting up the JDBC Driver
date: 2022-06-19T22:46:55+05:30
draft: true
weight: 1
---

This section describes the steps you need to take before you can write or run
programs that use the JDBC interface.

<a name="build"></a>
# Getting the Driver

Precompiled versions of the driver can be downloaded from the [PostgreSQL™ JDBC web site](https://jdbc.postgresql.org).

Alternatively you can build the driver from source, but you should only need to
do this if you are making changes to the source code. To build the JDBC driver,
you need gradle and a JDK (currently at least jdk1.8) .

If you have several Java compilers installed, maven will use the first one on the path.
To use a different one set JAVA_HOME to the Java version you wish to use For example,
to use a different JDK than the default, this may work:

`JAVA_HOME=/usr/local/jdk1.8.0_45`

To compile the driver simply run **gradlew assemble** or **gradlew build** if you want to run the tests
in the top level directory.
Note: if you want to skip test execution, add the option -DskipTests.
The compiled driver will be placed in `pgjdbc/build/libs/postgresql-MM.nn.pp.jar`
Where MM is the major version, nn is the minor version and pp is the patch version.
Versions for JDBC3 and lower can be found [here](https://jdbc.postgresql.org/download.html#others)
This is a very brief outline of how to build the driver. Much more detailed information can be
found on the [github repo](https://github.com/pgjdbc/pgjdbc/blob/master/CONTRIBUTING.md)

Even though the JDBC driver should be built with Gradle, for situations, where use of Gradle is not possible,
e.g., when building pgjdbc for distributions, the pgjdbc Gradle build provides a convenience
source release artifact `*-src.tar.gz` - a Maven based project.
The Maven based project contains a version of the JDBC driver with complete functionality,
which can be used in production and is still validly buildable within the Maven build environment.
The Maven-based project is created with **gradlew -d :postgresql:sourceDistribution -Prelease**.
The produced `*-src.tar.gz` can be then found in `pgjdbc/build/distributions/` directory.
JDBC driver can be built from the Maven-based project with **mvn package** or,
when the tests are to be skipped, with **mvn -DskipTests package**.
Prefactored `*-src.tar.gz`s are released in the [Maven central repository](https://repo1.maven.org/maven2/org/postgresql/postgresql/).
