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
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Generates `docs/data/release-history.yaml` from local git refs
 * (release branches + release tags) merged with the hand-maintained
 * `docs/data/release-history-overlay.yaml`.
 *
 * The output is a flat `rows` list consumed by the `release-history`
 * Hugo shortcode on the `/documentation/getting-started/compatibility/`
 * page. Each row represents one "compatibility segment":
 *
 *  - **segment row** — a contiguous run of tagged versions on a release
 *    branch with the same `(min_java, min_postgresql)` pair. The version
 *    range becomes the `42.7.0-42.7.4` cell.
 *  - **classifier row** — the latest `.jre6` / `.jre7` build of the
 *    42.2.x line, surfaced as a separate row under the same release-line
 *    label.
 *
 * The `Status` column is derived from each line's `.0` commit date plus
 * the overlay's `support_window_years`: rows older than the window are
 * "EOL since YYYY-MM", rows still inside are "Security until YYYY-MM",
 * and the latest segment of the latest release line is "Current".
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
    val rows = buildRows(facts, overlay, LocalDate.now(ZoneOffset.UTC))
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

internal data class Breakpoint(val pgjdbc: String, val value: String)

internal data class Classifier(
    val id: String,
    val branch: String,
    val lastVersion: String,
    val minJava: String,
)

internal data class Overlay(
    val supportWindowYears: Int = 5,
    val minJava: List<Breakpoint> = emptyList(),
    val minPostgresql: List<Breakpoint> = emptyList(),
    val classifiers: List<Classifier> = emptyList(),
    val testedJava: Map<String, List<String>> = emptyMap(),
    val testedPostgresql: Map<String, List<String>> = emptyMap(),
    val branchNotes: Map<String, String> = emptyMap(),
)

internal data class Row(
    val releaseLine: String,
    val versionRange: String,
    val released: String,
    val minJava: String = "",
    val minPostgresql: String = "",
    val status: String,
    val notes: String = "",
)

/** One published GHSA, possibly with multiple branch-specific fixes. */
internal data class SecurityAdvisory(
    /** CVE-XXXX-XXXX, or empty string for advisories without an assigned CVE. */
    val cveId: String,
    /** GHSA-xxxx-xxxx-xxxx — used as fallback display when cveId is empty. */
    val ghsaId: String,
    val severity: String,
    /** CVSS v3 base score (0.0..10.0), formatted as the GHSA payload
     *  delivers it (e.g. "9.8"). Empty string when the advisory has no
     *  CVSS attached. */
    val cvssScore: String,
    val vulnerabilities: List<VulnerabilityEntry>,
)

/** One affected-range + fix entry within an advisory (one per release branch). */
internal data class VulnerabilityEntry(
    val vulnerableRange: VersionRange,
    /** First version listed in `patched_versions`, e.g. "42.7.2". May be empty
     *  for advisories with no fix yet (state=published but unresolved). */
    val patchedVersion: String,
)

