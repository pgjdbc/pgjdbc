/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

@file:JvmName("GenerateReleaseHistory")

package org.postgresql.tools.docs

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.DumperOptions.FlowStyle
import org.yaml.snakeyaml.DumperOptions.ScalarStyle
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.IOException
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Generates `docs/data/release-history.yaml` from local git refs
 * (release branches + release tags), merged with the hand-maintained
 * `docs/data/release-history-overlay.yaml`.
 *
 * The output is a flat `rows` list with three columns per row:
 *  - `release_line` (e.g. `"42.7.x"`),
 *  - `version_range` (`"42.7.0-42.7.11"` for a line's main row,
 *    `"42.2.29.jre7"` for a classifier row),
 *  - `released` (ISO date of the latest tag in the row).
 *
 * Consumers: the `recent-versions` and `past-versions` Hugo shortcodes
 * on the `/download/` page. Nothing else reads this file. The Java
 * version of a classifier row is derived from the `.jreN` suffix in
 * the template, so it is not part of the data.
 *
 * The overlay carries only what cannot be derived from git: the list
 * of `.jre6` / `.jre7` classifier builds (id, branch, last_version).
 */

/** `refs/heads/release/42.X.x` or `refs/remotes/origin/release/42.X.x`. */
private val BRANCH_PATTERN = Regex("""^refs/(?:heads|remotes/[^/]+)/release/(42\.\d+\.x)$""")

/** Release tag. RC tags (REL42.7.11-rc1 etc.) are filtered out. */
private val TAG_PATTERN = Regex("""^refs/tags/REL(42\.\d+\.\d+)$""")

fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: GenerateReleaseHistory " +
            "<project-root> <overlay-yaml> <output-yaml>")
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

internal fun collectGitFacts(projectRoot: Path): GitFacts =
    Git.open(projectRoot.toFile()).use { scanRefs(it.repository) }

private fun scanRefs(repo: Repository): GitFacts {
    val refs = repo.refDatabase.refs

    val branchIds = sortedSetOf(versionDescending())
    for (ref in refs) {
        BRANCH_PATTERN.matchEntire(ref.name)?.let { branchIds += it.groupValues[1] }
    }

    val tagsByBranch = mutableMapOf<String, MutableList<TaggedRelease>>()
    RevWalk(repo).use { walk ->
        for (ref in refs) {
            val match = TAG_PATTERN.matchEntire(ref.name) ?: continue
            val version = match.groupValues[1]
            val branchId = toBranchId(version)
            branchIds += branchId
            val peeled = peelTag(repo, ref) ?: continue
            val commit = walk.parseCommit(peeled)
            tagsByBranch.getOrPut(branchId, ::mutableListOf)
                .add(TaggedRelease(version, formatCommitDate(commit.commitTime)))
        }
    }
    tagsByBranch.values.forEach { list -> list.sortBy { versionKey(it.version) } }

    val orderedBranches = branchIds.toList()
    val orderedTags = orderedBranches.associateWith { tagsByBranch[it].orEmpty() }
    return GitFacts(orderedBranches, orderedTags)
}

private fun toBranchId(version: String): String {
    val second = version.indexOf('.', version.indexOf('.') + 1)
    return version.substring(0, second) + ".x"
}

private fun peelTag(repo: Repository, ref: Ref): ObjectId? {
    val peeled = repo.refDatabase.peel(ref)
    return peeled.peeledObjectId ?: ref.objectId
}

private fun formatCommitDate(epochSecond: Int): String =
    DateTimeFormatter.ISO_LOCAL_DATE
        .withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochSecond(epochSecond.toLong()))

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

/* ===== Overlay loader (snakeyaml) ===================================== */

internal object OverlayLoader {
    @Suppress("UNCHECKED_CAST")
    fun load(file: Path): Overlay {
        if (!Files.isRegularFile(file)) {
            System.err.println("Overlay not found at $file — proceeding with no overlay data.")
            return Overlay()
        }
        val root: Map<String, Any?> = Files.newBufferedReader(file, StandardCharsets.UTF_8).use { r ->
            (Yaml().load<Any?>(r) as? Map<String, Any?>)
                ?: throw IOException("Overlay root must be a mapping in $file")
        }
        return Overlay(
            classifiers = (root["classifiers"] as? List<Map<String, Any?>>).orEmpty().map {
                Classifier(
                    branch = it["branch"].toString(),
                    lastVersion = it["last_version"].toString(),
                    id = it["id"].toString(),
                )
            },
        )
    }
}

/* ===== YAML emitter (snakeyaml) ======================================= */

internal object ReleaseHistoryYamlEmitter {
    fun writeTo(rows: List<Row>, output: Path) {
        Files.createDirectories(output.parent)
        Files.newBufferedWriter(output, StandardCharsets.UTF_8).use { w ->
            writeHeader(w)
            Yaml(dumperOptions()).serialize(buildRoot(rows), w)
        }
    }

    private fun dumperOptions() = DumperOptions().apply {
        defaultFlowStyle = FlowStyle.BLOCK
        indent = 2
        indicatorIndent = 0
        width = 80
        splitLines = true
        lineBreak = DumperOptions.LineBreak.UNIX
    }

    private fun writeHeader(w: Writer) {
        w.write("# Generated by :docs-tools:generateReleaseHistory — DO NOT EDIT.\n")
        w.write("# Source of truth: git refs (release/* branches, REL* tags)\n")
        w.write("#                  + docs/data/release-history-overlay.yaml.\n")
        w.write("# Run `./gradlew :docs-tools:generateReleaseHistory` to regenerate.\n")
        w.write("\n")
    }

    private fun buildRoot(rows: List<Row>): MappingNode {
        val tuple = NodeTuple(
            ScalarNode(Tag.STR, "rows", null, null, ScalarStyle.PLAIN),
            SequenceNode(Tag.SEQ, true, rows.map(::buildRow), null, null, FlowStyle.BLOCK),
        )
        return MappingNode(Tag.MAP, true, listOf(tuple), null, null, FlowStyle.BLOCK)
    }

    private fun buildRow(r: Row): MappingNode = MappingNode(
        Tag.MAP,
        true,
        listOf(
            field("release_line", r.releaseLine),
            field("version_range", r.versionRange),
            field("released", r.released),
        ),
        null, null, FlowStyle.BLOCK,
    )

    private fun field(key: String, value: String): NodeTuple = NodeTuple(
        ScalarNode(Tag.STR, key, null, null, ScalarStyle.PLAIN),
        // DOUBLE_QUOTED matches the prior hand-rolled emitter which
        // always wrapped values in `"..."` so a future contributor knows
        // every value is a string regardless of how it parses.
        ScalarNode(Tag.STR, value, null, null, ScalarStyle.DOUBLE_QUOTED),
    )
}
