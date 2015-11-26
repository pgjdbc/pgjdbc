<img src="http://developer.postgresql.org/~josh/graphics/logos/elephant-64.png" />
# PostgreSQL JDBC driver

[![Build Status](https://travis-ci.org/pgjdbc/pgjdbc.png)](https://travis-ci.org/pgjdbc/pgjdbc)

This is a simple readme describing how to compile and use the PostgreSQL JDBC driver.

 - [Commit Message Guidelines](#commit)

## Info

This isn't a guide on how to use JDBC - for that refer to [Oracle's website](http://www.oracle.com/technetwork/java/javase/jdbc/) and the [JDBC tutorial](http://docs.oracle.com/javase/tutorial/jdbc/).

For problems with this driver, refer to driver's [home page](http://jdbc.postgresql.org/) and associated [mailing list](http://archives.postgresql.org/pgsql-jdbc/).

## Downloading pre-built drivers

Most people do not need to compile PgJDBC. You can download prebuilt versions of the driver 
from the [Postgresql JDBC site](http://jdbc.postgresql.org/) or using your chosen dependency management tool:

### Maven
```xml
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <version>9.4-1201-jdbc41</version>
</dependency>
```
### Gradle
```
'org.postgresql:postgresql:9.4-1201-jdbc41'
```
### Ivy
```xml
<dependency org="org.postgresql" name="postgresql" rev="9.4-1201-jdbc41"/>
```

## Build requirements

In order to build the source code for PgJDBC you will need the following tools:

- A git client
- A recent version of Maven (3.x)
- A JDK for the JDBC version you'd like to build (JDK6 for JDBC 4, JDK7 for JDBC 4.1 or JDK8 for JDBC 4.2)
- A running PostgreSQL instance

## Checking out the source code

The PgJDBC project uses git for version control. You can check out the current code by running:

    git clone https://github.com/pgjdbc/pgjdbc.git
    
This will create a pgjdbc directory containing the checked-out source code.

## Compiling with Maven on the command line

After checking out the code you can compile and test the PgJDBC driver by running the following
on a command line:

    mvn clean package

PgJDBC doesn't natively support compilation from IDEs like Eclipse, NetBeans or
IntelliJ. However you can use the tools Maven support from within the IDE if you wish. 
  
After running the build , and build a .jar file (Java ARchive)
depending on the version of java and which release you have the jar will be named
postgresql-<major>.<minor>-<release>.jdbc<N>.jar. Where major,minor are the postgreSQL major,minor
version numbers. release is the jdbc release number. N is the version of the JDBC API which 
corresponds to the version of Java used to compile the driver.

The target directory will contain a number of built artifacts including archives. These
contain a packaged version of the driver jar, source code, documentation and runtime dependencies.

*REMEMBER*: Once you have compiled the driver, it will work on ALL platforms
that support that version of the API. You don't need to build it for each
platform.

## Dependencies

PgJDBC has optional dependencies on other libraries for some features. These
libraries must also be on your classpath if you wish to use those features; if
they aren't, you'll get a PSQLException at runtime when you try to use features
with missing libraries.

Maven will download additional dependencies from the Internet (from Maven
repositories) to satisfy build requirements. Whether or not you intend to use
the optional features the libraries used to implement them they *must* be present to
compile the driver.

Currently Waffle-JNA and its dependencies are required for SSPI authentication
support (only supported on a JVM running on Windows). Unless you're on Windows
and using SSPI you can leave them out when you install the driver.

## Installing the driver

To install the driver, the postgresql jar file has to be in the classpath.
When running standalone Java programs, use the `-cp` command line option,
e.g.

    java -cp postgresql-9.4-1201.jdbc4.jar -jar myprogram.jar

If you're using an application server or servlet container, follow the
instructions for installing JDBC drivers for that server or container.

For users of IDEs like Eclipse, NetBeans, etc, you should simply add the
driver JAR like any other JAR to use it in your program. To use it within
the IDE itself (for database browsing etc) you should follow the IDE
specific documentation on how to install JDBC drivers.

## Using the driver

Java 6 and above do not need any special action to enable the driver - if it's
on the classpath it is automatically detected and loaded by the JVM.

## JDBC URL syntax

The driver recognises JDBC URLs of the form:

    jdbc:postgresql/

    jdbc:postgresql:database

    jdbc:postgresql://host/database

    jdbc:postgresql://host/

    jdbc:postgresql://host:port/database

    jdbc:postgresql://host:port/

When the parameter `database` is omitted it defaults to the username. 

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

PgJDBC development is carried out on the [PgJDBC mailing list](https://jdbc.postgresql.org/community/mailinglist.html) and on [GitHub](https://github.com/pgjdbc/pgjdbc).

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

You also need to test your changes with older JDKs. PgJDBC must support JDK6
("Java 1.6") and newer. Code that is specific to a particular spec version
may use features from that version of the language. i.e. JDBC4.1 specific 
may use JDK7 features, JDBC4.2 may use JDK8 features.
Common code and JDBC4 code needs to be compiled using JDK6.

Three different versions of PgJDBC can be built, the JDBC 4, 4.1 and 4.2 drivers.
These require JDK6, JDK7 and JDK8 respectively.
The driver to build is auto-selected based on the JDK version used to run the
build. The best way to test a proposed change is to build and test with JDK6, 7 and 8.

You can get old JDK versions from the [Oracle Java Archive](http://www.oracle.com/technetwork/java/archive-139210.html).

Typically you can test against an old JDK with:

    export JAVA_HOME=/path/to/jdk_1_6
    export PATH=$JAVA_HOME/bin:$JAVA_HOME/jre/bin:
    mvn clean test

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

It's possible to debug and test PgJDBC with various IDEs, not just with mvn on
the command line. Projects aren't supplied, but it's easy to prepare them.

### Eclipse

On Eclipse Luna, to import PgJDBC as an Eclipse Java project with full
support for on-demand compile, debugging, etc, you must:

* Perform a git clone of PgJDBC on the command line
* Use Maven to fetch the dependency JARs:

          mvn clean compile

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

Eclipse will interoperate fine with Maven, so you can test and debug
with Eclipse then do dist builds with Maven.

### Other IDEs

Please submit build instructions for your preferred IDE.

## <a name="commit"></a> Git Commit Guidelines

We have very precise rules over how our git commit messages can be formatted.  This leads to **more
readable messages** that are easy to follow when looking through the **project history**.  But also,
we use the git commit messages to **generate the change log**.

### Commit Message Format
Each commit message consists of a **header**, a **body** and a **footer**.  The header has a special
format that includes a **type**, and a **subject**:

```
<type>: <subject>
<BLANK LINE>
<body>
<BLANK LINE>
<footer>
```

Any line of the commit message cannot be longer 100 characters! This allows the message to be easier
to read on github as well as in various git tools.

### Type
Must be one of the following:

* **feat**: A new feature
* **fix**: A bug fix
* **docs**: Documentation only changes
* **style**: Changes that do not affect the meaning of the code (white-space, formatting, missing
  semi-colons, etc)
* **refactor**: A code change that neither fixes a bug or adds a feature
* **perf**: A code change that improves performance
* **test**: Adding missing tests
* **chore**: Changes to the build process or auxiliary tools and libraries such as documentation
  generation

### Subject
The subject contains succinct description of the change:

* use the imperative, present tense: "change" not "changed" nor "changes"
* don't capitalize first letter
* no dot (.) at the end

###Body
Just as in the **subject**, use the imperative, present tense: "change" not "changed" nor "changes"
The body should include the motivation for the change and contrast this with previous behavior.

###Footer
The footer should contain any information about **Breaking Changes** and is also the place to
reference GitHub issues that this commit **Closes**.


### Sponsors

[PostgreSQL International](http://www.postgresintl.com) 
 