internal data class Segment(
    val firstVersion: String,
    val lastVersion: String,
    val firstDate: String,
    val lastDate: String,
    val minJava: String,
    val minPg: String,
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

internal fun buildRows(
    facts: GitFacts,
    overlay: Overlay,
    today: LocalDate,
): List<Row> {
    val jBp = overlay.minJava.sortedBy { versionKey(it.pgjdbc) }
    val pgBp = overlay.minPostgresql.sortedBy { versionKey(it.pgjdbc) }
    val classifiersByBranch = overlay.classifiers.groupBy { it.branch }
    val latestLine = facts.branchIds.firstOrNull().orEmpty()

    val out = mutableListOf<Row>()
    for (branchId in facts.branchIds) {
        val tags = facts.tagsByBranch[branchId].orEmpty()
        if (tags.isEmpty()) continue

        val latest = tags.last()
        // Branch is rendered only if its latest tag falls within the
        // overlay's coverage (≥ lowest breakpoint).
        if (resolve(jBp, latest.version).isEmpty() &&
            resolve(pgBp, latest.version).isEmpty()) {
            continue
        }

        // Compute the line's support window from its .0 (or earliest)
        // tag plus support_window_years. Used in the Status column for
        // every row of this line.
        val lineStart = LocalDate.parse(tags.first().commitDate)
        val supportEnd = lineStart.plusYears(overlay.supportWindowYears.toLong())
        val supportEndYm = DateTimeFormatter.ofPattern("yyyy-MM").format(supportEnd)

        // Group consecutive tags by (min_java, min_pg). Each group is one
        // "segment" — one row in the rendered matrix.
        val segments = computeSegments(tags, jBp, pgBp)

        // Collect this branch's rows into a local list so we can sort by
        // released-desc before appending to the global output; release-line
        // groups stay in their outer (descending) order.
        val branchRows = mutableListOf<Row>()

        segments.forEachIndexed { i, seg ->
            val isLastSegment = i == segments.lastIndex
            val isCurrent = isLastSegment && branchId == latestLine
            val status = when {
                !isLastSegment -> "Superseded by ${segments[i + 1].firstVersion}+"
                isCurrent -> "Current"
                today.isBefore(supportEnd) -> "Security until $supportEndYm"
                else -> "EOL since $supportEndYm"
            }
            branchRows += Row(
                releaseLine = branchId,
                versionRange = formatRange(seg.firstVersion, seg.lastVersion),
                released = seg.lastDate,
                minJava = seg.minJava,
                minPostgresql = seg.minPg,
                status = status,
                notes = composeNotes(isCurrent, branchId, overlay),
            )
        }

        // Classifier rows piggy-back on the parent line's status.
        for (c in classifiersByBranch[branchId].orEmpty()) {
            val date = tags.firstOrNull { it.version == c.lastVersion }?.commitDate.orEmpty()
            branchRows += Row(
                releaseLine = branchId,
                versionRange = "${c.lastVersion}.${c.id}",
                released = date,
                minJava = c.minJava,
                minPostgresql = resolve(pgBp, c.lastVersion),
                // No per-classifier note: the Java column ("6+" / "7+")
                // and the version range column already convey that this
                // row is the latest jreN build of the line.
                status = if (today.isBefore(supportEnd)) "Security until $supportEndYm"
                else "EOL since $supportEndYm",
            )
        }

        // Sort within the branch by released-desc; sortByDescending is stable
        // and keeps tied dates in emission order (main segment before
        // classifier with the same date).
        branchRows.sortByDescending { it.released }
        out += branchRows
    }
    return out
}

private fun composeNotes(isCurrent: Boolean, branchId: String, overlay: Overlay): String {
    val fallback = overlay.branchNotes[branchId].orEmpty()
    if (!isCurrent) return fallback
    val jv = overlay.testedJava[branchId].orEmpty()
    val pgv = overlay.testedPostgresql[branchId].orEmpty()
    if (jv.isEmpty() && pgv.isEmpty()) return fallback
    return "CI: Java ${jv.joinToString(", ")}; PostgreSQL ${collapsedPgList(pgv)}."
}

/** Render PG list as "first–last" when it has at least five entries (the
 *  only realistic case is "every supported version in the modern Postgres
 *  line"); otherwise comma-separated. */
private fun collapsedPgList(values: List<String>): String =
    if (values.size >= 5) "${values.first()}-${values.last()}" else values.joinToString(", ")

/* ===== Segment computation ============================================ */

internal fun computeSegments(
    tags: List<TaggedRelease>,
    jBp: List<Breakpoint>,
    pgBp: List<Breakpoint>,
): List<Segment> {
    if (tags.isEmpty()) return emptyList()
    val floors = tags.map { resolve(jBp, it.version) to resolve(pgBp, it.version) }
    return buildList {
        var startIdx = 0
        for (i in 1..tags.size) {
            val boundary = i == tags.size || floors[i] != floors[startIdx]
            if (boundary) {
                val first = tags[startIdx]
                val last = tags[i - 1]
                val (j, pg) = floors[startIdx]
                add(Segment(first.version, last.version, first.commitDate, last.commitDate, j, pg))
                startIdx = i
            }
        }
    }
}

private fun formatRange(first: String, last: String): String = when {
    first == last -> last
    else -> "$first-$last"
}

internal fun resolve(ascending: List<Breakpoint>, target: String): String {
    val targetKey = versionKey(target)
    var resolved: String? = null
    for (bp in ascending) {
        if (versionKey(bp.pgjdbc) <= targetKey) resolved = bp.value
        else break
    }
    return resolved.orEmpty()
}

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
            supportWindowYears = (root["support_window_years"] as? Number)?.toInt() ?: 5,
            minJava = (root["min_java"] as? List<Map<String, Any?>>).orEmpty().map {
                Breakpoint(it["pgjdbc"].toString(), it["java"].toString())
            },
            minPostgresql = (root["min_postgresql"] as? List<Map<String, Any?>>).orEmpty().map {
                Breakpoint(it["pgjdbc"].toString(), it["postgresql"].toString())
            },
            classifiers = (root["classifiers"] as? List<Map<String, Any?>>).orEmpty().map {
                Classifier(
                    id = it["id"].toString(),
                    branch = it["branch"].toString(),
                    lastVersion = it["last_version"].toString(),
                    minJava = it["min_java"].toString(),
                )
            },
            testedJava = (root["tested_java"] as? Map<String, Any?>).orEmpty().mapValues { (_, v) -> toStringList(v) },
            testedPostgresql = (root["tested_postgresql"] as? Map<String, Any?>).orEmpty().mapValues { (_, v) -> toStringList(v) },
            branchNotes = (root["branch_notes"] as? Map<String, Any?>).orEmpty().mapValues { (_, v) -> v.toString() },
        )
    }

    private fun toStringList(raw: Any?): List<String> =
        (raw as? List<*>)?.map { it.toString() }.orEmpty()
}

