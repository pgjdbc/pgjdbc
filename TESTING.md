# PostgreSQL/JDBC Test Suite Howto


## 1 - Introduction

The PostgreSQL source tree contains an automated test suite for
the JDBC driver. This document explains how to install,
configure and run this test suite. Furthermore, it offers
guidelines and an example for developers to add new test cases.

## 2 - Installation

Of course, you need to have a [Java 8 JDK](https://www.oracle.com/technetwork/java/javase/downloads/index.html).

You need to install and build the PostgreSQL JDBC driver source
tree. You can download it from https://github.com/pgjdbc/pgjdbc.  See
[README](https://github.com/pgjdbc/pgjdbc) in that project for more information.

In this Howto we'll use `$JDBC_SRC` to refer to the top-level directory
of the JDBC driver source tree.  The test suite is the directory where you cloned the https://github.com/pgjdbc/pgjdbc project from GitHub.

## 3 - Test PostgreSQL Database

The test suite requires a PostgreSQL database to run the tests against
and a user to login as. The tests will create and drop many objects in
this database, so it should not contain production tables to avoid
loss of data. We recommend you assign the following names:

- database: test
- username: test
- password: test

- The test user must have `CREATE` privilege on the test database.
- There must be a superuser named postgres.
- The test user must have the `REPLICATION` attribute.
- The test PostgreSQL instance must be started with `wal_level = logical`.

If you have chosen other names you need to
create a file named `$JDBC_SRC/build.local.properties` and add your
customized values of the properties `database`, `username` and
`password`.

The test suite requires that you have the `contrib/lo` and `contrib/test_decoding` modules
installed.

If you have Docker, you can use `docker-compose` to launch test database (see [docker](docker)):

    cd docker/postgres-server

    # Launch the most recent PostgreSQL database with SSL, XA, and SCRAM
    docker-compose down && docker-compose up

    # Launch PostgreSQL 13, with XA, without SSL
    docker-compose down && SSL=no XA=yes docker-compose up

An alternative way is to use a Vagrant script: [jackdb/pgjdbc-test-vm](https://github.com/jackdb/pgjdbc-test-vm).
Follow the instructions on that project's [README](https://github.com/jackdb/pgjdbc-test-vm) page.

## 4 - Running the test suite

```sh
$ cd $JDBC_SRC
$ ./gradlew test
```

This will run the command line version of JUnit. If you'd like
to see an animated coloured progress bar as the tests are
executed, you may want to use one of the GUI versions of the
test runner. See the JUnit documentation for more information.

If the test suite reports errors or failures that you cannot
explain, please post the relevant parts of the output to the
mailing list pgsql-jdbc@postgresql.org.

## 5 - Extending the test suite with new tests

Most of the tests are written with JUnit4, however it is recommended to create new tests with JUnit5.

If you're not familiar with JUnit, we recommend that you
first read the introductory article [JUnit Test Infected:
Programmers Love Writing Tests](http://junit.sourceforge.net/doc/testinfected/testing.htm).
Before continuing, you should ensure you understand the
following concepts: test suite, test case, test, fixture,
assertion, failure.

The test suite consists of test cases, which consist of tests.
A test case is a collection of tests that test a particular
feature. The test suite is a collection of test cases that
together test the driver - and to an extent the PostgreSQL
backend - as a whole.

If you decide to add a test to an existing test case, all you
need to do is add a method with a name that begins with "test"
and which takes no arguments. JUnit will dynamically find this
method using reflection and run it when it runs the test case.
In your test method you can use the fixture that is setup for it
by the test case.

If you decide to add a new test case, you should do two things:

1. Add a test class. It should
   contain `setUp()` and `tearDown()` methods that create and destroy
   the fixture respectively.
2. Add your test class in `$JDBC_SRC/src/test/java/org/postgresql/test`. This will make the test case
   part of the test suite.

## 6 - Guidelines for developing new tests

Every test should create and drop its own tables. We suggest to
consider database objects (e.g. tables) part of the fixture for
the tests in the test case. The test should also succeed when a
table by the same name already exists in the test database, e.g.
by dropping the table before running the test (ignoring errors).
The recommended pattern for creating and dropping tables can be
found in the example in section 7 below.

Please note that JUnit provides several convenience methods to
check for conditions. See the `Assert` class in the Javadoc
documentation of JUnit, which is installed on your system. For
example, you can compare two integers using
`Assert.assertEquals(int expected, int actual)`. This method
will print both values in case of a failure.

To simply report a failure use `Assert.fail()`.

The JUnit FAQ explains how to test for a thrown exception.

As a rule, the test suite should succeed. Any errors or failures
- which may be caused by bugs in the JDBC driver, the backend or
the test suite - should be fixed ASAP. Don't let a test fail
just to make it clear that something needs to be fixed somewhere.
That's what the TODO lists are for.

Add some comments to your tests to explain to others what it is
you're testing. A long sequence of JDBC method calls and JUnit
assertions can be hard to comprehend.

For example, in the comments you can explain where a certain test
condition originates from. Is it a JDBC requirement, PostgreSQL
behaviour or the intended implementation of a feature?

## 7 - Example

See [BlobTest.java](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/test/java/org/postgresql/test/jdbc2/BlobTest.java) for an example of a unit test that creates  test table, runs a test, and then drops the table. There are other tests in [pgjdbc/src/test/java/org/postgresql](https://github.com/pgjdbc/pgjdbc/tree/master/pgjdbc/src/test/java/org/postgresql) which you can use an examples. Please add your own tests in this location.

## 8 - SSL tests

- requires SSL to be turned on in the database `postgresql.conf ssl=true`
- `pg_hba.conf` requires entries for `hostssl`, and `hostnossl`
- contrib module sslinfo needs to be installed in the databases
- databases `certdb`, `hostdb`, `hostnossldb`, `hostssldb`, and `hostsslcertdb` need to be created


## 9 - Running the JDBC 2 test suite against PostgreSQL

Download the [JDBC test suite](http://java.sun.com/products/jdbc/jdbctestsuite-1_2_1.html).
This is the JDBC 2 test suite that includes J2EE requirements.

1. Configure PostgreSQL so that it accepts TCP/IP connections and
   start the server. Prepare PostgreSQL by creating two users (cts1
   and cts2) and two databases (DB1 and DB2) in the cluster that is
   going to be used for JDBC testing.

2. Download the latest release versions of the J2EE, J2SE, and JDBC
   test suite from Sun's Java site (http://java.sun.com), and install
   according to Sun's documentation.

3. The following environment variables should be set:

       CTS_HOME=<path where JDBC test suite installed (eg: /usr/local/jdbccts)>
       J2EE_HOME=<path where J2EE installed (eg: /usr/local/j2sdkee1.2.1)>
       JAVA_HOME=<path where J2SE installed (eg: /usr/local/jdk1.3.1)>
       NO_JAVATEST=Y
       LOCAL_CLASSES=<path to PostgreSQL JDBC driver jar>

4. In $J2EE_HOME/config/default.properties:

       jdbc.drivers=org.postgresql.Driver
       jdbc.datasources=jdbc/DB1|jdbc:postgresql://localhost:5432/DB1|jdbc/DB2|jdbc:postgresq://localhost:5432/DB2

    Of course, if PostgreSQL is running on a computer different from
    the one running the application server, localhost should be changed
    to the proper host. Also, 5432 should be changed to whatever port
    PostgreSQL is listening on (5432 is the default).

    In $J2EE_HOME/bin/userconfig.sh:

       Add $CTS_HOME/lib/harness.jar, $CTS_HOME/lib/moo.jar,
       $CTS_HOME/lib/util.jar to J2EE_CLASSPATH. Also add the path to
       the PostgreSQL JDBC jar to J2EE_CLASSPATH. Set the JAVA_HOME
       variable to where you installed the J2SE. You should end up with
       something like this:

       CTS_HOME=/home/liams/linux/java/jdbccts
       J2EE_CLASSPATH=/home/liams/work/inst/postgresql-7.1.2/share/java/postgresql.jar:$CTS_HOME/lib/harness.jar:$CTS_HOME/lib/moo.jar:$CTS_HOME/lib/util.jar
       export J2EE_CLASSPATH

       JAVA_HOME=/home/liams/linux/java/jdk1.3.1
       export JAVA_HOME

   In $CTS_HOME/bin/cts.jte:

       webServerHost=localhost
       webServerPort=8000
       servletServerHost=localhost
       servletServerPort=8000

5. Start the application server (j2ee):

        cd $J2EE_HOME
        bin/j2ee -verbose

    The server can be stopped after the tests have finished:

        cd $J2EE_HOME
        bin/j2ee -stop

6. Run the JDBC tests:

        cd $CTS_HOME/tests/jdbc/ee
        make jdbc-tests

At the time of writing of this document, a great number of tests
in this test suite fail.

## 10 - Credits and Feedback

The parts of this document describing the PostgreSQL test suite
were originally written by Rene Pijlman. Liam Stewart contributed
the section on the Sun JDBC 2 test suite.

Please send your questions about the JDBC test suites or suggestions
for improvement to the pgsql-jdbc@postgresql.org mailing list.
