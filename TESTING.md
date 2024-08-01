# PostgreSQL/JDBC Test Suite Howto


## 1 - Introduction

The PostgreSQL source tree contains an automated test suite for
the JDBC driver. This document explains how to install,
configure and run this test suite. Furthermore, it offers
guidelines and an example for developers to add new test cases.

## 2 - Installation

Java 17+ is required to build pgjdbc. We recommend installing [Java 17](https://javaalmanac.io/jdk/17/).

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

    cd docker && bin/postgresql-server

    Helper script to start a postgres container for testing the PGJDBC driver.

    This is the same container used by the automated CI platform and can be used
    to reproduce CI errors locally. It respects all the same environment variables
    used by the CI matrix:

    PGV   = "9.2" | "9.6" | ... "13" ...   - PostgreSQL server version (defaults to latest)
    SSL   = "yes" | "no"                   - Whether to enable SSL
    XA    = "yes" | "no"                   - Whether to enable XA for prepared transactions
    SCRAM = "yes" | "no"                   - Whether to enable SCRAM authentication
    TZ    = "Etc/UTC" | ...                - Override server timezone (default Etc/UTC)
    CREATE_REPLICAS = "yes" | "no"         - Whether to create two streaming replicas (defaults to off)

    The container is started in the foreground. It will remain running until it
    is killed via Ctrl-C.

    To start the default (latest) version:

    docker/bin/postgres-server

    To start a v9.2 server without SSL:

    PGV=9.2 SSL=off docker/bin/postgres-server

    To start a v10 server with SCRAM disabled:

     PGV=10 SCRAM=no docker/bin/postgres-server

    To start a v11 server with a custom timezone:

    PGV=11 TZ=Americas/New_York docker/bin/postgres-server

    To start a v13 server with the defaults (SSL + XA + SCRAM):

    PGV=13 docker/bin/postgres-server

    To start the default (latest) version with read only replicas:

    CREATE_REPLICAS=on docker/bin/postgres-server

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

You could specify Java version for testing purposes with `-PjdkTestVersion=8` build parameter:

```sh
./gradlew -PjdkTestVersion=8 test
```

You could launch `./gradlew parameters` to get the list of available parameters.

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


## 10 - Credits and Feedback

The parts of this document describing the PostgreSQL test suite
were originally written by Rene Pijlman. Liam Stewart contributed
the section on the Sun JDBC 2 test suite.

Please send your questions about the JDBC test suites or suggestions
for improvement to the pgsql-jdbc@postgresql.org mailing list.
