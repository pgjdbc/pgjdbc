/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

@file:JvmName("GenerateReleaseHistory")

package org.postgresql.tools.docs

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Generates `docs/data/release-history.yaml` from local git tags,
 * merged with the hand-maintained `docs/data/release-history-overlay.yaml`.
 *
 * The output is a flat `rows` list with three columns per row:
 *  - `release_line` (e.g. `"42.7.x"`),
 *  - `version_range` (`"42.7.0-42.7.11"` for a line's main row,
 *    `"42.2.29.jre7"` for a classifier row),
 *  - `released` (ISO date of the latest tag in the row).
 *
 * Consumers: the `recent-versions` and `past-versions` Hugo shortcodes
 * on the `/download/` page.
 *
 * The overlay carries only what cannot be derived from git: the list
 * of `.jre6` / `.jre7` classifier builds (id, branch, last_version).
 */

/** Release tag pattern — RC tags (REL42.7.11-rc1 etc.) are excluded. */
private val TAG_PATTERN = Regex("""^REL(42\.\d+\.\d+)$""")

fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println(
            "usage: GenerateReleaseHistory <project-root> <overlay-yaml> <output-yaml>"
        )
        exitProcess(2)
    }
    val projectRoot = Paths.get(args[0])
    val overlayYaml = Paths.get(args[1])
    val outputYaml = Paths.get(args[2])

    val facts = collectGitFacts(projectRoot)
    val overlay = OverlayLoader.load(overlayYaml)
    val rows = buildRows(facts, overlay)
    ReleaseHistoryYamlEmitter.writeTo(rows, outputYaml)
    println("Wrote ${rows.size} release-history rows to $outputYaml")
}

/* ===== Model =========================================================== */

internal data class TaggedRelease(val version: String, val commitDate: String)

internal data class GitFacts(
    /** Branch ids, sorted descending: ["42.7.x", "42.6.x", …, "42.2.x"]. */
    val branchIds: List<String>,
    val tagsByBranch: Map<String, List<TaggedRelease>>,
)

/** One `.jre6` / `.jre7` classifier build. Emitted as an extra row under
 *  its parent line. The `id` becomes the version-range suffix
 *  (`<lastVersion>.<id>`); the template derives the Java version from
 *  the suffix (`jre7` -> 7). */
internal data class Classifier(
    val branch: String,
    val lastVersion: String,
    val id: String,
)

internal data class Overlay(
    val classifiers: List<Classifier> = emptyList(),
)

internal data class Row(
    val releaseLine: String,
    val versionRange: String,
    val released: String,
)

/* ===== Git scan ======================================================== */

/**
 * Runs `git tag` in [projectRoot] and returns release tags with their dates.
 * This replaces the JGit implementation: the only git operation needed is
 * enumerating REL42.* tags with their creator dates, which `git tag` handles
 * directly without requiring git history traversal.
 */
internal fun collectGitFacts(projectRoot: Path): GitFacts =
    collectGitFacts(runGitTag(projectRoot))

internal fun collectGitFacts(tagLines: List<String>): GitFacts {
    val tagsByBranch = mutableMapOf<String, MutableList<TaggedRelease>>()
    for (line in tagLines) {
        val parts = line.split('\t')
        if (parts.size != 2) continue
        val tag = parts[0].trim()
        val date = parts[1].trim()
        val match = TAG_PATTERN.matchEntire(tag) ?: continue
        val version = match.groupValues[1]
        val branchId = toBranchId(version)
        tagsByBranch.getOrPut(branchId, ::mutableListOf)
            .add(TaggedRelease(version, date))
    }
    tagsByBranch.values.forEach { list -> list.sortBy { versionKey(it.version) } }

    val branchIds = tagsByBranch.keys.sortedWith(versionDescending())
    val orderedTags = branchIds.associateWith { tagsByBranch[it].orEmpty() }
    return GitFacts(branchIds, orderedTags)
}

/** Runs `git tag --sort=version:refname --format=...` and returns stdout lines. */
internal fun runGitTag(projectRoot: Path): List<String> {
    val process = ProcessBuilder(
        "git", "tag",
        "--sort=version:refname",
        "--format=%(refname:strip=2)\t%(creatordate:format:%Y-%m-%d)",
        "REL42.*"
    )
        .directory(projectRoot.toFile())
        .redirectErrorStream(false)
        .start()

    val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readLines()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        val err = process.errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
        throw IOException("git tag exited with $exitCode: $err")
    }
    return output
}

