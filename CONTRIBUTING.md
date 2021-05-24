# Guidelines for Contributing

Thank you so much for wanting to contribute to **PostgreSQL JDBC Driver**!

The purpose of the *Guidelines for Contributing* is to create a collaboration baseline.
**Do NOT** blindly obey these guidelines, use them (after understanding) where they make sense.

Currently the PgJDBC driver supports the Oracle and OpenJDK Java implementations of
versions **8** and **higher**; and PostgreSQL server versions from **8.4** and higher.

Some PostgreSQL forks *might* work but are not officially supported, we support vendors of forks
that want to improve this driver by sending us pull requests that are not disruptive to the
community ecosystem of PostgreSQL.

## Issues

Issues are a great way to keep track of tasks, enhancements, and bugs for the PgJDBC project.

### How to submit a bug report

If you find a bug in the PgJDBC driver please use an issue to report it, try to be concise
and detailed in your report, please ensure to specify at least the following:

  * Use a concise subject.
  * PgJDBC driver version (e.g. 42.2.14)
  * JDK/JRE version or the output of `java -version` (e.g. OpenJDK Java 8u144, Oracle Java 7u79)
  * PostgreSQL server version or the output of `select version()` (e.g. PostgreSQL 9.6.2)
  * Context information: what you were trying to achieve with PgJDBC.
  * Simplest possible steps to reproduce
    * More complex the steps are, lower the priority will be.
    * A pull request with failing JUnit test case is most preferred, although it's OK to paste
      the test case into the issue description.

You can consider a bug: some behaviour that worked before and now it does not; a violation of the
JDBC spec in any form, unless it's stated otherwise as an extension.

In the unlikely event that a breaking change is introduced in the driver we will update the major version.
We will document this change but please read carefully the changelog and test thoroughly for any potential problems with your app.
What is not acceptable is to introduce breaking changes in the minor or patch update of the driver,
If you find a regression in a minor patch update, please report an issue.

Bug reports are not isolated only to code, errors in documentation as well as the website source
code located in the **docs** directory also qualify. You are welcome to report issues and send a
pull request on these as well. [skip ci] can be added to the commit message to prevent Travis-CI from building a
pull request that only changes the documentation.

For enhancements request keep reading the *Ideas, enhancements and new features* seccion.

### Ideas, enhancements and new features

