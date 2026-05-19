/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

@file:JvmName("FetchKnownCves")

package org.postgresql.tools.docs

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.DumperOptions.FlowStyle
import org.yaml.snakeyaml.DumperOptions.ScalarStyle
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Generates `docs/data/known-cves.yaml`: a version→CVE map consumed by the
 * changelog single-page template (`layouts/changelogs/single.html`) to
 * surface a "known CVEs" banner at the top of each release page.
 *
 * Cross-references local git tags with GitHub Security Advisories: for
 * each tagged pgjdbc version, lists every published advisory whose
 * `vulnerable_version_range` contains that version, along with the
 * branch-specific fix version (`patched_versions`) and severity.
 *
 * Output keys are pgjdbc version strings ("42.7.5"); versions with no
 * matching advisory are omitted so the layout's `with index ...` block
 * is naturally skipped — clean releases render no banner.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: FetchKnownCves <project-root> <output-yaml> [<ghsa-repo>]")
        exitProcess(2)
    }
    val projectRoot = Paths.get(args[0])
    val outputYaml = Paths.get(args[1])
    val ghsaRepo = args.getOrNull(2) ?: "pgjdbc/pgjdbc"

    val facts = collectGitFacts(projectRoot)
    val advisories = SecurityAdvisoryFetcher.fetch(ghsaRepo)
    val cvesByVersion = computeKnownCves(facts, advisories, ghsaRepo)
    KnownCvesYamlEmitter.writeTo(cvesByVersion, outputYaml)
    println("Wrote known CVEs for ${cvesByVersion.size} versions to $outputYaml")
}

/** Per-version split of advisories the release page should surface. */
internal data class VersionCves(
    /** Advisories whose `patched_versions` IS this version — i.e. this
     *  release ships the fix. Rendered as the green "Fixes" banner. */
    val fixes: List<KnownCve>,
    /** Advisories whose `vulnerable_version_range` contains this version
     *  but the fix is in some other version. Rendered as the red
     *  "Affected by" banner. */
    val affectedBy: List<KnownCve>,
)

/** One CVE attached to one specific pgjdbc version on a release page. */
internal data class KnownCve(
    /** Display id — `cve_id` if assigned, otherwise the `ghsa_id`. */
    val id: String,
    val ghsaId: String,
    val severity: String,
    /** CVSS v3 base score (e.g. "9.8"); empty when GHSA carries no CVSS. */
    val cvssScore: String,
    /** First version that ships the fix. Set in `affectedBy` entries,
     *  empty in `fixes` entries (the page IS the fix — redundant). */
    val fixedIn: String,
    val advisoryUrl: String,
)

/**
 * For each tagged version, splits matched advisories into two buckets:
 * `fixes` (this version IS some entry's `patched_version`) and
 * `affectedBy` (some entry's `vulnerable_range` contains this version
 * and the fix is elsewhere).
 *
 * An advisory cannot land in both buckets for the same version: a
 * release that ships the fix for an advisory is by definition not in
 * that advisory's vulnerable range (assuming the GHSA author kept the
 * range upper-bounded at the patched version, which is the canonical
 * shape).
 *
 * Versions with no matching advisory are omitted so the Hugo layout's
 * `with` block naturally skips rendering the banner.
 */
internal fun computeKnownCves(
    facts: GitFacts,
    advisories: List<SecurityAdvisory>,
    ghsaRepo: String,
): Map<String, VersionCves> {
    val result = sortedMapOf<String, VersionCves>(compareBy(::versionKey))
    val allTags = facts.tagsByBranch.values.flatten()
    for (tag in allTags) {
        val fixes = mutableListOf<KnownCve>()
        val affectedBy = mutableListOf<KnownCve>()
        for (advisory in advisories) {
            val advisoryUrl = "https://github.com/$ghsaRepo/security/advisories/${advisory.ghsaId}"
            // Fix-version check first: a release "owns" any advisory whose
            // patched_versions points at it, and skips the affected check.
            val isFixFor = advisory.vulnerabilities.any { it.patchedVersion == tag.version }
            if (isFixFor) {
                fixes += KnownCve(
                    id = advisory.cveId.ifEmpty { advisory.ghsaId },
                    ghsaId = advisory.ghsaId,
                    severity = advisory.severity,
                    cvssScore = advisory.cvssScore,
                    fixedIn = "",
                    advisoryUrl = advisoryUrl,
                )
                continue
            }
            val entry = advisory.vulnerabilities.firstOrNull {
                it.vulnerableRange.contains(tag.version)
            } ?: continue
            affectedBy += KnownCve(
                id = advisory.cveId.ifEmpty { advisory.ghsaId },
                ghsaId = advisory.ghsaId,
                severity = advisory.severity,
                cvssScore = advisory.cvssScore,
                fixedIn = entry.patchedVersion,
                advisoryUrl = advisoryUrl,
            )
        }
        if (fixes.isNotEmpty() || affectedBy.isNotEmpty()) {
            result[tag.version] = VersionCves(fixes, affectedBy)
        }
    }
    return result
}

