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

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

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

    // snakeyaml drives the release-history YAML emitter and parses the
    // hand-maintained release-history-overlay.yaml.
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

val generateReleaseHistory by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Generate docs/data/release-history.yaml from git refs " +
        "(release/* branches, REL* tags) + release-history-overlay.yaml."

    commandLine("bash", projectRoot.resolve("docs-tools/bin/generate-release-history.sh").absolutePath)
    workingDir = projectRoot

    // The git directory's content drives the output, but Gradle cannot
    // track .git efficiently — declare the overlay as the only file input
    // and mark the task non-cacheable on the git side via outputs.upToDateWhen.
    inputs.file(releaseHistoryOverlay).withPropertyName("overlay")
    outputs.file(releaseHistoryYaml).withPropertyName("releaseHistoryYaml")
    outputs.upToDateWhen { false }
}

// ----- Hugo wrappers -------------------------------------------------------
//
// buildDocs  — production build: regenerate release-history.yaml, then
//              run a one-shot Hugo build.
//
// serveDocs  — local dev server: regenerate release-history.yaml, then
//              start the Hugo dev server with hot-reload.
//
// Both require a recent extended Hugo on PATH. The doFirst hook fails
// with a readable error if Hugo is missing, too old, or not the
// extended build.

val docsDir = isolated.rootProject.projectDirectory.dir("docs").asFile

// The templates track Hugo's current API rather than its deprecated
// surface. site.Language.Locale arrived in 0.158.0 (the release that
// deprecated the older .LanguageCode) and hugo.Data in 0.156.0, so a
// fixed floor is unavoidable. We pin above both and refuse to fall
// back to deprecated APIs: contributors upgrade Hugo instead. Raise
// this when the templates adopt a newer API; do not lower it to
// accommodate an old local install.
val minHugoVersion = "0.161.0"

fun Exec.requireHugo() {
    doFirst {
        val versionLine = try {
            val process = ProcessBuilder("hugo", "version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (process.exitValue() != 0) {
                throw GradleException(
                    "`hugo version` returned exit code ${process.exitValue()}:\n$output"
                )
            }
            output.trim()
        } catch (e: java.io.IOException) {
            throw GradleException(
                "hugo binary is not on PATH. Install the extended Hugo " +
                    "$minHugoVersion or newer: https://gohugo.io/installation/. " +
                    "Underlying error: ${e.message}"
            )
        }

        // `hugo version` prints e.g.
        //   hugo v0.161.1+extended+withdeploy darwin/arm64 BuildDate=...
        val match = Regex("""v(\d+)\.(\d+)\.(\d+)""").find(versionLine)
            ?: throw GradleException("Could not parse a Hugo version from: $versionLine")
        val found = match.destructured.toList().map(String::toInt)
        val required = minHugoVersion.split(".").map(String::toInt)
        val tooOld = found.zip(required)
            .firstOrNull { (f, r) -> f != r }
            ?.let { (f, r) -> f < r }
            ?: false
        if (tooOld) {
            throw GradleException(
                "This docs build needs the extended Hugo $minHugoVersion or " +
                    "newer, but found ${match.value.removePrefix("v")}. The " +
                    "templates use site.Language.Locale (Hugo 0.158.0+) and " +
                    "hugo.Data (0.156.0+); we track the current API rather than " +
                    "Hugo's deprecated surface. Upgrade Hugo: " +
                    "https://gohugo.io/installation/."
            )
        }

        // SCSS under docs/assets/sass/ is transpiled by Hugo Pipes, which
        // only the extended build ships. A standard build fails mid-render
        // with a less obvious message, so reject it up front.
        if (!versionLine.contains("+extended")) {
            throw GradleException(
                "This docs build needs the *extended* Hugo build (SCSS " +
                    "support), but found a standard build: $versionLine. " +
                    "Reinstall the extended edition: " +
                    "https://gohugo.io/installation/."
            )
        }
    }
}

