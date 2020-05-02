---
layout: default_docs
title: Chapter 2. Setting up the JDBC Driver
header: Chapter 2. Setting up the JDBC Driver
resource: media
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
you need maven and a JDK (currently at least jdk1.6) . Maven is a tool for building Java-based
projects. It can be downloaded from the [maven web site](https://maven.apache.org/).
    
If you have several Java compilers installed, maven will use the first one on the path. 
To use a different one set JAVA_HOME to the Java version you wish to use For example,
to use a different JDK than the default, this may work:

`JAVA_HOME=/usr/local/jdk1.8.0_45`  

To compile the driver simply run **maven package** in the top level directory. 
Note: if you want to skip test execution, issue mvn package -DskipTests.
The compiled driver will be placed in `pgjdbc/target/postgresql-MM.nn.pp.jar`. Where MM is the major version, nn is the 
minor version and pp is the patch version. The resulting driver will be built for the version 
of Java you are running. If you build with JDK 1.6 then the driver will support JDBC 4, JDK 1.7 
supports 4.1 and JDK 1.8 or greater supports 4.2. 
Versions for JDBC3 and lower can be found [here](https://jdbc.postgresql.org/download.html#others)
This is a very brief outline of how to build the driver. Much more detailed information can be 
found on the [github repo](https://github.com/pgjdbc/pgjdbc/blob/master/CONTRIBUTING.md)