/* ===== YAML emitter ==================================================== */

internal object KnownCvesYamlEmitter {
    fun writeTo(cvesByVersion: Map<String, VersionCves>, output: Path) {
        Files.createDirectories(output.parent)
        Files.newBufferedWriter(output, StandardCharsets.UTF_8).use { w ->
            writeHeader(w)
            Yaml(dumperOptions()).serialize(buildRoot(cvesByVersion), w)
        }
    }

    private fun dumperOptions() = DumperOptions().apply {
        defaultFlowStyle = FlowStyle.BLOCK
        indent = 2
        indicatorIndent = 0
        width = 120
        splitLines = true
        lineBreak = DumperOptions.LineBreak.UNIX
    }

    private fun writeHeader(w: Writer) {
        w.write("# Generated by :docs-tools:fetchKnownCves — DO NOT EDIT.\n")
        w.write("# Source: gh api repos/<repo>/security-advisories (state=published,\n")
        w.write("#         minus withdrawn_at)\n")
        w.write("#         × local git tags. Run `./gradlew :docs-tools:fetchKnownCves`\n")
        w.write("# to regenerate. Each version maps to two lists:\n")
        w.write("#   - fixes:       advisories this release patched\n")
        w.write("#   - affected_by: advisories this release is still vulnerable to\n")
        w.write("# Versions with neither are omitted from the file.\n")
        w.write("\n")
    }

    private fun buildRoot(cvesByVersion: Map<String, VersionCves>): MappingNode {
        val tuples = cvesByVersion.map { (version, vc) ->
            NodeTuple(
                ScalarNode(Tag.STR, version, null, null, ScalarStyle.DOUBLE_QUOTED),
                buildVersionEntry(vc),
            )
        }
        return MappingNode(Tag.MAP, true, tuples, null, null, FlowStyle.BLOCK)
    }

    private fun buildVersionEntry(vc: VersionCves): MappingNode = MappingNode(
        Tag.MAP,
        true,
        listOf(
            NodeTuple(
                ScalarNode(Tag.STR, "fixes", null, null, ScalarStyle.PLAIN),
                SequenceNode(Tag.SEQ, true, vc.fixes.map(::buildCve), null, null, FlowStyle.BLOCK),
            ),
            NodeTuple(
                ScalarNode(Tag.STR, "affected_by", null, null, ScalarStyle.PLAIN),
                SequenceNode(Tag.SEQ, true, vc.affectedBy.map(::buildCve), null, null, FlowStyle.BLOCK),
            ),
        ),
        null, null, FlowStyle.BLOCK,
    )

    private fun buildCve(c: KnownCve): MappingNode = MappingNode(
        Tag.MAP,
        true,
        listOf(
            field("id", c.id),
            field("ghsa", c.ghsaId),
            field("severity", c.severity),
            field("cvss_score", c.cvssScore),
            field("fixed_in", c.fixedIn),
            field("advisory_url", c.advisoryUrl),
        ),
        null, null, FlowStyle.BLOCK,
    )

    private fun field(key: String, value: String): NodeTuple = NodeTuple(
        ScalarNode(Tag.STR, key, null, null, ScalarStyle.PLAIN),
        ScalarNode(Tag.STR, value, null, null, ScalarStyle.DOUBLE_QUOTED),
    )
}
