---
title: "Building the Driver from Source"
date: 2026-05-13T00:00:00Z
draft: false
weight: 10
toc: true
last_reviewed: "2026-05-22"
aliases:
    - "/documentation/setup/#getting-the-driver/"
---

You normally do not need to build pgJDBC yourself. Released jars are available on [Maven Central](https://repo1.maven.org/maven2/org/postgresql/postgresql/). Build from source if you are modifying the driver, packaging it for a Linux distribution, or reproducing a specific commit.

The build is driven by the bundled Gradle Wrapper (`./gradlew` or `gradlew.bat`), so a separate Gradle installation is not required.

## Requirements

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- settings.gradle.kts | settings.gradle.kts | 28-30
- build-parameters | build-logic/build-parameters/build.gradle.kts | 19-44
- Java compiler release | build-logic/jvm/src/main/kotlin/build-logic.java.gradle.kts | 54-66
{{< /review >}}

* A Git client.
* **Java 17 or newer** to launch Gradle itself. `settings.gradle.kts` rejects older JDKs.
* For the actual compilation pgJDBC uses [Gradle toolchains](https://docs.gradle.org/current/userguide/toolchains.html); by default the build expects JDK 21 (`jdkBuildVersion=21`) and Gradle will auto-provision it if not found locally.
* The produced jar targets **Java 8** by default (`targetJavaVersion=8`), so the artifact runs on Java 8 and above.

To override the build or test JDK, pass `-PjdkBuildVersion=...` / `-PjdkTestVersion=...`. See [CONTRIBUTING.md, Build requirements](https://github.com/pgjdbc/pgjdbc/blob/master/CONTRIBUTING.md#build-requirements) for the full list of build parameters.

## Building the driver

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- Gradle build workflow | CONTRIBUTING.md | 104-127
- project version | build.gradle.kts | 31-35
- pgjdbc project directory | settings.gradle.kts | 48-52
- release version property | gradle.properties | 14-17
{{< /review >}}

To compile pgJDBC and run the tests:

```
./gradlew build
```

To assemble the jars without running tests:

```
./gradlew assemble          # build the artifacts only
./gradlew build -x test     # build, verify code style, skip tests
```

The compiled jar is placed in `pgjdbc/build/libs/postgresql-<version>.jar`. For example, `postgresql-42.7.12.jar` for a release build, or `postgresql-42.7.12-SNAPSHOT.jar` while developing against an unreleased version.

For the full Gradle workflow (running selected tests, code-style checks, ErrorProne / CheckerFramework, translations, the release procedure), see [CONTRIBUTING.md](https://github.com/pgjdbc/pgjdbc/blob/master/CONTRIBUTING.md).

## Building with Maven from the source distribution

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- sourceDistribution | pgjdbc/build.gradle.kts | 334-421
- Maven source build verification | pgjdbc/build.gradle.kts | 423-443
- source distribution publication | pgjdbc/build.gradle.kts | 452-455
- reduced Maven build | pgjdbc/reduced-pom.xml | 29-42
{{< /review >}}

Where Gradle is not available, typically when packaging pgJDBC for a Linux distribution, the Gradle build can emit a convenience Maven-based source release. The Maven project is a **trimmed** variant of pgJDBC: OSGi metadata generation, the native Windows SSPI sources, and the OSGi / SSPI / replication test suites are excluded so that the project can be built with stock Maven. The resulting jar is suitable for runtime use as a JDBC driver.

Produce the archive with:

```
./gradlew :postgresql:sourceDistribution -Prelease -Psigning.pgp.enabled=OFF
```

The archive `postgresql-<version>-jdbc-src.tar.gz` is placed in `pgjdbc/build/distributions/`. After extracting it, build the driver with Maven:

```
mvn package                # or, to skip tests:
mvn -DskipTests package
```

The `*-jdbc-src.tar.gz` archives are also published to [Maven Central](https://repo1.maven.org/maven2/org/postgresql/postgresql/) alongside each release.
