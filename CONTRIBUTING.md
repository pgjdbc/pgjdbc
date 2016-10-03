# How to contribute

Thank you so much for wanting to contribute to PostgreSQL JDBC Driver! Here are a few important
things you should know about contributing:

  1. API changes require discussion, use cases, etc. Code comes later.
  2. Pull requests are great for small fixes for bugs, documentation, etc.
  3. Pull requests are not merged directly into the master branch.

## Ideas

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

### IntelliJ IDEA

IDEA imports PgJDBC project just fine. So clone the project whatever way you like and import it (e.g. File -> Open -> `pom.xml`) 

* Configure code style:

Project code style is located at `pgjdbc/src/main/checkstyle/pgjdbc-intellij-java-google-style.xml`
In order to import it, copy the file to `$IDEA_CONFIG_LOCATION/codestyles` folder, restart IDEA,
then choose "GoogleStyle (PgJDBC)" style for the Preferences -> Editor -> CodeStyle setting.

For instance, for macOS it would be `~/Library/Preferences/IntelliJIdeaXX/codestyles`.
More details here: https://intellij-support.jetbrains.com/hc/en-us/articles/206827437-Directories-used-by-the-IDE-to-store-settings-caches-plugins-and-logs

### Eclipse

On Eclipse Mars, to import PgJDBC as an Eclipse Java project with full
support for on-demand compile, debugging, etc, you can use the following approach:

* File -> New -> Project
* Maven -> Check out Maven Project from SCM
* Pick `git`, select `https://github.com/pgjdbc/pgjdbc.git` URL.
Note: if `git` SCM is missing, just click `m2e Marketplace` link and search for `egit` there. Note the letter `e`.
* Click finish
* Eclipse might complain with "Plugin execution not covered by lifecycle configuration: com.igormaznitsa:jcp:6.0.1:preprocess (execution: preprocessSources, phase: generate-sources)", however this error seems to be not that important

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


Eclipse will interoperate fine with Maven, so you can test and debug
with Eclipse then do dist builds with Maven.

### Other IDEs

Please submit build instructions for your preferred IDE.

## <a name="tests"></a> Coding Guidelines

### Java

Project uses [Google style](https://google.github.io/styleguide/javaguide.html) conventions for java with 100 wide lines.
Code style is verified via Travis job. In order to do manual verification, issue

    cd pgjdbc && mvn checkstyle:check

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

###Footer
The footer should contain any information about **Breaking Changes** and is also the place to
reference GitHub issues that this commit **Closes**.
