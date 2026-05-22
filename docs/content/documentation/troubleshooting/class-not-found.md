---
title: "org.postgresql.Driver not found"
date: 2026-05-16T00:00:00Z
draft: false
weight: 5
toc: true
last_reviewed: "2026-05-21"
description: "The classic ClassNotFoundException / NoClassDefFoundError on org.postgresql.Driver from legacy Class.forName code — what it means in 2026 and why the call almost always belongs in the bin."
---

The error usually surfaces in one of two forms:

```
java.lang.ClassNotFoundException: org.postgresql.Driver
java.lang.NoClassDefFoundError: org/postgresql/Driver
```

The first is raised by `Class.forName("org.postgresql.Driver")` when
the JVM cannot resolve the name; the second is raised by the verifier
when *some other class* references `org.postgresql.Driver` and the
class is not available at link time. Both have the same root cause
(the driver class is not visible to the calling classloader) and the
same fix in modern code.

## First: do you actually need `Class.forName`?

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ServiceLoader entry | pgjdbc/src/main/resources/META-INF/services/java.sql.Driver | 1
- Driver static initializer | pgjdbc/src/main/java/org/postgresql/Driver.java | 70-80
- Quick start Class.forName note | docs/content/documentation/getting-started/install.md | 38-42
{{< /review >}}

Almost certainly not. Since JDBC&nbsp;4.0 / Java&nbsp;6, the
`DriverManager` discovers drivers via the
[`ServiceLoader`](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html)
mechanism: every JAR carrying a `META-INF/services/java.sql.Driver`
file is registered automatically when the JAR is on the classpath.
Every pgJDBC release ships that file in its JAR, containing
`org.postgresql.Driver`. The
[Quick start ServiceLoader note](/documentation/getting-started/install/)
spells this out next to the modern connection example.

Old tutorials, copy-pasted boilerplate from the Java 1.4 era, and
defensive "make sure the driver is loaded" patterns still carry
lines like:

```java
Class.forName("org.postgresql.Driver");                    // not needed
Connection c = DriverManager.getConnection(url, props);
```

Delete the `Class.forName` line. If the driver was already loaded by
the `ServiceLoader`, this changes nothing. If it was not, you'll get
[`No suitable driver found`](/documentation/troubleshooting/no-suitable-driver/)
instead, a more actionable error that points at the real problem
(classpath, shaded JAR, classloader visibility) rather than at the
absent `Class.forName` call.

## If you genuinely cannot remove `Class.forName`

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- BaseDataSource loads Driver | pgjdbc/src/main/java/org/postgresql/ds/common/BaseDataSource.java | 62-77
- BaseDataSource still uses DriverManager | pgjdbc/src/main/java/org/postgresql/ds/common/BaseDataSource.java | 101-115
- DriverManager registration | pgjdbc/src/main/java/org/postgresql/Driver.java | 757-788
- SSPI Waffle probe | pgjdbc/src/main/java/org/postgresql/sspi/SSPIClient.java | 103-123
- SCRAM shading | pgjdbc/build.gradle.kts | 138-139
- Source-distribution shade minimizer | pgjdbc/reduced-pom.xml | 220-241
{{< /review >}}

Some legacy frameworks call `Class.forName` themselves (the value is
read from a config file) and you can't change them. The exception
then means exactly what it says: the JVM is asked to load
`org.postgresql.Driver` and cannot find it. The four causes mirror
those of [`No suitable driver found`](/documentation/troubleshooting/no-suitable-driver/):

- **The pgJDBC JAR is not on the classpath.** Verify with
  `mvn dependency:tree`, `./gradlew dependencies --configuration runtimeClasspath`,
  or by inspecting the runtime classpath the launcher passes to
  `java`.
- **The JAR is on the classpath but in a different classloader.**
  Common in OSGi, JPMS-with-classpath-mix, and application servers
  where the driver lives in a shared loader and the calling code
  lives in a child. `Class.forName` resolves through the *caller's*
  classloader by default; if the loader chain does not reach the
  JAR, the class is invisible.
  - For the three-argument form
    `Class.forName(name, true, classLoader)`, the loader you pass is
    the one that searches.
  - The cleanest fix is to lift the JAR to a classloader visible to
    the caller. `PGSimpleDataSource` can help with ServiceLoader
    visibility because `BaseDataSource` explicitly loads
    `org.postgresql.Driver`, but its connection path still goes
    through `DriverManager`.
- **The class was stripped by a shading minimizer.** Maven Shade
  Plugin's `minimizeJar` and similar tools may decide that
  `org.postgresql.Driver` is unreachable (there are no direct
  byte-code references to it; `ServiceLoader` finds it via reflection)
  and drop it from the final artifact. Either disable
  `minimizeJar` for this artifact or add an explicit keep rule
  (`<filter><artifact>org.postgresql:postgresql</artifact><includes><include>**</include></includes></filter>`).
- **A `NoClassDefFoundError` on a different class.** If the message
  is `NoClassDefFoundError: org/postgresql/util/...` (anything other
  than `Driver` itself), the driver class loaded but another class
  needed by that code path did not. For example, SSPI requires
  `waffle-jna` to be available, and custom source-distribution or
  shaded builds must keep the shaded SCRAM classes and OSGi support
  classes they use.

## Related

- [No suitable driver found](/documentation/troubleshooting/no-suitable-driver/):
  the `DriverManager`-side variant of the same root issue, with
  more detail on shaded JARs and classloader visibility.
- [Quick start](/documentation/getting-started/install/): the
  Maven / Gradle dependency declaration and the
  `ServiceLoader`-vs-`Class.forName` note.
- [DataSource and JNDI](/documentation/connect/datasource/):
  `PGSimpleDataSource` explicitly loads the pgJDBC driver before
  requesting a connection through `DriverManager`.
