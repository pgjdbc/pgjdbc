/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FetchKnownCvesTest {

    private fun adv(cve: String, ghsa: String, severity: String, vararg ranges: Pair<String, String>) =
        SecurityAdvisory(cve, ghsa, severity, cvssScore = "", vulnerabilities = ranges.map {
            VulnerabilityEntry(VersionRange.parse(it.first), it.second)
        })

    private fun facts(vararg tagsPerBranch: Pair<String, List<String>>) = GitFacts(
        branchIds = tagsPerBranch.map { it.first },
        tagsByBranch = tagsPerBranch.associate { (b, vs) ->
            b to vs.map { TaggedRelease(it, "2024-01-01") }
        },
    )

    @Test fun `version with no matching advisory is omitted from the map`() {
        // The Hugo layout uses `with index ...` and renders nothing when the
        // key is absent — clean releases must not have an entry at all.
        val cves = computeKnownCves(
            facts("42.7.x" to listOf("42.7.11")),
            listOf(adv("CVE-2099-X", "GHSA-X", "high", "< 42.7.7" to "42.7.7")),
            "pgjdbc/pgjdbc",
        )
        assertFalse(cves.containsKey("42.7.11"))
    }

    @Test fun `vulnerable version goes into affected_by with its branch fix`() {
        val cves = computeKnownCves(
            facts("42.7.x" to listOf("42.7.0", "42.7.2")),
            listOf(adv("CVE-2024-1597", "GHSA-24rp", "critical",
                "< 42.7.2" to "42.7.2")),
            "pgjdbc/pgjdbc",
        )
        val entry = cves["42.7.0"]!!.affectedBy.single()
        assertEquals("CVE-2024-1597", entry.id)
        assertEquals("critical", entry.severity)
        assertEquals("42.7.2", entry.fixedIn)
        assertTrue(entry.advisoryUrl.endsWith("/security/advisories/GHSA-24rp"), entry.advisoryUrl)
        // 42.7.0 has nothing in fixes — it didn't patch anything.
        assertEquals(emptyList<KnownCve>(), cves["42.7.0"]!!.fixes)
    }

    @Test fun `fix version goes into fixes with empty fixedIn`() {
        // 42.7.2 is the patched_versions of the advisory — it lands in `fixes`,
        // not in `affected_by`. `fixed_in` is left blank since the page IS the fix.
        val cves = computeKnownCves(
            facts("42.7.x" to listOf("42.7.0", "42.7.2")),
            listOf(adv("CVE-2024-1597", "GHSA-24rp", "critical",
                "< 42.7.2" to "42.7.2")),
            "pgjdbc/pgjdbc",
        )
        val entry = cves["42.7.2"]!!.fixes.single()
        assertEquals("CVE-2024-1597", entry.id)
        assertEquals("", entry.fixedIn)
        assertEquals(emptyList<KnownCve>(), cves["42.7.2"]!!.affectedBy)
    }

    @Test fun `version can simultaneously fix one CVE and remain vulnerable to another`() {
        // 42.7.7 fixes CVE-2025-49146 but is still vulnerable to CVE-2026-42198
        // (which won't be patched until 42.7.11).
        val cves = computeKnownCves(
            facts("42.7.x" to listOf("42.7.7")),
            listOf(
                adv("CVE-2025-49146", "GHSA-hq9p", "high",
                    ">= 42.7.4, < 42.7.7" to "42.7.7"),
                adv("CVE-2026-42198", "GHSA-98qh", "high",
                    ">= 42.2.0, < 42.7.11" to "42.7.11"),
            ),
            "pgjdbc/pgjdbc",
        )
        val vc = cves["42.7.7"]!!
        assertEquals(listOf("CVE-2025-49146"), vc.fixes.map { it.id })
        assertEquals(listOf("CVE-2026-42198"), vc.affectedBy.map { it.id })
    }

    @Test fun `cross-line advisory surfaces with the upstream fix version`() {
        // CVE-2026-42198 has one entry covering everything below 42.7.11.
        // A 42.6.2 page should warn "fixed in 42.7.11" — that IS the fix,
        // even though it's on a newer line.
        val cves = computeKnownCves(
            facts(
                "42.7.x" to listOf("42.7.11"),
                "42.6.x" to listOf("42.6.2"),
            ),
            listOf(adv("CVE-2026-42198", "GHSA-98qh", "high",
                ">= 42.2.0, < 42.7.11" to "42.7.11")),
            "pgjdbc/pgjdbc",
        )
        assertEquals("42.7.11", cves["42.6.2"]!!.affectedBy.single().fixedIn)
        // 42.7.11 itself is the fix — appears in fixes, not in affected_by.
        assertEquals(listOf("CVE-2026-42198"), cves["42.7.11"]!!.fixes.map { it.id })
    }

    @Test fun `multiple advisories on the same affected_by version preserve input order`() {
        val cves = computeKnownCves(
            facts("42.7.x" to listOf("42.7.5")),
            listOf(
                adv("CVE-2025-49146", "GHSA-hq9p", "high",
                    ">= 42.7.4, < 42.7.7" to "42.7.7"),
                adv("CVE-2026-42198", "GHSA-98qh", "high",
                    ">= 42.2.0, < 42.7.11" to "42.7.11"),
            ),
            "pgjdbc/pgjdbc",
        )
        assertEquals(
            listOf("CVE-2025-49146", "CVE-2026-42198"),
            cves["42.7.5"]!!.affectedBy.map { it.id },
        )
    }

    @Test fun `advisory without a CVE id falls back to the GHSA id`() {
        val cves = computeKnownCves(
            facts("42.3.x" to listOf("42.3.2")),
            listOf(adv("", "GHSA-673j-qm5f-xpv8", "high", "< 42.3.3" to "42.3.3")),
            "pgjdbc/pgjdbc",
        )
        assertEquals("GHSA-673j-qm5f-xpv8", cves["42.3.2"]!!.affectedBy.single().id)
    }

    @Test fun `output map iterates in ascending version order`() {
        // Important: the YAML emitter writes in iteration order, and we want
        // versions ordered by versionKey (so 42.7.10 follows 42.7.9, not
        // sandwiched between 42.7.1 and 42.7.2 lexically).
        val cves = computeKnownCves(
            facts("42.7.x" to listOf("42.7.10", "42.7.1", "42.7.9", "42.7.2")),
            listOf(adv("CVE-X", "GHSA-X", "high", ">= 42.7.0" to "")),
            "pgjdbc/pgjdbc",
        )
        assertEquals(listOf("42.7.1", "42.7.2", "42.7.9", "42.7.10"), cves.keys.toList())
    }

    /* ----- YAML emitter --------------------------------------------------- */

    @TempDir lateinit var tmp: Path

    @Test fun `emitter writes header plus version mapping with fixes and affected_by`() {
        val out = tmp.resolve("known-cves.yaml")
        KnownCvesYamlEmitter.writeTo(
            mapOf("42.7.7" to VersionCves(
                fixes = listOf(KnownCve(
                    id = "CVE-2025-49146",
                    ghsaId = "GHSA-hq9p",
                    severity = "high",
                    cvssScore = "7.5",
                    fixedIn = "",
                    advisoryUrl = "https://example/GHSA-hq9p",
                )),
                affectedBy = listOf(KnownCve(
                    id = "CVE-2026-42198",
                    ghsaId = "GHSA-98qh",
                    severity = "high",
                    cvssScore = "7.5",
                    fixedIn = "42.7.11",
                    advisoryUrl = "https://example/GHSA-98qh",
                )),
            )),
            out,
        )
        val text = out.toFile().readText()
        assertTrue(text.startsWith("# Generated by"), text.take(200))
        assertTrue(text.contains("\"42.7.7\":"), text)
        assertTrue(text.contains("fixes:"), text)
        assertTrue(text.contains("affected_by:"), text)
        assertTrue(text.contains("id: \"CVE-2025-49146\""), text)
        assertTrue(text.contains("id: \"CVE-2026-42198\""), text)
        assertTrue(text.contains("fixed_in: \"42.7.11\""), text)
    }
}
