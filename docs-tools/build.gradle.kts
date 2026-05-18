/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("build-logic.java-library")
    id("build-logic.test-junit5")
    id("org.jetbrains.kotlin.jvm")
}

// docs-tools is a build-time-only utility module. It is NOT shipped with
// the driver jar; nothing here is on the runtime classpath of pgjdbc.
//
// It introspects the compiled `org.postgresql.PGProperty` enum via ASM
// and emits a YAML data file consumed by the Hugo docs build.

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // ASM reads CLASS-retention annotations directly from the compiled
    // .class file (these are not visible to reflection at runtime).
    implementation(platform("org.ow2.asm:asm-bom:9.9.1"))
    implementation("org.ow2.asm:asm")
    implementation("org.ow2.asm:asm-tree")

    // Plain runtime metadata of PGProperty (name, default, description,
    // choices, required) lives in normal Java fields, so we read those via
    // reflection — much simpler than reverse-engineering them from <clinit>
    // bytecode. PGProperty's static initialiser only builds a HashMap and
    // is safe to trigger.
    implementation(projects.postgresql)

    // GenerateReleaseHistory walks git refs (release/*.x branches and
    // REL42.* tags). JGit 7.x has first-class git-worktree support
    // (commondir indirection) and a typed API — preferable to shelling
    // out to `git`. JGit 7.x bytecode targets Java 17; pgjdbc builds on
    // JDK 21, so the runtime is fine.
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.5.0.202512021534-r")

    // SLF4J binding for JGit. Without one, SLF4J 2.x prints a per-JVM
    // "No SLF4J providers were found" warning and silently routes JGit's
    // diagnostics to NOP. slf4j-simple writes to stderr with no config
    // file; tune via -Dorg.slf4j.simpleLogger.defaultLogLevel=warn.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    // snakeyaml drives both YAML emitters (connection-properties output
    // and release-history output) and parses the hand-maintained
    // release-history-overlay.yaml.
    implementation("org.yaml:snakeyaml:2.2")
}

// pgjdbc targets Java 8 bytecode via --release 8; pin Kotlin to the same
// jvmTarget so the two compilers agree (Gradle aborts on a mismatch).
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

// docs-tools runs at build time on the project's buildJdk (Java 17+ in
// practice — JGit 7.x already requires Java 17 at runtime). Its tests
// freely use post-Java-8 APIs (e.g. ByteArrayOutputStream#toString(Charset),
// since Java 10), so they cannot run on a Java 8 test toolchain even though
// the produced bytecode is JVM 1.8.
tasks.test {
    onlyIf("docs-tools tests use post-Java-8 APIs (e.g. ByteArrayOutputStream#toString(Charset))") {
        buildParameters.testJdkVersion > 8
    }
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
    // :postgresql:classes builds PGProperty.class we read by ASM;
    // :docs-tools:classes (implicit via runtimeClasspath) builds our own
    // Kotlin entrypoint plus the jandex index that Gradle 9 expects on
    // the classpath but cannot infer as a dependency on its own.
    dependsOn(":postgresql:classes")
    dependsOn("classes")
    dependsOn("processJandexIndex")

    mainClass.set("org.postgresql.tools.docs.GenerateProperties")
    classpath = sourceSets.main.get().runtimeClasspath

    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            if (allowIncomplete) {
                add("--allow-incomplete")
            }
            add(postgresqlMainClasses.singleFile.absolutePath)
            add(connectionPropertiesYaml.asFile.absolutePath)
        }
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

// ===== generateReleaseHistory ============================================
//
// Scans the local clone's `release/<NN>.x` branches and `REL<NN>` tags,
// merges with docs/data/release-history-overlay.yaml, emits
// docs/data/release-history.yaml. Consumed by the `release-history`
// Hugo shortcode on the compatibility page.
//
// For CI to produce a complete table the checkout must include all
// `REL42.*` tags and the `release/42.*.x` branches (full-history clone
// or an explicit `git fetch --tags`).
// We pass the project root (where `.git` lives, as either a directory in
// a regular clone or a pointer file in a git worktree); JGit's findGitDir()
// walks up from there and follows the pointer in the worktree case.
val projectRoot = isolated.rootProject.projectDirectory.asFile
val releaseHistoryOverlay =
    isolated.rootProject.projectDirectory.dir("docs/data")
        .file("release-history-overlay.yaml")
