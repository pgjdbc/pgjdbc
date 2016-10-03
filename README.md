<img src="http://developer.postgresql.org/~josh/graphics/logos/elephant-64.png" />
# PostgreSQL JDBC driver

[![Build Status](https://travis-ci.org/pgjdbc/pgjdbc.svg?branch=master)](https://travis-ci.org/pgjdbc/pgjdbc)
[![codecov.io](http://codecov.io/github/pgjdbc/pgjdbc/coverage.svg?branch=master)](http://codecov.io/github/pgjdbc/pgjdbc?branch=master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.postgresql/postgresql/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.postgresql/postgresql)
[![Javadocs](http://javadoc.io/badge/org.postgresql/postgresql.svg)](http://javadoc.io/doc/org.postgresql/postgresql)
[![Join the chat at https://gitter.im/pgjdbc/pgjdbc](https://badges.gitter.im/pgjdbc/pgjdbc.svg)](https://gitter.im/pgjdbc/pgjdbc?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

This is a simple readme describing how to compile and use the PostgreSQL JDBC driver.

 - [Commit Message Guidelines](CONTRIBUTING.md#commit)

## Info

This isn't a guide on how to use JDBC - for that refer to [Oracle's website](http://www.oracle.com/technetwork/java/javase/jdbc/) and the [JDBC tutorial](http://docs.oracle.com/javase/tutorial/jdbc/).

For problems with this driver, refer to driver's [home page](https://jdbc.postgresql.org/) and associated [mailing list](https://archives.postgresql.org/pgsql-jdbc/).

## Downloading pre-built drivers

Most people do not need to compile PgJDBC. You can download prebuilt versions of the driver 
from the [PostgreSQL JDBC site](https://jdbc.postgresql.org/) or using your chosen dependency management tool:

## Changelog

Notable changes for 9.4.1211 (2016-09-18):
* json type is returned as PGObject like in pre-9.4.1210 (fixed regression of 9.4.1210)
* 'current transaction is aborted' exception includes the original exception via caused-by chain

Notable changes for 9.4.1210 (2016-09-07):
* BUG: json datatype is returned as java.lang.String object, not as PGObject (fixed in 9.4.1211)
* Better support for RETURN_GENERATED_KEYS, statements with RETURNING clause
* Avoid user-visible prepared-statement errors if client uses DEALLOCATE/DISCARD statements (invalidate cache when those statements detected)
* Avoid user-visible prepared-statement errors if client changes search_path (invalidate cache when set search_path detected)
* Support comments when replacing {fn ...} JDBC syntax
* Support for Types.REF_CURSOR

Notable changes for 9.4.1209 (2016-07-15):
* Many improvements to `insert into .. values(?,?)` => `insert .. values(?,?), (?,?)...` rewriter. Give it a try by using `reWriteBatchedInserts=true` connection property. 2-3x improvements for insert batch can be expected
* Full test suite passes against PostgreSQL 9.6, and OpenJDK 9
* Performance optimization for timestamps (~`TimeZone.getDefault` optimization)
* Allow build-from-source on GNU/Linux without maven repositories, and add Fedora Copr test to the regression suite

Full change log can be found here: https://jdbc.postgresql.org/documentation/changelog.html#introduction

## Supported PostgreSQL versions

Pgjdbc regression tests are run against all PostgreSQL versions since 8.4, including "build PostgreSQL from git master" version.
Don't assume pgjdbc 9.4.x is only PostgreSQL 9.4 compatible.

### Maven
```xml
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <version>9.4.1211</version> <!-- Java 8 -->
  <version>9.4.1211.jre7</version> <!-- Java 7 -->
  <version>9.4.1211.jre6</version> <!-- Java 6 -->
</dependency>
```
### Gradle
Java 8:
```
'org.postgresql:postgresql:9.4.1211'
```
Java 7:
```
'org.postgresql:postgresql:9.4.1211.jre7'
```
Java 6:
```
'org.postgresql:postgresql:9.4.1211.jre6'
```
### Ivy
Java 8:
```xml
<dependency org="org.postgresql" name="postgresql" rev="9.4.1211"/>
```
Java 7:
```xml
<dependency org="org.postgresql" name="postgresql" rev="9.4.1211.jre7"/>
```
Java 6:
```xml
<dependency org="org.postgresql" name="postgresql" rev="9.4.1211.jre6"/>
```

### Development snapshots

Snapshot builds (builds from `master` branch) are deployed to Maven Central, so you can test current development version via
```xml
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <version>9.4.1212-SNAPSHOT</version> <!-- Java 8 -->
  <version>9.4.1212.jre7-SNAPSHOT</version> <!-- Java 7 -->
  <version>9.4.1212.jre6-SNAPSHOT</version> <!-- Java 6 -->
</dependency>
```

There are also available (snapshot) binary RPMs in Fedora's Copr repository, you
can download them from:
https://copr.fedorainfracloud.org/coprs/g/pgjdbc/pgjdbc-travis/


## Build requirements

In order to build the source code for PgJDBC you will need the following tools:

- A git client
- A recent version of Maven (3.x)
- A JDK for the JDBC version you'd like to build (JDK6 for JDBC 4, JDK7 for JDBC 4.1 or JDK8 for JDBC 4.2)
- A running PostgreSQL instance

Additionally, in order to update translations (not typical), you will need the following additional tools:

-  the gettext package, which contains the commands "msgfmt", "msgmerge", and "xgettext"

## Checking out the source code

The PgJDBC project uses git for version control. You can check out the current code by running:

    git clone https://github.com/pgjdbc/pgjdbc.git
    
This will create a pgjdbc directory containing the checked-out source code.
In order do build jre7 or jre6 compatible versions, check out those repositories under `pgjdbc`

    cd pgjdbc # <-- that is pgjdbc/pgjdbc.git clone
    git clone https://github.com/pgjdbc/pgjdbc-jre7.git
    git clone https://github.com/pgjdbc/pgjdbc-jre6.git

Note: all the source code is stored in `pgjdbc.git` repository, so just `pgjdbc.git` is sufficient for development.

## Compiling with Maven on the command line

After checking out the code you can compile and test the PgJDBC driver by running the following
on a command line:

    mvn package

Note: if you want to skip test execution, issue `mvn package -DskipTests`.

Note: in certain cases, proper build requires cleaning the results of previous one.
For instance, if you remove a `.java` file, then clean is required to remove the relevant `.class` file.
In such cases, use `mvn clean` or `mvn clean package`.

PgJDBC doesn't natively support building from IDEs like Eclipse, NetBeans or
IntelliJ. However you can use the tools Maven support from within the IDE if you wish.
You can use regular IDE tools to develop, execute tests, etc, however if you want to build final
artifacts you should use `mvn`.
  
After running the build , and build a .jar file (Java ARchive)
depending on the version of java and which release you have the jar will be named
postgresql-<major>.<minor>.<release>.jre<N>.jar. Where major,minor are the PostgreSQL major,minor
version numbers. release is the jdbc release number. N is the version of the JDBC API which 
corresponds to the version of Java used to compile the driver.

The target directory will contain the driver jar.
If you need source code, documentation and runtime dependencies use `mvn package -P release-artifacts`.

*NOTE*: default build produces Java 8 (JDBC 4.2) driver (in `pgjdbc/target` folder).

If you need a version for older Java, configure `~/.m2/toolchains.xml`.
Here's sample configuration for macOS:
```xml
<?xml version="1.0" encoding="UTF8"?>
<toolchains>
  <!-- JDK toolchains -->
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>1.6</version>
      <vendor>oracle</vendor>
    </provides>
    <configuration>
      <jdkHome>/Library/Java/JavaVirtualMachines/1.6.0.jdk/Contents/Home</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>1.7</version>
      <vendor>oracle</vendor>
    </provides>
    <configuration>
      <jdkHome>/Library/Java/JavaVirtualMachines/jdk1.7.0_55.jdk/Contents/Home</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>1.8</version>
      <vendor>oracle</vendor>
    </provides>
    <configuration>
      <jdkHome>/Library/Java/JavaVirtualMachines/jdk1.8.0_60.jdk/Contents/Home</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

## Updating translations

From time to time, the translation packages will need to be updated as part of the build process.
However, this is atypical, and is generally only done when needed such as by a project committer before a major release.
This process adds additional compile time and generally should not be executed for every build.

Updating translations can be accomplished with the following command:

    mvn -Ptranslate compile && git add pgjdbc && git commit -m "Translations updated"

Note that the maven profile "translate" can safely be called with other profiles, such as -P release-artifacts.
Invocation of this command will generate new .po files, a new messages.pot file, and newly translated class files.

## Releasing a snapshot version

TravisCI automatically deploys snapshots for each commit to master branch.

Git repository typically contains -SNAPSHOT versions, so you can use the following command:

    mvn deploy && (cd pgjdbc-jre7; mvn deploy) && (cd pgjdbc-jre6; mvn deploy)

## Releasing a new version

Prerequisites:
- JDK 6, JDK 7, and JDK8 configured in `~/.m2/toolchains.xml`
- a PostgreSQL instance for running tests
- ensure that the RPM packaging CI isn't failing at
  [copr web page](https://copr.fedorainfracloud.org/coprs/g/pgjdbc/pgjdbc-travis/builds/) -
  possibly bump `parent poms` or `pgjdbc` versions in RPM [spec file](packaging/rpm/postgresql-jdbc.spec).

Procedure:

Release a version for JDK8
- From a root folder, perform `mvn release:clean release:prepare`. That will ask you new version, update pom.xml, commit and push it to git.
- From a root folder, perform `mvn release:perform`. That will *stage* Java 8-compatible PgJDBC version to maven central.

Release a version for JDK7
- Update `pgjdbc` submodule in `pgjdbc-jre7`

```
cd pgjdbc-jre7/pgjdbc
git checkout master
git reset --hard REL9.4.1208
cd ..
git add pgjdbc
git commit -m "Update pgjdbc"
```

- Release `pgjdbc-jre7`

```
mvn release:clean release:prepare release:perform
```

Release a version for JDK6
- Update `pgjdbc` submodule in `pgjdbc-jre7`
- Release `pgjdbc-jre6`

```
mvn release:clean release:prepare release:perform
```

Close staging repository and release it:
- From a `pgjdbc` folder, perform

```
mvn nexus-staging:close -DstagingRepositoryId=orgpostgresql-1082
```

The staged repository will become open for smoke testing access at https://oss.sonatype.org/content/repositories/orgpostgresql-1082/

If staged artifacts look fine, release it

```
 mvn nexus-staging:release -DstagingRepositoryId=orgpostgresql-1082
```

Update changelog:
- run `./release_notes.sh`, edit as desired

## Dependencies

PgJDBC has optional dependencies on other libraries for some features. These
libraries must also be on your classpath if you wish to use those features; if
they aren't, you'll get a `PSQLException` at runtime when you try to use features
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

    java -cp postgresql-<major>.<minor>.<release>.jre<N>.jar -jar myprogram.jar

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

For more information see the [the PgJDBC driver documentation](https://jdbc.postgresql.org/documentation/documentation.html) and [the JDBC tutorial](http://docs.oracle.com/javase/tutorial/jdbc/).

## Bug reports, patches and development

PgJDBC development is carried out on the [PgJDBC mailing list](https://jdbc.postgresql.org/community/mailinglist.html) and on [GitHub](https://github.com/pgjdbc/pgjdbc).

Set of "backend protocol missing features" is collected in [backend_protocol_v4_wanted_features.md](backend_protocol_v4_wanted_features.md)

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

Then, to test against old JDK, run `mvn test` in `pgjdbc-jre6` or `pgjdbc-jre7` modules.

For information about the unit tests and how to run them, see
  [org/postgresql/test/README](pgjdbc/src/test/java/org/postgresql/test/README.md)


### Sponsors

[PostgreSQL International](http://www.postgresintl.com) 
 
