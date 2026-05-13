/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

plugins {
    id("build-logic.java-library")
}

// docs-tools is a build-time-only utility module. It is NOT shipped with
// the driver jar; nothing here is on the runtime classpath of pgjdbc.
//
// It introspects the compiled `org.postgresql.PGProperty` enum via ASM
// and emits a YAML data file consumed by the Hugo docs build.

dependencies {
    // ASM reads the compiled .class file directly, without loading it into a
    // ClassLoader — so the docs generator works even if PGProperty pulls in
    // runtime dependencies (DriverInfo, GT, ...) that we don't want to drag
    // into the build classpath.
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-tree:9.9.1")
}

// Read the compiled bytecode of :postgresql so PGProperty.class exists.
val postgresqlMainClasses = files(
    project(":postgresql").layout.buildDirectory.dir("classes/java/main")
)

val generateProperties by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Read PGProperty annotations and (eventually) emit " +
        "docs/data/connection-properties.yaml."

    dependsOn(project(":postgresql").tasks.named("classes"))

    mainClass.set("org.postgresql.tools.docs.GenerateProperties")
    classpath = sourceSets.main.get().runtimeClasspath

    // Args: the path to the directory containing compiled main classes of
    // :postgresql, so the tool can locate PGProperty.class deterministically.
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(postgresqlMainClasses.singleFile.absolutePath)
    })

    // Print to stdout for now; YAML emission lands in a follow-up commit.
    standardOutput = System.out
    errorOutput = System.err
}