/* ===== Version range (subset of GHSA range syntax) ===================== */

/**
 * Predicate over pgjdbc version strings, derived from GHSA's
 * `vulnerable_version_range` syntax: a comma-separated list of
 * constraints (`<`, `<=`, `>`, `>=`) AND'd together.
 *
 * The "exact equality" form (`= X` or bare `X`) is not currently emitted
 * by pgjdbc advisories and is not supported. An empty range matches
 * nothing (safer than matching everything if GHSA ever returns empty).
 */
internal class VersionRange private constructor(
    private val constraints: List<Constraint>,
) {
    fun contains(version: String): Boolean {
        if (constraints.isEmpty()) return false
        val k = versionKey(version)
        return constraints.all { it.matches(k) }
    }

    internal data class Constraint(val op: String, val versionKey: Long) {
        fun matches(target: Long): Boolean = when (op) {
            "<" -> target < versionKey
            "<=" -> target <= versionKey
            ">" -> target > versionKey
            ">=" -> target >= versionKey
            else -> false
        }
    }

    companion object {
        /** Recognises `<`, `<=`, `>`, `>=` followed by a version, optionally separated by whitespace. */
        private val CONSTRAINT = Regex("""\s*(<=|>=|<|>)\s*([0-9A-Za-z.\-]+)\s*""")

        fun parse(raw: String): VersionRange {
            if (raw.isBlank()) return VersionRange(emptyList())
            val parts = mutableListOf<Constraint>()
            for (chunk in raw.split(',')) {
                val m = CONSTRAINT.matchEntire(chunk) ?: continue
                parts += Constraint(m.groupValues[1], versionKey(m.groupValues[2]))
            }
            return VersionRange(parts)
        }
    }
}

/* ===== Security advisory fetcher ======================================= */

