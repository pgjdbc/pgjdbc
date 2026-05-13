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
    // ASM reads CLASS-retention annotations directly from the compiled
    // .class file (these are not visible to reflection at runtime).
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-tree:9.9.1")

    // Plain runtime metadata of PGProperty (name, default, description,
    // choices, required) lives in normal Java fields, so we read those via
    // reflection — much simpler than reverse-engineering them from <clinit>
    // bytecode. PGProperty's static initialiser only builds a HashMap and
    // is safe to trigger.
    implementation(projects.postgresql)
}

// Compiled bytecode of :postgresql, where PGProperty.class lives.
val postgresqlMainClasses = files(
    project(":postgresql").layout.buildDirectory.dir("classes/java/main")
)

// Target YAML consumed by the Hugo `param-table` shortcode.
val connectionPropertiesYaml =
    isolated.rootProject.projectDirectory.dir("docs/data")
        .file("connection-properties.yaml")

// Shared configuration for the strict and the allow-incomplete variants.
fun JavaExec.configureGenerator(allowIncomplete: Boolean) {
    group = "documentation"
    dependsOn(project(":postgresql").tasks.named("classes"))

    mainClass.set("org.postgresql.tools.docs.GenerateProperties")
    classpath = sourceSets.main.get().runtimeClasspath

    argumentProviders.add(CommandLineArgumentProvider {
        val a = mutableListOf<String>()
        if (allowIncomplete) a.add("--allow-incomplete")
        a.add(postgresqlMainClasses.singleFile.absolutePath)
        a.add(connectionPropertiesYaml.asFile.absolutePath)
        a
    })

    inputs.files(postgresqlMainClasses).withPropertyName("pgPropertyClasses")
    inputs.property("allowIncomplete", allowIncomplete)
    outputs.file(connectionPropertiesYaml).withPropertyName("connectionPropertiesYaml")

    standardOutput = System.out
    errorOutput = System.err
}

// Strict: fails the build on any incomplete or inconsistent annotation.
// Wired into the production `docsBuild` task and CI.
val generateProperties by tasks.registering(JavaExec::class) {
    description = "Generate docs/data/connection-properties.yaml from " +
        "PGProperty annotations (@PgApi / @PgTags / @PgPropertyType). Strict mode."
    configureGenerator(allowIncomplete = false)
}

// Lenient: emits YAML for whatever IS fully annotated, prints the fail
// report for the rest. Wired into `docsServe` so contributors can keep
// previewing the site while annotating more properties.
val generatePropertiesPartial by tasks.registering(JavaExec::class) {
    description = "Like `generateProperties` but emits YAML for fully-" +
        "annotated properties even when others are still incomplete."
    configureGenerator(allowIncomplete = true)
}

// ----- Hugo wrappers -------------------------------------------------------
//
// docsBuild  — strict production build: regenerate connection-properties.yaml
//              from PGProperty annotations, then run a one-shot Hugo build.
//              Fails if any PGProperty value has incomplete annotations.
//
// docsServe  — local dev server: regenerate with --allow-incomplete (so
//              the preview works mid-mass-annotation), then start the Hugo
//              dev server with hot-reload.
//
// Both require the `hugo` binary on PATH. The doFirst hook fails with a
// readable error if it isn't.

val docsDir = isolated.rootProject.projectDirectory.dir("docs").asFile

fun Exec.ensureHugoOnPath() {
    doFirst {
        try {
            val rc = ProcessBuilder("hugo", "version")
                .redirectErrorStream(true)
                .start()
                .also { it.waitFor() }
                .exitValue()
            if (rc != 0) {
                throw GradleException("`hugo version` returned exit code $rc.")
            }
        } catch (e: java.io.IOException) {
            throw GradleException("hugo binary is not on PATH. " +
                "Install Hugo (extended) — see https://gohugo.io/installation/. " +
                "Underlying error: ${e.message}")
        }
    }
}

val docsBuild by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Regenerate connection-properties.yaml and run a " +
        "production Hugo build into docs/public."
    dependsOn(generateProperties)
    workingDir = docsDir
    commandLine("hugo", "--gc", "--minify")
    ensureHugoOnPath()
}

val docsServe by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Start the Hugo dev server with hot-reload " +
        "(connection-properties.yaml regenerated in allow-incomplete mode)."
    dependsOn(generatePropertiesPartial)
    workingDir = docsDir

    // -PdocsPort=NNNN overrides the default 1313.
    val port = providers.gradleProperty("docsPort").orElse("1313")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("server", "--bind", "127.0.0.1", "--port", port.get(),
               "--disableFastRender")
    })
    commandLine("hugo")
    ensureHugoOnPath()
}