val releaseHistoryYaml =
    isolated.rootProject.projectDirectory.dir("docs/data")
        .file("release-history.yaml")

val generateReleaseHistory by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Generate docs/data/release-history.yaml from git refs " +
        "(release/* branches, REL* tags) + release-history-overlay.yaml."

    mainClass.set("org.postgresql.tools.docs.GenerateReleaseHistory")
    classpath = sourceSets.main.get().runtimeClasspath
    // See configureGenerator for the rationale on these dependencies.
    dependsOn(tasks.named("classes"))
    dependsOn(tasks.named("processJandexIndex"))

    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            projectRoot.absolutePath,
            releaseHistoryOverlay.asFile.absolutePath,
            releaseHistoryYaml.asFile.absolutePath
        )
    })

    // The git directory's content drives the output, but Gradle cannot
    // track .git efficiently — declare the overlay as the only file input
    // and mark the task non-cacheable on the git side via outputs.upToDateWhen.
    inputs.file(releaseHistoryOverlay).withPropertyName("overlay")
    outputs.file(releaseHistoryYaml).withPropertyName("releaseHistoryYaml")
    outputs.upToDateWhen { false }

    standardOutput = System.out
    errorOutput = System.err
}

// ===== fetchKnownCves ====================================================
//
// Cross-references local git tags with `gh api repos/<repo>/security-advisories`
// and emits docs/data/known-cves.yaml, keyed by pgjdbc version. The
// `layouts/changelogs/single.html` Hugo template looks up the current
// page's `version` in that data file and renders a "known CVEs" banner
// at the top of the changelog body when any apply.
//
// Network-dependent (calls `gh`). The fetcher is best-effort and degrades
// to an empty advisory list on offline / unauthenticated / rate-limited
// runs, so the build does not break — the banner just won't appear for
// affected versions until the file is regenerated.
val knownCvesYaml =
    isolated.rootProject.projectDirectory.dir("docs/data")
        .file("known-cves.yaml")

val fetchKnownCves by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Generate docs/data/known-cves.yaml: per-version known " +
        "CVE list cross-referenced against GitHub Security Advisories."

    mainClass.set("org.postgresql.tools.docs.FetchKnownCves")
    classpath = sourceSets.main.get().runtimeClasspath
    dependsOn(tasks.named("classes"))
    dependsOn(tasks.named("processJandexIndex"))

    argumentProviders.add(CommandLineArgumentProvider {
        listOf(projectRoot.absolutePath, knownCvesYaml.asFile.absolutePath)
    })

    outputs.file(knownCvesYaml).withPropertyName("knownCvesYaml")
    outputs.upToDateWhen { false }

    standardOutput = System.out
    errorOutput = System.err
}

// ----- Hugo wrappers -------------------------------------------------------
//
// docsBuild  — strict production build: regenerate connection-properties.yaml
//              and release-history.yaml, then run a one-shot Hugo build.
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
    description = "Regenerate connection-properties.yaml and " +
        "release-history.yaml, then run a production Hugo build into " +
        "docs/public."
    dependsOn(generateProperties)
    dependsOn(generateReleaseHistory)
    dependsOn(fetchKnownCves)
    workingDir = docsDir
    commandLine("hugo", "--gc", "--minify")
    ensureHugoOnPath()
}

val docsServe by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Start the Hugo dev server with hot-reload " +
        "(connection-properties.yaml regenerated in allow-incomplete mode; " +
        "release-history.yaml regenerated from git refs)."
    dependsOn(generatePropertiesPartial)
    dependsOn(generateReleaseHistory)
    dependsOn(fetchKnownCves)
    workingDir = docsDir

    // -PdocsPort=NNNN overrides the default 1313.
    val port = providers.gradleProperty("docsPort").orElse("1313")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "server", "--bind", "127.0.0.1", "--port", port.get(),
            "--disableFastRender"
        )
    })
    commandLine("hugo")
    ensureHugoOnPath()
}
