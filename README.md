<img src="http://developer.postgresql.org/~josh/graphics/logos/elephant-64.png" />
# PostgreSQL JDBC driver

[![Build Status](https://travis-ci.org/pgjdbc/pgjdbc.png)](https://travis-ci.org/pgjdbc/pgjdbc)

This is a simple readme describing how to compile and use the Postgresql JDBC driver.

## Info

This isn't a guide on how to use JDBC - for that refer to [Oracle's website](http://www.oracle.com/technetwork/java/javase/jdbc/) and the [JDBC tutorial](http://docs.oracle.com/javase/tutorial/jdbc/).

For problems with this driver, refer to driver's [home page](http://jdbc.postgresql.org/) and associated [mailing list](http://archives.postgresql.org/pgsql-jdbc/).

## Downloading pre-built drivers

Most people do not need to compile PgJDBC. You can download prebuilt versions of the driver 
from the [Postgresql JDBC site](http://jdbc.postgresql.org/).

## Compiling with Ant on the command line

PgJDBC doesn't natively support compilation from IDEs like Eclipse, NetBeans or
IntelliJ. You should compile with ant on the command line or create your own
IDE project. Tips for use with some IDEs follow below.

Before you can compile the driver you must download the source code from git.
You cannot compile from a jar or a .zip distribution. Run:

    git clone https://github.com/pgjdbc/pgjdbc.git

to download the source code. (You'll need git installed, of course).

To compile you will need to have a Java 5 or newer JDK and will need to have
Ant installed. To obtain Ant go to http://ant.apache.org/index.html and
download the binary. Being pure Java it will run on virtually all Java
platforms. If you have any problems please email the pgsql-jdbc list.

Once you have Ant, simply run ant using 'ant -lib lib' in the top level directory.  
This will compile the correct driver for your JVM, and build a .jar file (Java ARchive)
depending on the version of java and which release you have the jar will be named
postgresql-<major>.<minor>-<release>.jdbc<N>.jar. Where major,minor are the postgreSQL major,minor
version numbers. release is the jdbc release number. N is the version of the JDBC API which 
corresponds to the version of Java used to compile the driver.

*REMEMBER*: Once you have compiled the driver, it will work on ALL platforms
that support that version of the API. You don't need to build it for each
platform.

## Creating a distribution zip

To create a package of the driver jar, sources, and dependencies, run:

    ant dist

## Dependencies

PgJDBC has optional dependencies on other libraries for some features. These
libraries must also be on your classpath if you wish to use those features; if
they aren't, you'll get a PSQLException at runtime when you try to use features
with missing libraries.

Ant will download additional dependencies from the Internet (from Maven
respositories) to satisfy build requirements. Whether or not you intend to use
the optional features the libraries used to implement them *must* be present to
compile the driver.

Currently Waffle-JNA and its dependencies are required for SSPI authentication
support (only supported on a JVM running on Windows). Unless you're on Windows
and using SSPI you can leave them out when you install the driver.

## Installing the driver

To install the driver, the postgresql.jar file has to be in the classpath.
When running standalone Java programs, use the `-cp` command line option,
e.g.

    java -cp postgresql-9.4-1200.jdbc4.jar -jar myprogram.jar

If you're using an application server or servlet container, follow the
instructions for installing JDBC drivers for that server or container.

For users of IDEs like Eclipse, NetBeans, etc, you should simply add the
driver JAR like any other JAR to use it in your program. To use it within
the IDE its self (for database browsing etc) you should follow the IDE
specific documentation on how to install JDBC drivers.

## Using the driver

Java 6 and above do not need any special action to enable the driver - if it's
on the classpath it is automatically detected and loaded by the JVM.

For Java 1.5 and below, use `Class.forName` or a system parameter. See the main
documentation and the JDBC tutorial for details - take a look at "more
information" below.

## JDBC URL syntax

The driver recognises JDBC URLs of the form:

    jdbc:postgresql:database

    jdbc:postgresql://host/database

    jdbc:postgresql://host:port/database

Also, you can supply both username and passwords as arguments, by appending
them to the URL. e.g.:

    jdbc:postgresql:database?user=me
    jdbc:postgresql:database?user=me&password=mypass

Notes:

- If you are connecting to localhost or 127.0.0.1 you can leave it out of the
   URL. i.e.: `jdbc:postgresql://localhost/mydb` can be replaced with
   `jdbc:postgresql:mydb`

- The port defaults to 5432 if it's left out.

There are many options you can pass on the URL to control the driver's behaviour.
See the full JDBC driver documentation for details.

## More information

For more information see the [the PgJDBC driver documentation](http://jdbc.postgresql.org/documentation/documentation.html) and [the JDBC tutorial](http://docs.oracle.com/javase/tutorial/jdbc/).

## Bug reports, patches and development

PgJDBC development is carried out on the [PgJDBC mailing list](http://jdbc.postgresql.org/lists.html) and on [GitHub](https://github.com/pgjdbc/pgjdbc).

### Bug reports

For bug reports please post on pgsql-jdbc or add a GitHub issue. If you include
additional unit tests demonstrating the issue, or self-contained runnable test
case including SQL scripts etc that shows the problem, your report is likely to
get more attention. Make sure you include appropriate details on your
environment, like your JDK version, container/appserver if any, platform,
PostgreSQL version, etc. Err on the site of excess detail if in doubt.

### Bug fixes and new features

If you've developed a patch you want to propose for inclusion in PgJDBC, feel
free to send a GitHub pull request or post the patch on the PgJDBC mailing
list.  Make sure your patch includes additional unit tests demonstrating and
testing any new features. In the case of bug fixes, where possible include a
new unit test that failed before the fix and passes after it.

For information on working with GitHub, see: http://help.github.com/articles/fork-a-repo and http://learn.github.com/p/intro.html.

### Testing

Remember to test proposed PgJDBC patches when running against older PostgreSQL
versions where possible, not just against the PostgreSQL you use yourself.

You also need to test your changes with older JDKs. PgJDBC must support JDK5
("Java 1.5") and newer, which means you can't use annotations, auto-boxing, for
(:), and numerous other features added since JDK 5. Code that's JDBC4 specific
may use JDK6 features, and code that's JDBC4.1 specific may use JDK7 features.
Common code and JDBC3 code needs to stick to Java 1.5.

Two different versions of PgJDBC can be built, the JDBC 3 and JDBC 4 drivers.
The former may be built with JDK 5, while building JDBC4 requires JDK 6 or 7.
The driver to build is auto-selected based on the JDK version used to run the
build. The best way to test a proposed change with both the JDBC3 and JDBC4
drivers is to build and test with both JDK5 and JDK6 or 7.

You can get old JDK versions from the [Oracle Java Archive](http://www.oracle.com/technetwork/java/archive-139210.html).

Typically you can test against an old JDK with:

    export JAVA_HOME=/path/to/jdk_1_5
    export PATH=$JAVA_HOME/bin:$JAVA_HOME/jre/bin:
    ant clean test

For information about the unit tests and how to run them, see
  [org/postgresql/test/README](org/postgresql/test/README)

### Ideas

If you have ideas or proposed changes, please post on the mailing list or
open a detailed, specific GitHub issue.

Think about how the change would affect other users, what side effects it
might have, how practical it is to implement, what implications it would
have for standards compliance and security, etc. Include a detailed use-case
description.

Few of the PgJDBC developers have much spare time, so it's unlikely that your
idea will be picked up and implemented for you. The best way to make sure a
desired feature or improvement happens is to implement it yourself. The PgJDBC
sources are reasonably clear and they're pure Java, so it's sometimes easier
than you might expect.

## Support for IDEs

It's possible to debug and test PgJDBC with various IDEs, not just with ant on
the command line. Projects aren't supplied, but it's easy to prepare them.

### Eclipse

On Eclipse Luna, to import PgJDBC as an Eclipse Java project with full
support for on-demand compile, debugging, etc, you must:

* Perform a git clone of PgJDBC on the command line
* Use Ant to fetch the dependency JARs:

          ant -lib lib snapshot-version maven-dependencies

* In Eclipse, File -> New -> Java Project
* Uncheck "Use default location" and find your git clone of PgJDBC then
  press Next
* Under Source, open "configure inclusion and exclusion filters"
* Add the exclusion filters:
    `org/postgresql/jdbc3/Jdbc3*.java`
    `org/postgresql/jdbc3g/Jdbc3g*.java`
  ... and accept the dialog.

* Under Libraries, choose Add JARs and add everything under `lib`
* Click finish to create the project

Note that unlike a JDBC4 JAR an Eclipse project will not be
auto-detected using service discovery, so you'll have to use an
explicit load:

    Class.forName("org.postgresql.Driver")

Eclipse will interoperate fine with Ant, so you can test and debug
with Eclipse then do dist builds with Ant.

### Other IDEs

Please submit build instructions for your preferred IDE.


### Sponsors

[credativ ltd (Canada)](http://www.credativ.ca) 
 
