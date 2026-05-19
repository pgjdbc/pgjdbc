---
title: "Download"
date: 2022-06-20T01:17:28+05:30
draft: false
---

Binary JAR downloads of the JDBC driver are available below. The current release is also published to [Maven Central](https://central.sonatype.com/artifact/org.postgresql/postgresql), so most projects pull it through their build tool instead of dropping a JAR on the classpath — see the [Quick start](/documentation/getting-started/install/) for the dependency snippets. Development [SNAPSHOT](https://central.sonatype.com/repository/maven-snapshots/org/postgresql/postgresql/maven-metadata.xml) builds are published to the Maven snapshots repository.

## Latest Versions

The Java 8 card below is the driver you should use unless you have unusual requirements (running old applications or JVMs). It requires Java 8 or newer and supports PostgreSQL 9.1 or newer; releases since 42.7.4 are not guaranteed to work with older servers. The Java 7 and Java 6 cards are legacy compatibility builds for applications that cannot upgrade their JVM — see [Compatibility](/documentation/getting-started/compatibility/) for the support status of each release line.

{{< recent-versions >}}

## Older Versions

Earlier patches of the current line, the latest patch of each prior release line (42.6.x through 42.3.x), and the prior `.jre7` build. Use one of the cards above unless you need a specific version.

{{< past-versions >}}
