---
layout: default_docs
title: Chapter 2. Setting up the JDBC Driver
header: Chapter 2. Setting up the JDBC Driver
resource: /documentation/head/media
previoustitle: Chapter 1. Introduction
previous: intro.html
nexttitle: Setting up the Class Path
next: classpath.html
---
		
**Table of Contents**

* [Getting the Driver](setup.html#build)
* [Setting up the Class Path](classpath.html)
* [Preparing the Database Server for JDBC](prepare.html)
* [Creating a Database](your-database.html)

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