/**
 * Lists published security advisories of a GitHub repo by shelling out to
 * `gh api`. The fetcher is best-effort: if `gh` is missing or the call
 * fails (offline, rate-limited, unauthenticated for a private repo), it
 * logs a warning and returns an empty list.
 *
 * Currently unused by the matrix generator — the compatibility page
 * deliberately does NOT surface per-segment CVE info because a single
 * `version_range` row mixes vulnerable and patched releases, which reads
 * misleadingly. Kept here (with [VersionRange] and [SecurityAdvisory]
 * for parsing the GHSA payload) as the building block for a future
 * dedicated `/security/` page that lists each advisory with its full
 * affected/patched range, rather than crammed into a Notes cell.
 */
internal object SecurityAdvisoryFetcher {

    fun fetch(repo: String): List<SecurityAdvisory> {
        val raw = invokeGh(repo) ?: return emptyList()
        return parse(raw)
    }

    private fun invokeGh(repo: String): String? {
        return try {
            val proc = ProcessBuilder(
                "gh", "api", "--paginate",
                "repos/$repo/security-advisories?state=published&per_page=100",
            ).redirectErrorStream(false).start()
            val out = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            val err = proc.errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
            val exit = proc.waitFor()
            if (exit != 0) {
                System.err.println("gh api exited with $exit; CVE column will be empty. stderr: ${err.trim()}")
                null
            } else out
        } catch (e: Exception) {
            System.err.println("Could not invoke gh CLI (${e.javaClass.simpleName}: ${e.message}); CVE column will be empty.")
            null
        }
    }

    /**
     * GHSA pagination concatenates several JSON arrays — `gh --paginate`
     * emits them back-to-back like `[...][...]`. Split on the boundary,
     * parse each independently, flatten.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parse(raw: String): List<SecurityAdvisory> {
        // Replace `][` page-boundary marker with `,` so the whole stream
        // parses as a single JSON array; tolerates whitespace between pages.
        val joined = raw.replace(Regex("""]\s*\["""), ",")
        val parsed = Yaml().load<Any?>(joined) as? List<Map<String, Any?>> ?: return emptyList()
        return parsed.map { adv ->
            val cveId = (adv["cve_id"] as? String).orEmpty()
            val ghsaId = (adv["ghsa_id"] as? String).orEmpty()
            val severity = (adv["severity"] as? String).orEmpty()
            // `cvss.score` is a Number in the JSON; toString turns Double `9.8`
            // into "9.8" without trailing zeros. CVSS v4 lives under
            // cvss_severities.cvss_v4.score but most advisories only fill v3,
            // so we stick to the canonical top-level `cvss.score` field.
            val cvssScore = ((adv["cvss"] as? Map<String, Any?>)?.get("score") as? Number)
                ?.let(::formatCvss)
                .orEmpty()
            val vulns = (adv["vulnerabilities"] as? List<Map<String, Any?>>).orEmpty().map { v ->
                val range = VersionRange.parse((v["vulnerable_version_range"] as? String).orEmpty())
                val patched = (v["patched_versions"] as? String)
                    .orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
                VulnerabilityEntry(range, patched)
            }
            SecurityAdvisory(cveId, ghsaId, severity, cvssScore, vulns)
        }
    }

    /** Strip a trailing ".0" so a whole-number CVSS reads as "9", "7" etc. */
    internal fun formatCvss(n: Number): String {
        val s = n.toDouble().toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
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
        buildList {
            add(field("release_line", r.releaseLine))
            add(field("version_range", r.versionRange))
            add(field("released", r.released))
            if (r.minJava.isNotEmpty()) add(field("min_java", r.minJava))
            if (r.minPostgresql.isNotEmpty()) add(field("min_postgresql", r.minPostgresql))
            add(field("status", r.status))
            if (r.notes.isNotEmpty()) add(field("notes", r.notes))
        },
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
