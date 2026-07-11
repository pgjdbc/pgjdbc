/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

plugins {
    id("build-logic.java-library")
    id("build-logic.test-junit5")
}

// Differential backward-compatibility oracle. It drives the public JDBC API through the current driver
// and through a released baseline loaded in an isolated class loader, then compares the observable
// outcomes. The reusable core (src/main) loads the baseline under a class loader whose parent is the
// platform loader (reached as the application loader's parent), so java.sql.* resolves from the JDK while
// the baseline's org.postgresql.* comes only from its jar. That keeps the module at the default Java 8
// target, so other test modules (for example pgjdbc-jqf-test) can depend on it.

// The released baseline jar is not on any compile or runtime classpath: only its resolved path is handed
// to the test JVM, which loads it in an isolated class loader. Keeping it off the classpath is what lets
// both driver versions expose org.postgresql.* without a package clash.
val legacyDriver = configurations.dependencyScope("legacyDriver")
val legacyDriverClasspath = configurations.resolvable("legacyDriverClasspath") {
    extendsFrom(legacyDriver.get())
}

dependencies {
    legacyDriver(libs.pgjdbc.compat.baseline)

    // Nullness annotations for the reusable core; checker itself is opt-in via -PenableCheckerframework.
    implementation("org.checkerframework:checker-qual:3.55.1")

    // The current driver under test, in its shipped (shaded) shape.
    testImplementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }
    testImplementation(projects.testkit)
}

tasks.test {
    // Hand the baseline jar path to the test JVM. An explicit -Dpgjdbc.compat.legacyJar overrides the
    // resolved artifact, so a developer can point the oracle at any other build (e.g. a locally built
    // merge-base jar) without editing the version catalog.
    val override = providers.systemProperty("pgjdbc.compat.legacyJar")
    val resolved = legacyDriverClasspath.map { it.incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier && it.module == "postgresql" }
    }.files.singleFile.absolutePath }
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dpgjdbc.compat.legacyJar=${override.orNull ?: resolved.get()}")
    })
}
