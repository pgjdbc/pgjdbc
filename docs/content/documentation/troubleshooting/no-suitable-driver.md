---
title: "No suitable driver found"
date: 2026-05-16T00:00:00Z
draft: false
weight: 4
toc: true
last_reviewed: "2026-05-21"
description: "`DriverManager.getConnection` throws `SQLException: No suitable driver found for jdbc:postgresql://…`. The four ways the driver can be unreachable to `DriverManager`, with a one-line diagnostic for each."
---

The error, verbatim:

```
java.sql.SQLException: No suitable driver found for jdbc:postgresql://…
```

`DriverManager.getConnection(url, …)` walks every registered
`java.sql.Driver` and asks each one to `connect(url, …)`. The
exception fires when every driver returns `null`. Either there are no
pgJDBC drivers registered, or one is registered but does not
recognize the URL you passed. The four sections below cover those
cases in order of diagnostic value. `DriverManager.getDriver(url)` is
slightly different: that lookup uses `acceptsURL(url)`, but it is not
the path that throws the longer `No suitable driver found for ...`
message from `getConnection`.

## Confirm what's registered first

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- Driver auto-registration | pgjdbc/src/main/java/org/postgresql/Driver.java | 70-80
- connect returns null for non-pgJDBC URLs | pgjdbc/src/main/java/org/postgresql/Driver.java | 245-255
- DriverManager registration test | pgjdbc/src/test/java/org/postgresql/test/jdbc2/DriverTest.java | 483-522
{{< /review >}}

Before touching configuration, dump the actual `DriverManager` state.
This is the fastest way to distinguish "the JAR isn't loaded" from
"the JAR is loaded but the URL is wrong":

```java
import java.sql.DriverManager;
import java.util.Collections;

for (java.sql.Driver d : Collections.list(DriverManager.getDrivers())) {
    System.out.println(d.getClass().getName()
        + "  loaded by " + d.getClass().getClassLoader());
}
```

