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