val buildDocs by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Regenerate release-history.yaml, then run a production " +
        "Hugo build into docs/public."
    dependsOn(generateReleaseHistory)
    workingDir = docsDir

    // -PhugoBaseURL=… (or env HUGO_BASEURL) → `hugo --baseURL <value>`.
    // The CLI flag wins over both config.toml and Hugo's env-override
    // machinery, which has been unreliable for top-level keys since the
    // 0.123 config rewrite. CI uses this to point a fork's deploy at
    // <owner>.github.io/<repo>/ without editing config.toml; locally it
    // can be set with `./gradlew :docs-tools:buildDocs -PhugoBaseURL=…`.
    val hugoBaseUrl = providers.gradleProperty("hugoBaseURL")
        .orElse(providers.environmentVariable("HUGO_BASEURL"))
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            add("--gc")
            add("--minify")
            if (hugoBaseUrl.isPresent) {
                add("--baseURL")
                add(hugoBaseUrl.get())
            }
        }
    })
    commandLine("hugo")
    requireHugo()
}

val serveDocs by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Start the Hugo dev server with hot-reload " +
        "(release-history.yaml regenerated from git refs)."
    dependsOn(generateReleaseHistory)
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
    requireHugo()
}

// Lints source files for site-internal links that bypass Hugo's
// BasePath. Three categories of mistake, all of which produce HTML
// that breaks on a project-page deploy (https://<owner>.github.io/<repo>/)
// but happens to "work" on https://jdbc.postgresql.org/ because the
// BasePath is empty there:
//
//   1. Raw `href="/foo"` / `src="/foo"` in templates, not wrapped in
//      `| relURL`. Hugo emits the path verbatim, missing `/<repo>/`.
//
//   2. `{{ "/foo" | relURL }}` with a leading slash. Hugo's relURL
//      deliberately treats `/`-prefixed inputs as "you already know
//      the absolute path" and does NOT prepend BasePath — so the
//      result is identical to (1).
//
//   3. `url = "/foo"` in TOML / `url: "/foo"` in YAML data. The
//      consumer template pipes the value through `| relURL`, but
//      because the data still starts with `/`, the relURL is a no-op
//      (same trap as (2)) — and the data file is then a quiet failure
//      mode that's invisible from the template.
//
// Markdown content (`docs/content/**/*.md`) is intentionally NOT
// linted: root-relative markdown links like `[text](/foo)` are the
// idiomatic source-of-truth shape, and the `_default/_markup/render-link.html`
// hook re-runs them through relURL at render time. We're betting that
// no contributor will hand-write `<a href="/foo">` as raw HTML inside
// a .md file; if that bet breaks, add a fourth check here.
val lintDocsLinks by tasks.registering {
    group = "verification"
    description = "Lint docs sources for site-internal links that " +
            "bypass Hugo's BasePath (root-relative href/src, leading-slash " +
            "relURL arguments, `/`-prefixed URLs in data files). Run as a " +
            "dependency of :docs-tools:buildDocs."
    dependsOn(buildDocs)

    val rootDirAbs = isolated.rootProject.projectDirectory
    val layoutsDir = rootDirAbs.dir("docs/layouts").asFile
    val dataDir = rootDirAbs.dir("docs/data").asFile
    val menusFile = rootDirAbs.file("docs/config/_default/menus.toml").asFile
    val repoRoot = rootDirAbs.asFile

    inputs.dir(layoutsDir).withPropertyName("layouts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(dataDir).withPropertyName("data")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(menusFile).withPropertyName("menus")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    val stamp = layout.buildDirectory.file("lint-docs-links.stamp")
    outputs.file(stamp)

    doLast {
        val problems = mutableListOf<String>()

        // Anchor on the literal `"` that opens the attribute value: if
        // the very next char is `/` and the one after isn't `/` (so
        // protocol-relative `//host` doesn't match), the value Hugo
        // emits will start with `/` verbatim. Crucially this does NOT
        // match `href="{{ $repo }}/edit/…"` — the first char inside
        // the quotes is `{`, not `/` — so multi-piece templates that
        // happen to interpolate a `/`-prefixed tail stay clean.
        val rawRootAttr = Regex("""\b(href|src)\s*=\s*"(/[a-zA-Z0-9_\-][^"]*)"""")
        // `{{ "/foo" | relURL }}` — leading `/` in the relURL arg
        // makes Hugo skip BasePath.
        val relURLLeadingSlash = Regex(
            """\{\{[^}]*"\s*/[a-zA-Z0-9_\-][^"]*"[^}]*\|\s*[^}]*relURL[^}]*\}\}"""
        )
        // Multi-line Hugo comments: `{{- /* … */ -}}`. The single-line
        // strip can't cover these; track open/close across lines and
        // blank out the spanning region before matching.
        val commentOpen = Regex("""\{\{-?\s*/\*""")
        val commentClose = Regex("""\*/\s*-?\}\}""")

        // render-link.html itself analyzes `/`-prefixed strings as its
        // input, so the linter has to skip it (otherwise it'd flag the
        // hook that fixes the very problem we're guarding against).
        val renderLinkRelative = "_default/_markup/render-link.html"

        layoutsDir.walkTopDown()
            .filter { it.isFile && it.extension == "html" }
            .filter {
                !it.toRelativeString(layoutsDir).replace(File.separatorChar, '/')
                    .equals(renderLinkRelative)
            }
            .forEach { f ->
                val rel = f.relativeTo(repoRoot).invariantSeparatorsPath
                var inComment = false
                f.useLines { lines ->
                    lines.forEachIndexed { idx, rawLine ->
                        var line = rawLine
                        if (inComment) {
                            val close = commentClose.find(line)
                            if (close != null) {
                                line = line.substring(close.range.last + 1)
                                inComment = false
                            } else {
                                return@forEachIndexed
                            }
                        }
                        commentOpen.find(line)?.let { open ->
                            val tail = line.substring(open.range.first)
                            val close = commentClose.find(tail)
                            if (close != null) {
                                line = line.substring(0, open.range.first) +
                                        tail.substring(close.range.last + 1)
                            } else {
                                line = line.substring(0, open.range.first)
                                inComment = true
                            }
                        }

                        rawRootAttr.findAll(line).forEach { m ->
                            val attr = m.groupValues[1]
                            val path = m.groupValues[2]
                            problems += "$rel:${idx + 1}: ${m.value} — " +
                                    "root-relative `$attr` bypasses Hugo's BasePath. " +
                                    "Wrap in `{{ \"${path.trimStart('/')}\" | relURL }}`."
                        }
                        relURLLeadingSlash.findAll(line).forEach { m ->
                            problems += "$rel:${idx + 1}: ${m.value} — " +
                                    "leading `/` in relURL arg; strip it so Hugo " +
                                    "prepends BasePath."
                        }
                    }
                }
            }

        // Data files: bare `/foo` in `url = …` (TOML) / `url: …` (YAML).
        val tomlRootURL = Regex("""^\s*url\s*=\s*"(/[a-zA-Z0-9_\-][^"]*)"""")
        val yamlRootURL = Regex("""^\s*url\s*:\s*"?(/[a-zA-Z0-9_\-][^"\s]*)""")
        val dataFiles = sequence {
            yield(menusFile)
            dataDir.walkTopDown().filter { it.isFile }.forEach { yield(it) }
        }
        dataFiles.filter { it.exists() }.forEach { f ->
            val rel = f.relativeTo(repoRoot).invariantSeparatorsPath
            val re = when (f.extension.lowercase()) {
                "toml" -> tomlRootURL
                "yaml", "yml" -> yamlRootURL
                else -> return@forEach
            }
            f.useLines { lines ->
                lines.forEachIndexed { idx, line ->
                    re.find(line)?.let { m ->
                        problems += "$rel:${idx + 1}: ${m.value.trim()} — " +
                                "strip leading `/` so the consumer's `| relURL` " +
                                "prepends BasePath."
                    }
                }
            }
        }

        val stampFile = stamp.get().asFile
        if (problems.isNotEmpty()) {
            stampFile.delete()
            throw GradleException(buildString {
                appendLine(
                    "Found ${problems.size} root-relative link(s) that " +
                            "bypass Hugo's BasePath:"
                )
                problems.forEach { appendLine("  $it") }
                appendLine()
                appendLine(
                    "Site-internal links must go through `relURL` so they " +
                            "include the repo prefix on project-page deploys " +
                            "(e.g. https://<owner>.github.io/<repo>/). Markdown " +
                            "content is handled by the render-link hook; " +
                            "templates and data files need the fix at the source."
                )
            })
        }
        stampFile.parentFile.mkdirs()
        stampFile.writeText("OK\n")
    }
}

tasks.check {
    dependsOn(lintDocsLinks)
}