private fun toBranchId(version: String): String {
    val second = version.indexOf('.', version.indexOf('.') + 1)
    return version.substring(0, second) + ".x"
}

/* ===== Row building =================================================== */

/**
 * One row per release line spanning its first..last tag, plus one
 * extra row per classifier listed in the overlay. Rows within a
 * release line are sorted by `released` descending so the latest
 * classifier appears next to the line's main row.
 */
internal fun buildRows(facts: GitFacts, overlay: Overlay): List<Row> {
    val classifiersByBranch = overlay.classifiers.groupBy { it.branch }
    val out = mutableListOf<Row>()
    for (branchId in facts.branchIds) {
        val tags = facts.tagsByBranch[branchId].orEmpty()
        if (tags.isEmpty()) continue

        val first = tags.first()
        val last = tags.last()
        val branchRows = mutableListOf<Row>()
        branchRows += Row(
            releaseLine = branchId,
            versionRange = formatRange(first.version, last.version),
            released = last.commitDate,
        )

        for (c in classifiersByBranch[branchId].orEmpty()) {
            val date = tags.firstOrNull { it.version == c.lastVersion }?.commitDate.orEmpty()
            branchRows += Row(
                releaseLine = branchId,
                versionRange = "${c.lastVersion}.${c.id}",
                released = date,
            )
        }

        // sortByDescending is stable, so a classifier with the same
        // date as the line's main row stays after it (main row goes in
        // first, classifier second).
        branchRows.sortByDescending { it.released }
        out += branchRows
    }
    return out
}

private fun formatRange(first: String, last: String): String =
    if (first == last) last else "$first-$last"

/* ===== Version helpers ================================================ */

internal fun versionKey(version: String): Long {
    val parts = version.split('.').take(5)
    val padded = parts + List(5 - parts.size) { "0" }
    return padded.fold(0L) { acc, raw ->
        val part = if (raw == "x") "0" else raw
        acc * 1000 + (part.toLongOrNull() ?: 0L)
    }
}

internal fun versionDescending(): Comparator<String> =
    compareByDescending(::versionKey)

/* ===== Overlay loader ================================================= */

/**
 * Parses the hand-maintained release-history-overlay.yaml.
 * The file format is intentionally simple (flat list of classifier entries)
 * so a line-by-line parser is sufficient and avoids a snakeyaml dependency.
 */
internal object OverlayLoader {
    fun load(file: Path): Overlay {
        if (!Files.isRegularFile(file)) {
            System.err.println("Overlay not found at $file — proceeding with no overlay data.")
            return Overlay()
        }
        val classifiers = mutableListOf<Classifier>()
        var id = ""
        var branch = ""
        var lastVersion = ""
        for (line in Files.readAllLines(file, StandardCharsets.UTF_8)) {
            when {
                line.trimStart().startsWith("- id:") -> {
                    if (id.isNotEmpty() && branch.isNotEmpty() && lastVersion.isNotEmpty()) {
                        classifiers += Classifier(branch, lastVersion, id)
                    }
                    id = line.substringAfter("id:").trim()
                    branch = ""
                    lastVersion = ""
                }
                line.trimStart().startsWith("branch:") ->
                    branch = line.substringAfter("branch:").trim()
                line.trimStart().startsWith("last_version:") ->
                    lastVersion = line.substringAfter("last_version:").trim()
            }
        }
        if (id.isNotEmpty() && branch.isNotEmpty() && lastVersion.isNotEmpty()) {
            classifiers += Classifier(branch, lastVersion, id)
        }
        return Overlay(classifiers)
    }
}

/* ===== YAML emitter =================================================== */

internal object ReleaseHistoryYamlEmitter {
    fun writeTo(rows: List<Row>, output: Path) {
        Files.createDirectories(output.parent)
        Files.newBufferedWriter(output, StandardCharsets.UTF_8).use { w ->
            w.write("# Generated by :docs-tools:generateReleaseHistory — DO NOT EDIT.\n")
            w.write("# Source of truth: git tags (REL42.*)\n")
            w.write("#                  + docs/data/release-history-overlay.yaml.\n")
            w.write("# Run `./gradlew :docs-tools:generateReleaseHistory` to regenerate.\n")
            w.write("\n")
            w.write("rows:\n")
            for (r in rows) {
                w.write("- release_line: \"${r.releaseLine}\"\n")
                w.write("  version_range: \"${r.versionRange}\"\n")
                w.write("  released: \"${r.released}\"\n")
            }
        }
    }
}
