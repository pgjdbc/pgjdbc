/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class GenerateReleaseHistoryTest {

    /* ----- versionKey & ordering ----------------------------------------- */

    @Test fun `versionKey orders multi-segment versions numerically`() {
        // Lexical sort would put 42.7.10 before 42.7.2 — versionKey must not.
        val versions = listOf("42.7.2", "42.7.10", "42.7.1", "42.6.0")
            .sortedBy(::versionKey)
        assertEquals(listOf("42.6.0", "42.7.1", "42.7.2", "42.7.10"), versions)
    }

    @Test fun `versionKey treats x as zero in branch ids`() {
        // 42.7.x sorts as 42.7.0 — strictly less than 42.7.1.
        assertTrue(versionKey("42.7.x") < versionKey("42.7.1"))
    }

    @Test fun `versionDescending puts the highest version first`() {
        val sorted = listOf("42.2.x", "42.7.x", "42.5.x")
            .sortedWith(versionDescending())
        assertEquals(listOf("42.7.x", "42.5.x", "42.2.x"), sorted)
    }

    /* ----- resolve breakpoints ------------------------------------------- */

    @Test fun `resolve returns the largest breakpoint not exceeding the target`() {
        val bps = listOf(
            Breakpoint("42.0.0", "8"),
            Breakpoint("42.7.0", "11"),
        )
        assertEquals("8", resolve(bps, "42.5.0"))
        assertEquals("11", resolve(bps, "42.7.3"))
        // Target below the lowest breakpoint yields "".
        assertEquals("", resolve(bps, "41.9.0"))
    }

    /* ----- computeSegments ----------------------------------------------- */

    @Test fun `computeSegments splits on floor change and preserves the run`() {
        val tags = listOf(
            TaggedRelease("42.7.0", "2024-01-01"),
            TaggedRelease("42.7.1", "2024-02-01"),
            TaggedRelease("42.7.5", "2024-08-01"),
            TaggedRelease("42.7.6", "2024-09-01"),
        )
        val jBp = listOf(Breakpoint("42.0.0", "8"))
        val pgBp = listOf(
            Breakpoint("42.0.0", "8.4"),
            Breakpoint("42.7.5", "9.1"),
        )
        val segs = computeSegments(tags, jBp, pgBp)
        assertEquals(2, segs.size)
        assertEquals("42.7.0", segs[0].firstVersion)
        assertEquals("42.7.1", segs[0].lastVersion)
        assertEquals("8.4", segs[0].minPg)
        assertEquals("42.7.5", segs[1].firstVersion)
        assertEquals("42.7.6", segs[1].lastVersion)
        assertEquals("9.1", segs[1].minPg)
    }

    @Test fun `computeSegments on empty input returns empty`() {
        assertEquals(emptyList<Segment>(), computeSegments(emptyList(), emptyList(), emptyList()))
    }

    /* ----- buildRows end-to-end (with hand-crafted facts + overlay) ------ */

    @Test fun `buildRows marks the latest segment of the latest line as Current`() {
        val facts = GitFacts(
            branchIds = listOf("42.7.x"),
            tagsByBranch = mapOf("42.7.x" to listOf(
                TaggedRelease("42.7.0", "2024-01-01"),
                TaggedRelease("42.7.6", "2024-09-01"),
            )),
        )
        val overlay = Overlay(
            supportWindowYears = 5,
            minJava = listOf(Breakpoint("42.0.0", "8")),
            minPostgresql = listOf(Breakpoint("42.0.0", "8.4")),
        )
        val rows = buildRows(facts, overlay, LocalDate.of(2024, 12, 1))
        assertEquals(1, rows.size)
        assertEquals("Current", rows.single().status)
        assertEquals("42.7.0-42.7.6", rows.single().versionRange)
    }

    @Test fun `older line within support window gets Security until status`() {
        val facts = GitFacts(
            branchIds = listOf("42.7.x", "42.5.x"),
            tagsByBranch = mapOf(
                "42.7.x" to listOf(TaggedRelease("42.7.0", "2024-01-01")),
                "42.5.x" to listOf(TaggedRelease("42.5.0", "2023-01-01")),
            ),
        )
        val overlay = Overlay(
            supportWindowYears = 5,
            minJava = listOf(Breakpoint("42.0.0", "8")),
            minPostgresql = listOf(Breakpoint("42.0.0", "8.4")),
        )
        val rows = buildRows(facts, overlay, LocalDate.of(2025, 6, 1))
        assertEquals("Current", rows[0].status)
        assertTrue(rows[1].status.startsWith("Security until 2028-01"), rows[1].status)
    }

    @Test fun `expired line gets EOL since status`() {
        // 42.7.x must exist as the latest line so 42.2.x is not treated as
        // the "current" one even though it's the only line with tags here.
        val facts = GitFacts(
            branchIds = listOf("42.7.x", "42.2.x"),
            tagsByBranch = mapOf(
                "42.7.x" to listOf(TaggedRelease("42.7.0", "2024-01-01")),
                "42.2.x" to listOf(TaggedRelease("42.2.0", "2018-01-01")),
            ),
        )
        val overlay = Overlay(
            supportWindowYears = 5,
            minJava = listOf(Breakpoint("42.0.0", "8")),
            minPostgresql = listOf(Breakpoint("42.0.0", "8.4")),
        )
        val rows = buildRows(facts, overlay, LocalDate.of(2025, 6, 1))
        val row22 = rows.single { it.releaseLine == "42.2.x" }
        assertTrue(row22.status.startsWith("EOL since 2023-01"), row22.status)
    }

    @Test fun `intermediate segment is marked Superseded by next floor`() {
        val facts = GitFacts(
            branchIds = listOf("42.7.x"),
            tagsByBranch = mapOf("42.7.x" to listOf(
                TaggedRelease("42.7.0", "2024-01-01"),
                TaggedRelease("42.7.5", "2024-08-01"),
            )),
        )
        val overlay = Overlay(
            supportWindowYears = 5,
            minJava = listOf(Breakpoint("42.0.0", "8")),
            minPostgresql = listOf(
                Breakpoint("42.0.0", "8.4"),
                Breakpoint("42.7.5", "9.1"),
            ),
        )
        val rows = buildRows(facts, overlay, LocalDate.of(2024, 12, 1))
        assertEquals(2, rows.size)
        // released-desc: newer segment first
        assertEquals("Current", rows[0].status)
        assertEquals("Superseded by 42.7.5+", rows[1].status)
    }

    /* ----- VersionRange parsing ------------------------------------------ */

    @Test fun `VersionRange parses single less-than constraint`() {
        val r = VersionRange.parse("< 42.7.2")
        assertTrue(r.contains("42.7.0"))
        assertTrue(r.contains("42.7.1"))
        assertEquals(false, r.contains("42.7.2"))
        assertEquals(false, r.contains("42.7.3"))
    }

    @Test fun `VersionRange parses composite range`() {
        val r = VersionRange.parse(">= 42.7.4, < 42.7.7")
        assertEquals(false, r.contains("42.7.3"))
        assertTrue(r.contains("42.7.4"))
        assertTrue(r.contains("42.7.5"))
        assertTrue(r.contains("42.7.6"))
        assertEquals(false, r.contains("42.7.7"))
    }

    @Test fun `VersionRange accepts no-space syntax`() {
        // GHSA sometimes emits `>42.2.0` with no space — observed in real data.
        val r = VersionRange.parse(">42.2.0")
        assertTrue(r.contains("42.7.10"))
        assertEquals(false, r.contains("42.2.0"))
    }

    @Test fun `VersionRange treats empty input as matches-nothing`() {
        val r = VersionRange.parse("")
        assertEquals(false, r.contains("42.7.5"))
    }

    /* ----- SecurityAdvisoryFetcher.parse --------------------------------- */

    @Test fun `parse handles the single-array GHSA response shape`() {
        val json = """
            [
              {
                "cve_id": "CVE-2026-42198",
                "ghsa_id": "GHSA-98qh-xjc8-98pq",
                "severity": "high",
                "vulnerabilities": [
                  {
                    "vulnerable_version_range": "< 42.7.11",
                    "patched_versions": "42.7.11"
                  }
                ]
              }
            ]
        """.trimIndent()
        val advisories = SecurityAdvisoryFetcher.parse(json)
        assertEquals(1, advisories.size)
        val a = advisories.single()
        assertEquals("CVE-2026-42198", a.cveId)
        assertEquals("GHSA-98qh-xjc8-98pq", a.ghsaId)
        assertEquals(1, a.vulnerabilities.size)
        assertEquals("42.7.11", a.vulnerabilities.single().patchedVersion)
        assertTrue(a.vulnerabilities.single().vulnerableRange.contains("42.7.10"))
    }

    @Test fun `parse joins paginated arrays emitted back-to-back by gh --paginate`() {
        // `gh api --paginate` concatenates page JSON arrays without an outer wrapper.
        val json = """
            [{"cve_id": "CVE-A", "ghsa_id": "GHSA-A", "severity": "low",
              "vulnerabilities": [{"vulnerable_version_range": "< 1.0.0", "patched_versions": "1.0.0"}]}]
            [{"cve_id": "CVE-B", "ghsa_id": "GHSA-B", "severity": "high",
              "vulnerabilities": [{"vulnerable_version_range": "< 2.0.0", "patched_versions": "2.0.0"}]}]
        """.trimIndent()
        val advisories = SecurityAdvisoryFetcher.parse(json)
        assertEquals(listOf("CVE-A", "CVE-B"), advisories.map { it.cveId })
    }

    @Test fun `parse picks the first patched_versions entry when comma-separated`() {
        // CVE-2024-1597's entries list two patches per branch, e.g. "42.2.28, 42.2.28.jre7".
        val json = """
            [{"cve_id": "CVE-2024-1597", "ghsa_id": "GHSA-24rp-q3w6-vc56", "severity": "critical",
              "vulnerabilities": [
                {"vulnerable_version_range": "< 42.2.28", "patched_versions": "42.2.28, 42.2.28.jre7"}
              ]}]
        """.trimIndent()
        val a = SecurityAdvisoryFetcher.parse(json).single()
        assertEquals("42.2.28", a.vulnerabilities.single().patchedVersion)
    }

    @Test fun `parse tolerates empty input`() {
        assertEquals(emptyList<SecurityAdvisory>(), SecurityAdvisoryFetcher.parse(""))
    }

    @Test fun `parse extracts CVSS score from cvss-dot-score`() {
        // Real GHSA payload has `cvss: { score: 7.5, vector_string: "..." }`.
        val json = """
            [{"cve_id": "CVE-X", "ghsa_id": "GHSA-X", "severity": "high",
              "cvss": {"score": 7.5, "vector_string": "CVSS:3.1/AV:N/..."},
              "vulnerabilities": [{"vulnerable_version_range": "< 1.0.0", "patched_versions": "1.0.0"}]}]
        """.trimIndent()
        assertEquals("7.5", SecurityAdvisoryFetcher.parse(json).single().cvssScore)
    }

    @Test fun `parse formats whole-number CVSS without a trailing zero`() {
        // CVSS 9.0 and 7.0 happen — render as "9" / "7", not "9.0" / "7.0".
        val json = """
            [{"cve_id": "CVE-X", "ghsa_id": "GHSA-X", "severity": "high",
              "cvss": {"score": 9.0, "vector_string": ""},
              "vulnerabilities": []}]
        """.trimIndent()
        assertEquals("9", SecurityAdvisoryFetcher.parse(json).single().cvssScore)
    }

    @Test fun `parse leaves CVSS empty when the advisory carries no cvss block`() {
        // Older advisories were sometimes published without a CVSS rating.
        val json = """
            [{"cve_id": "CVE-X", "ghsa_id": "GHSA-X", "severity": "high",
              "vulnerabilities": [{"vulnerable_version_range": "< 1.0.0", "patched_versions": "1.0.0"}]}]
        """.trimIndent()
        assertEquals("", SecurityAdvisoryFetcher.parse(json).single().cvssScore)
    }

    @Test fun `classifier row inherits parent line status`() {
        val facts = GitFacts(
            branchIds = listOf("42.2.x"),
            tagsByBranch = mapOf("42.2.x" to listOf(
                TaggedRelease("42.2.29", "2023-06-01"),
            )),
        )
        val overlay = Overlay(
            supportWindowYears = 5,
            minJava = listOf(Breakpoint("42.0.0", "8")),
            minPostgresql = listOf(Breakpoint("42.0.0", "8.4")),
            classifiers = listOf(Classifier("jre7", "42.2.x", "42.2.29", "7")),
        )
        val rows = buildRows(facts, overlay, LocalDate.of(2025, 6, 1))
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.versionRange == "42.2.29.jre7" && it.minJava == "7" },
            "expected classifier row, got: $rows")
    }
}
