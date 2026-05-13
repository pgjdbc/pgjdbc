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

val generateProperties by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Generate docs/data/connection-properties.yaml from " +
        "PGProperty annotations (@PgApi / @PgTags / @PgPropertyType)."

    dependsOn(project(":postgresql").tasks.named("classes"))

    mainClass.set("org.postgresql.tools.docs.GenerateProperties")
    classpath = sourceSets.main.get().runtimeClasspath

    // -PdocsAllowIncomplete=true lets the task succeed even when some
    // PGProperty values are missing annotations. Useful during the mass
    // annotation pass — the report still prints, but YAML emission
    // proceeds for the fully-annotated subset so docs can still preview.
    val allowIncomplete = providers.gradleProperty("docsAllowIncomplete")
        .map { it.toBoolean() }
        .orElse(false)

    argumentProviders.add(CommandLineArgumentProvider {
        val args = mutableListOf<String>()
        if (allowIncomplete.get()) args.add("--allow-incomplete")
        args.add(postgresqlMainClasses.singleFile.absolutePath)
        args.add(connectionPropertiesYaml.asFile.absolutePath)
        args
    })

    inputs.files(postgresqlMainClasses).withPropertyName("pgPropertyClasses")
    inputs.property("docsAllowIncomplete", allowIncomplete)
    outputs.file(connectionPropertiesYaml).withPropertyName("connectionPropertiesYaml")

    standardOutput = System.out
    errorOutput = System.err
}