If `org.postgresql.Driver` does not appear in that list, jump to
[The JAR is not on the classpath](#the-jar-is-not-on-the-classpath)
or [The shaded JAR lost its service-loader entry](#the-shaded-jar-lost-its-service-loader-entry).
If it does, jump to [The URL does not match the driver's pattern](#the-url-does-not-match-the-drivers-pattern).

## The JAR is not on the classpath

A common cause. `DriverManager` discovers drivers via
the [`ServiceLoader`](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html)
mechanism: it reads files named `META-INF/services/java.sql.Driver`
from every JAR on the classpath and loads the classes named there. If
the postgresql JAR is not present, `ServiceLoader` finds nothing and
the registration never happens.

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ServiceLoader entry | pgjdbc/src/main/resources/META-INF/services/java.sql.Driver | 1
- Driver static initializer | pgjdbc/src/main/java/org/postgresql/Driver.java | 70-80
{{< /review >}}

Verify the dependency is actually on the build path:

```bash
# Maven
mvn dependency:tree | grep org.postgresql

# Gradle
./gradlew dependencies --configuration runtimeClasspath | grep org.postgresql
```

For a hand-rolled launch, the JAR must appear on `-cp` / `-classpath`:

```bash
java -cp postgresql-42.7.11.jar:myapp.jar com.example.Main
```

`Class.forName("org.postgresql.Driver")` does **not** rescue this
case; the class still needs to be on the classpath to be loadable.
See the install page's
[ServiceLoader note](/documentation/getting-started/install/): that
call has been redundant since Java&nbsp;6 and is not a substitute for
a real dependency.

## The shaded JAR lost its service-loader entry

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- ServiceLoader entry | pgjdbc/src/main/resources/META-INF/services/java.sql.Driver | 1
{{< /review >}}

When an application is repackaged into a single "fat" JAR (Maven
Shade Plugin, Gradle Shadow, `mvn-assembly`, `spring-boot:repackage`),
the service-loader file at `META-INF/services/java.sql.Driver` from
each dependency must be merged into the final JAR. Many shading
defaults silently drop these files when they collide across
dependencies, which removes pgJDBC's registration.

Symptoms specific to this case:

- `DriverManager.getDrivers()` returns an empty (or partial) list at
  runtime even though `org.postgresql.Driver.class.getResource(...)`
  finds the class.
- The app works when run from the IDE (raw classpath) but fails when
  launched from the shaded artifact.

Fix per build system:

### Maven Shade Plugin

Add the `ServicesResourceTransformer`:

```xml
<plugin>
  <artifactId>maven-shade-plugin</artifactId>
  <configuration>
    <transformers>
      <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
    </transformers>
  </configuration>
</plugin>
```

### Gradle Shadow

The plugin includes `mergeServiceFiles()` but does not call it by
default:

```kotlin
tasks.shadowJar {
    mergeServiceFiles()
}
```

### Spring Boot

`spring-boot-maven-plugin` and `spring-boot-gradle-plugin` produce a
**nested** fat JAR: dependencies stay as JARs inside
`BOOT-INF/lib/`, the service-loader files are not collapsed, and
nothing breaks. If you are seeing this error with a Spring Boot
artifact, the build is producing a flat shaded JAR (custom packaging,
or `spring-boot:repackage` was overridden); switch back to the
default repackaging.

## The URL does not match the driver's pattern

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- acceptsURL delegates to parseURL | pgjdbc/src/main/java/org/postgresql/Driver.java | 451-463
- parseURL prefix and URL grammar | pgjdbc/src/main/java/org/postgresql/Driver.java | 535-688
- accepted and rejected URL tests | pgjdbc/src/test/java/org/postgresql/test/jdbc2/DriverTest.java | 72-175
{{< /review >}}

`org.postgresql.Driver.connect(url, …)` returns `null` unless the URL
starts with the literal prefix `jdbc:postgresql:`. Anything else is
silently a "no" and falls through to the next driver, then to
"No suitable driver found". `acceptsURL(url)` uses the same parser, so
it is still a useful standalone check.

Common typos and slips:

- `jdbc:postgres://...` (missing the `ql`)
- `postgresql://...` (missing the `jdbc:` scheme)
- `jdbc:postgresql//...` (missing the `:` after `postgresql`)
- An empty URL after a property-lookup mishap

The full URL grammar is documented at
[JDBC URL](/documentation/connect/url-syntax/);
the simplest valid form is:

```
jdbc:postgresql://host:port/database
```

## The driver is registered but in a different classloader

{{< review date="2026-05-21" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- DriverManager registration | pgjdbc/src/main/java/org/postgresql/Driver.java | 757-788
- BaseDataSource loads Driver | pgjdbc/src/main/java/org/postgresql/ds/common/BaseDataSource.java | 62-77
- BaseDataSource still uses DriverManager | pgjdbc/src/main/java/org/postgresql/ds/common/BaseDataSource.java | 101-115
- OSGi activator registers driver and DataSourceFactory | pgjdbc/src/main/java/org/postgresql/osgi/PGBundleActivator.java | 26-68
{{< /review >}}

In containers and modular runtimes (OSGi, JPMS, application servers,
GraalVM polyglot), `DriverManager.getConnection` only sees drivers
loaded by a classloader visible to the caller. A driver loaded by a
child or sibling classloader can be registered yet still be skipped
by `DriverManager` for that caller.

The `DriverManager` source documents this explicitly: it skips
drivers whose `Class` is not visible from the caller's classloader.

Workarounds, in decreasing order of cleanliness:

- **Move the JAR up to a classloader visible to the caller:** the
  application classloader, the container's shared classloader, the
  JPMS module's transitive dependencies, the OSGi system bundle.
- **Use a `DataSource` instead of `DriverManager`.** `PGSimpleDataSource`
  loads `org.postgresql.Driver` in `BaseDataSource`'s static
  initializer before it asks `DriverManager` for a connection. That
  can fix ServiceLoader visibility problems, but the connection path
  still uses `DriverManager`, so the driver class must be visible to
  the code calling `getConnection()`. This is the recommended path
  for application servers anyway; see
  [DataSource and JNDI](/documentation/connect/datasource/).
- **Explicitly register from the caller's classloader.** Calling
  `org.postgresql.Driver.register()` (or `Class.forName(...)` on the
  driver class, loaded by your classloader) re-registers the driver
  under your classloader and makes it visible.

## Related

- [Quick start](/documentation/getting-started/install/): the
  Maven / Gradle dependency declaration for the supported release
  lines.
- [JDBC URL](/documentation/connect/url-syntax/):
  full JDBC URL grammar.
- [DataSource and JNDI](/documentation/connect/datasource/):
  the `PGSimpleDataSource` path, including how it loads the pgJDBC
  driver before requesting a connection.
