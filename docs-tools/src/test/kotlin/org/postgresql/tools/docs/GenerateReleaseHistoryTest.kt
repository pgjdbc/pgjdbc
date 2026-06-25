/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenerateReleaseHistoryTest {

    /* ----- versionKey & ordering ----------------------------------------- */

    @Test fun `versionKey orders multi-segment versions numerically`() {
        // 42.2.10 must sort after 42.2.9, not lexicographically before it.
        assertTrue(versionKey("42.2.10") > versionKey("42.2.9"))
        assertTrue(versionKey("42.7.0") > versionKey("42.6.99"))
        assertTrue(versionKey("42.10.0") > versionKey("42.9.99"))
    }

    @Test fun `versionKey treats x as zero in branch ids`() {
        // Branch ids like "42.7.x" must sort consistently with their tags.
        assertEquals(versionKey("42.7.0"), versionKey("42.7.x"))
    }

    @Test fun `versionDescending puts the highest version first`() {
        val sorted = listOf("42.2.x", "42.7.x", "42.5.x").sortedWith(versionDescending())
        assertEquals(listOf("42.7.x", "42.5.x", "42.2.x"), sorted)
    }

    /* ----- collectGitFacts from tag lines --------------------------------- */

    @Test fun `collectGitFacts groups tags by branch and sorts ascending`() {
        val lines = listOf(
            "REL42.7.0\t2024-01-01",
            "REL42.7.11\t2026-04-28",
            "REL42.6.0\t2023-03-17",
            "REL42.6.2\t2024-03-13",
        )
        val facts = collectGitFacts(lines)
        assertEquals(listOf("42.7.x", "42.6.x"), facts.branchIds)
        assertEquals(
            listOf(TaggedRelease("42.7.0", "2024-01-01"), TaggedRelease("42.7.11", "2026-04-28")),
            facts.tagsByBranch["42.7.x"],
        )
    }

    @Test fun `collectGitFacts ignores RC and non-release tags`() {
        val lines = listOf(
            "REL42.7.11-rc1\t2026-04-01",
            "REL42.7.11\t2026-04-28",
            "some-other-tag\t2026-01-01",
        )
        val facts = collectGitFacts(lines)
        assertEquals(listOf("42.7.x"), facts.branchIds)
        assertEquals(1, facts.tagsByBranch["42.7.x"]?.size)
    }

    /* ----- buildRows ----------------------------------------------------- */

    @Test fun `buildRows emits one row per release line in branch order`() {
        val facts = GitFacts(
            branchIds = listOf("42.7.x", "42.6.x"),
            tagsByBranch = mapOf(
                "42.7.x" to listOf(
                    TaggedRelease("42.7.0", "2024-01-01"),
                    TaggedRelease("42.7.11", "2026-04-28"),
                ),
                "42.6.x" to listOf(
                    TaggedRelease("42.6.0", "2023-03-17"),
                    TaggedRelease("42.6.2", "2024-03-13"),
                ),
            ),
        )
        val rows = buildRows(facts, Overlay())
        assertEquals(
            listOf(
                Row("42.7.x", "42.7.0-42.7.11", "2026-04-28"),
                Row("42.6.x", "42.6.0-42.6.2", "2024-03-13"),
            ),
            rows,
        )
    }

    @Test fun `buildRows collapses a single-tag line to that tag without a range`() {
        val facts = GitFacts(
            branchIds = listOf("42.8.x"),
            tagsByBranch = mapOf("42.8.x" to listOf(TaggedRelease("42.8.0", "2026-06-01"))),
        )
        assertEquals(
            listOf(Row("42.8.x", "42.8.0", "2026-06-01")),
            buildRows(facts, Overlay()),
        )
    }

    @Test fun `buildRows skips branches whose tag list is empty`() {
        // A branch declared in branchIds without any tags (e.g. a release
        // branch cut but never tagged) is silently dropped rather than
        // emitting a row with an empty version_range.
        val facts = GitFacts(
            branchIds = listOf("42.9.x", "42.7.x"),
            tagsByBranch = mapOf(
                "42.9.x" to emptyList(),
                "42.7.x" to listOf(TaggedRelease("42.7.0", "2024-01-01")),
            ),
        )
        val rows = buildRows(facts, Overlay())
        assertEquals(listOf("42.7.x"), rows.map { it.releaseLine })
    }

    @Test fun `buildRows appends classifier rows under their parent line`() {
        val facts = GitFacts(
            branchIds = listOf("42.2.x"),
            tagsByBranch = mapOf(
                "42.2.x" to listOf(
                    TaggedRelease("42.2.0", "2018-01-17"),
                    TaggedRelease("42.2.29", "2024-03-13"),
                ),
            ),
        )
        val overlay = Overlay(
            classifiers = listOf(
                Classifier(branch = "42.2.x", lastVersion = "42.2.29", id = "jre7"),
                Classifier(branch = "42.2.x", lastVersion = "42.2.27", id = "jre6"),
            ),
        )
        val rows = buildRows(facts, overlay)
        assertEquals(
            listOf("42.2.0-42.2.29", "42.2.29.jre7", "42.2.27.jre6"),
            rows.map { it.versionRange },
        )
    }

    @Test fun `buildRows sorts rows within a line by released descending`() {
        // Main row and classifier rows on the same line are ordered by
        // their `released` date, newest first.
        val facts = GitFacts(
            branchIds = listOf("42.2.x"),
            tagsByBranch = mapOf(
                "42.2.x" to listOf(
                    TaggedRelease("42.2.27", "2023-01-01"),
                    TaggedRelease("42.2.29", "2024-03-13"),
                ),
            ),
        )
        val overlay = Overlay(
            classifiers = listOf(
                Classifier(branch = "42.2.x", lastVersion = "42.2.27", id = "jre6"),
            ),
        )
        val rows = buildRows(facts, overlay)
        // Main row's `released` (2024) is newer than the classifier's
        // (2023), so the main row stays first.
        assertEquals(listOf("2024-03-13", "2023-01-01"), rows.map { it.released })
    }

    @Test fun `classifier without a matching tag gets an empty released date`() {
        // Defensive: if a classifier's last_version is not in the tag list
        // (typo or stale overlay), the row still emits with an empty date
        // rather than crashing the build.
        val facts = GitFacts(
            branchIds = listOf("42.2.x"),
            tagsByBranch = mapOf("42.2.x" to listOf(TaggedRelease("42.2.29", "2024-03-13"))),
        )
        val overlay = Overlay(
            classifiers = listOf(
                Classifier(branch = "42.2.x", lastVersion = "42.2.999", id = "jre7"),
            ),
        )
        val classifierRow = buildRows(facts, overlay).single { "jre7" in it.versionRange }
        assertEquals("", classifierRow.released)
    }
}