If you have ideas or proposed changes, please post on the
[mailing list](https://www.postgresql.org/list/pgsql-jdbc/) or open a detailed,
specific [GitHub issue](https://github.com/pgjdbc/pgjdbc/issues/new).

Think about how the change would affect other users, what side effects it
might have, how practical it is to implement, what implications it would
have for standards compliance and security, etc. Include a detailed use-case
description.

Few of the PgJDBC developers have spare time, so it's unlikely that your
idea will be picked up and implemented for you. The best way to make sure a
desired feature or improvement happens is to implement it yourself. The PgJDBC
sources are reasonably clear and they're pure Java, so it's sometimes easier
than you might expect.

## Contributing code

Here are a few important things you should know about contributing code:

  1. API changes require discussion, use cases, etc. Code comes later.
  2. Pull requests are great for small fixes for bugs, documentation, etc.
  3. Pull request needs to be approved and merged by maintainers into the master branch.
  4. Pull requests needs to fully pass CI tests.

### Build requirements

In order to build the source code for PgJDBC you will need the following tools:

  - A git client
  - A JDK for the JDBC version you'd like to build (JDK7 for JDBC 4.1 or JDK8 for JDBC 4.2)
  - A running PostgreSQL instance (optional for unit/integration tests)

Additionally, in order to update translations (not typical), you will need the following additional tools:

  -  the gettext package, which contains the commands "msgfmt", "msgmerge", and "xgettext"

### Hacking on PgJDBC

The PgJDBC project uses git for version control. You can check out the current code by running:

    git clone https://github.com/pgjdbc/pgjdbc.git

## Compiling with Gradle on the command line

After checking out the code you can compile and test the PgJDBC driver by running the following
on a command line (the outputs are located in the relevant:

    ./gradlew tasks # lists available tasks

    ./gradlew build # builds everything
    ./gradlew assemble # build the artifacts
    ./gradlew build -x test # build the artifacts, verify code style, skip tests
    ./gradlew javadoc # build javadoc

    ./gradlew check # verify code style, execute tests
    ./gradlew style # update code formatting (for auto-correctable cases) and verify style
    ./gradlew autostyleCheck checkstyleAll # report code style violations

    ./gradlew test # execute tests
    ./gradlew test --tests org.postgresql.test.ssl.SslTest # execute test by class
    ./gradlew test -PincludeTestTags=!org.postgresql.test.SlowTests # skip slow tests

Note: by default `pgjdbc` builds Java7-compatible jar as well, and it might be a bit confusing
as every class is present in multiple files.
You can skip `postgresql-jre7` by adding `pgjdbc.skip.jre7` to your `$HOME/.gradle/gradle.properties`

Note: `clean` is not required, and the build automatically re-executes the tasks.
However, Gradle caches the results of `test` execution as well, so if you want to
re-execute tests even without changes to the source code, you might need to call
`./gradlew cleanTest test`

PgJDBC uses Gradle for the build system, so IDEs that support Gradle should be able
to load, build, and execute PgJDBC tests.
It is known that PgJDBC loads automatically and it works in IntelliJ IDEA.

After running the build , and build a .jar file (Java ARchive)
depending on the version of java and which release you have the jar will be named
postgresql[-jre<N>]-<major>.<minor>.<patch>.jar. We use Semantic versioning; as such
major, minor, patch refer to the level of change introduced. For Java 7
jre<N> will be appended after the patch level. N corresponds to the version of Java,
roughly correlated to the JDBC version number.

## Code style

We enforce a style using [Autostyle](https://github.com/autostyle/autostyle) and
[Checkstyle](https://github.com/checkstyle/checkstyle),
Travis CI will fail if there are checkstyle errors.

You might use the following command to fix auto-correctable issues, and report the rest:

    ./gradlew style

## Null safety

The project uses the [Checker Framework](https://checkerframework.org/) for verification of the null safety
(this was introduced in [PR 1814](https://github.com/pgjdbc/pgjdbc/pull/1814)).

By default, parameters, return values, and fields are `@NonNull`, so you need to add `@Nullable`
as required.

To execute the Checker Framework locally please use the following command:

    ./gradlew -PenableCheckerframework :postgresql:classes

Notable items:

* The Checker Framework verifies code method by method. That means, it can't account for method execution order.
That is why `@Nullable` fields should be verified in each method where they are used.
If you split logic into multiple methods, you might want verify null once, then pass it via non-nullable parameters.
For fields that start as null and become non-null later, use `@MonotonicNonNull`.
For fields that have already been checked against null, use `@RequiresNonNull`.

* If you are absolutely sure the value is non-null, you might use `org.postgresql.util.internal.Nullness.castNonNull(T)`
or `org.postgresql.util.internal.Nullness.castNonNull(T, String)`.

* You can configure postfix completion in IntelliJ IDEA via `Preferences -> Editor -> General -> Postfix Completion`
key: `cnn`, applicable element type: `non-primitive`, `org.postgresql.util.internal.Nullness.castNonNull($EXPR$)`.  

* The Checker Framework comes with an annotated JDK, however, there might be invalid annotations.
In that cases, stub files can be placed to `/config/checkerframework` to override the annotations.
It is important the files have `.astub` extension otherwise they will be ignored.

* In array types, a type annotation appears immediately before the type component (either the array or the array component) it refers to.
This is explained in the [Java Language Specification](https://docs.oracle.com/javase/specs/jls/se8/html/jls-9.html#jls-9.7.4).

        String nonNullable;
        @Nullable String nullable;

        java.lang.@Nullable String fullyQualifiedNullable;

        // array and elements: non-nullable
        String[] x;

        // array: nullable, elements: non-nullable
        String @Nullable [] x;

        // array: non-nullable, elements: nullable
        @Nullable String[] x;

        // array: nullable, elements: nullable
        @Nullable String @Nullable [] x;  

        // arrays: nullable, elements: nullable
        // x: non-nullable
        // x[0]: non-nullable
        // x[0][0]: nullable
        @Nullable String[][] x;

        // x: nullable
        // x[0]: non-nullable
        // x[0][0]: non-nullable
        String @Nullable [][] x;

        // x: non-nullable
        // x[0]: nullable
        // x[0][0]: non-nullable
        String[] @Nullable [] x;

## Updating translations

From time to time, the translation packages will need to be updated as part of the build process.
However, this is atypical, and is generally only done when needed; such as by a project committer before a major release.
This process adds additional compile time and generally should not be executed for every build.

Updating translations can be accomplished with the following command:

    ./gradlew generateGettextSources && git add pgjdbc && git commit -m "Translations updated"

## Releasing a snapshot version

[Stage Vote Release Plugin](https://github.com/vlsi/vlsi-release-plugins/tree/master/plugins/stage-vote-release-plugin)
is used for releasing artifacts.

## Releasing a new version

Prerequisites:
- Java 8
- a PostgreSQL instance for running tests; it must have a user named `test` as well as a database named `test`
- ensure that the RPM packaging CI isn't failing at
  [copr web page](https://copr.fedorainfracloud.org/coprs/g/pgjdbc/pgjdbc-travis/builds/)

### Manual release procedure

See details in [Stage Vote Release readme](https://github.com/vlsi/vlsi-release-plugins/tree/master/plugins/stage-vote-release-plugin#making-a-release-candidate)

Prepare release candidate:

    ./gradlew prepareVote -Prc=1 -Pgh

It will create a release candidate tag, push the artifacts to Maven Central and print the announcement draft.
The staged repository will become open for smoke testing access at https://oss.sonatype.org/content/repositories/orgpostgresql-1082/

If staged artifacts look fine, release it

    ./gradlew publishDist -Prc=1 -Pgh

Then update version, and readme as required.

### Updating changelog

- run `./release_notes.sh`, edit as desired

## Dependencies

PgJDBC has optional dependencies on other libraries for some features. These
libraries must also be on your classpath if you wish to use those features; if
they aren't, you'll get a `PSQLException` at runtime when you try to use features
with missing libraries.

Gradle will download additional dependencies from the Internet (from Maven
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

For information on working with GitHub, see:
https://guides.github.com/activities/forking/ and
https://guides.github.com/introduction/flow/.

### Testing

Remember to test proposed PgJDBC patches when running against older PostgreSQL
versions where possible, not just against the PostgreSQL you use yourself.

You also need to test your changes with older JDKs. PgJDBC must support JDK7
("Java 1.7") and newer. Code that is specific to a particular spec version
may use features from that version of the language. i.e. JDBC4.1 specific
may use JDK7 features, JDBC4.2 may use JDK8 features.
Common code and JDBC4 code needs to be compiled using JDK6.

Three different versions of PgJDBC can be built, the JDBC 4.1 and 4.2 drivers.
The driver to build is auto-selected based on the JDK version used to run the
build.

Note: `postgresql-jre7` is provided on a best effort basis, and the test suite
requires Java 1.8 for execution.

You can get old JDK versions from the [Oracle Java Archive](http://www.oracle.com/technetwork/java/archive-139210.html).

If you have Docker, you can use `docker-compose` to launch test database (see [docker](docker)):

    cd docker/postgres-server

    # Launch the most recent PostgreSQL database with SSL, XA, and SCRAM
    docker-compose down && docker-compose up

    # Launch PostgreSQL 9.6, with XA, without SSL
    docker-compose down && SSL=no XA=yes docker-compose up

Alternatively, to run the test server with Docker in the foreground:

    docker/bin/postgres-server

An alternative way is to use a Vagrant script: [jackdb/pgjdbc-test-vm](https://github.com/jackdb/pgjdbc-test-vm).
Follow the instructions on that project's [README](https://github.com/jackdb/pgjdbc-test-vm) page.

For more information about the unit tests and how to run them, see
  [TESTING.md](TESTING.md)

## Support for IDEs

It's possible to debug and test PgJDBC with various IDEs, not just with mvn on
the command line. Projects aren't supplied, but it's easy to prepare them.

### IntelliJ IDEA

IDEA imports PgJDBC project just fine. So clone the project whatever way you like and import it (e.g. File -> Open -> `build.gradle.kts`)

UPD: as of 2020-03, the code style is managed via `.editorconfig`, so IDEA should configure itself automatically.

* Configure code style:

Project code style is located at `pgjdbc/src/main/checkstyle/pgjdbc-intellij-java-google-style.xml`
In order to import it, copy the file to `$IDEA_CONFIG_LOCATION/codestyles` folder, restart IDEA,
then choose "GoogleStyle (PgJDBC)" style for the Preferences -> Editor -> CodeStyle setting.

For instance, for macOS it would be `~/Library/Preferences/IntelliJIdeaXX/codestyles`.
More details here: https://intellij-support.jetbrains.com/hc/en-us/articles/206827437-Directories-used-by-the-IDE-to-store-settings-caches-plugins-and-logs

### Eclipse

On Eclipse Mars, to import PgJDBC as an Eclipse Java project with full
support for on-demand compile, debugging, etc, you can use the following approach:

* File -> Import ...
* Then choose Existing Gradle Project and proceed with the import.
* Pick `git`, select `https://github.com/pgjdbc/pgjdbc.git` URL.
* Click finish

Configure format configuration:
* Import "import order" configuration: Eclipse -> Preferences -> Java -> Java Code Style -> Organize Imports -> Import... -> `.../workspace-pgjdbc/pgjdbc-aggregate/pgjdbc/src/main/checkstyle/pgjdbc_eclipse.importorder`
* Import "formatter" configuration: Eclipse -> Preferences -> Java -> Java Code Style -> Formatter -> Import... -> `.../workspace-pgjdbc/pgjdbc-aggregate/pgjdbc/src/main/checkstyle/pgjdbc-eclipse-java-google-style.xml`
* Configure "trim trailing whitespace": Eclipse -> Preferences -> Java -> Editor -> Save Actions -> "Perform Selected actions on save":
  * Check "Format source code", "Format edited lines"
  * Keep "Optimize Imports" selected
  * Check "Additional actions", click "Configure"
  * Click "Remove trailing whitespace", all lines
  * On "Code Style" tab, check "Use blocks in if/while/... statements", "Always"
  * On "Missing Code" tab, uncheck "Add missing @Override annotation"
  * On "Unnecessary Code" tab, check "Remove unused imports"

### Other IDEs

Please submit build instructions for your preferred IDE.

## <a name="tests"></a> Coding Guidelines

### Java

Project uses [Google style](https://google.github.io/styleguide/javaguide.html) conventions for java with 100 wide lines.
Code style is verified via Travis job. In order to do manual verification, issue

    ./gradlew style

Use 2 spaces for indenting, do not use tabs, trim space at end of lines.
Always put braces, even for single-line `if`.
Always put `default:` case for `switch` statement.

Note: there are formatter configurations in `pgjdbc/src/main/checkstyle` folder.

### Test

General rule: failing test should look like a good bug report. Thus `Assert.fail()` is bad.

* Consider using "single assertion" per test method. Having separate test methods helps manual execution of the tests,
and it makes test report cleaner

* Consider using `assertEquals(String message, expected, actual)` instead of `assertTrue(expected == actual)`.
The former allows you to provide human readable message and it integrates well with IDEs (i.e. it allows to open diff
of expected and actual).

 If using just `assertTrue(expected == actual)` all you get is a stacktrace and if such a test fails a developer
has to reverse engineer the intention behind that code.

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

### Body
Just as in the **subject**, use the imperative, present tense: "change" not "changed" nor "changes"
The body should include the motivation for the change and contrast this with previous behavior.

### Footer
The footer should contain any information about **Breaking Changes** and is also the place to
reference GitHub issues that this commit **Closes**.
