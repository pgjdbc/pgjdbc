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

Precompiled versions of the driver can be downloaded from the [PostgreSQL™ JDBC web site](http://jdbc.postgresql.org).
   
Alternatively you can build the driver from source, but you should only need to
do this if you are making changes to the source code. To build the JDBC driver,
you need Ant 1.5 or higher and a JDK. Ant is a special tool for building Java-based
packages. It can be downloaded from the [Ant web site](http://ant.apache.org/index.html).
    
If you have several Java compilers installed, it depends on the Ant configuration
which one gets used. Precompiled Ant distributions are typically set up to read
a file `.antrc` in the current user's home directory for configuration. For example,
to use a different JDK than the default, this may work:

`JAVA_HOME=/usr/local/jdk1.6.0_07`  
`JAVACMD=$JAVA_HOME/bin/java`

To compile the driver simply run **ant** in the top level directory. The compiled
driver will be placed in `jars/postgresql.jar`. The resulting driver will be built
for the version of Java you are running. If you build with a 1.4 or 1.5 JDK you
will build a version that supports the JDBC 3 specification and if you build with
a 1.6 or higher JDK you will build a version that supports the JDBC 4 specification.