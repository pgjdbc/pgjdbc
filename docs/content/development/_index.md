---
title: "Development"
date: 2022-06-20T01:17:51+05:30
draft: false
---

## About the Driver

The PostgreSQL JDBC driver has some unique properties that you should be aware of before starting to develop any code for it. The current development driver supports a number of server versions.  This doesn't mean that every feature must work in every combination, but a reasonable behaviour must be provided for non-supported versions.  While this extra compatibility sounds like a lot of work, the actual  goal is to reduce the amount of work by maintaining only one code base.

## Tools

The following tools are required to build and test the driver:

* [Java 8 Standard Edition Development Kit](https://java.oracle.com) At least JDK 1.8
* [Gradle](https://gradle.org) At least 7.5
* [Git SCM](https://git-scm.com)
* [A PostgreSQL instance](https://www.postgresql.org) to run the tests.

## Build ProcessBuild Process

After retrieving the source from the [git repository](https://github.com/pgjdbc/pgjdbc). Move into the top level `pgjdbc` directory and simply type `./gradlew build -DskipTests` .  This will build the driver and place it into `pgjdbc/build/distributions/postgresql-${version}.jar` .

## Test Suite

To make sure the driver is working as expected there are a set of JUnit tests that should be run.  These require a database to run against that has the plpgsql procedural language installed.  The default parameters for username and database are "test", and for password it's "test". so a sample interaction to set this up would look the following, if you enter "password" when asked for it:

```bash
postgres@host:~$ createuser -d -A test -P
Enter password for user "test":
Enter it again:
CREATE USER

postgres@host:~$ createdb -U test test
CREATE DATABASE

postgres@host:~$ createlang plpgsql test
```

Now we're ready to run the tests, we simply type `./gradlew clean test` , and it should be off and running.  To use non default values to runthe regression tests, you can create a `build.local.properties` in the top level directory. This properties file allows you to setvalues for host, database, user, password, and port with the standardproperties "key = value" usage.  The ability to set the port valuemakes it easy to run the tests against a number of different serverversions on the same machine.
