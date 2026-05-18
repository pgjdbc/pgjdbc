/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class OverlayLoaderTest {

    @TempDir lateinit var tmp: Path

    @Test fun `missing overlay yields defaults`() {
        val overlay = OverlayLoader.load(tmp.resolve("nope.yaml"))
        assertEquals(5, overlay.supportWindowYears)
        assertEquals(emptyList<Breakpoint>(), overlay.minJava)
    }

    @Test fun `every top-level key parses into the Overlay model`() {
        val yaml = tmp.resolve("overlay.yaml")
        yaml.writeText(
            """
            support_window_years: 3
            min_java:
              - {pgjdbc: 42.0.0, java: 8}
              - {pgjdbc: 42.7.0, java: 11}
            min_postgresql:
              - {pgjdbc: 42.0.0, postgresql: 8.4}
            classifiers:
              - {id: jre7, branch: 42.2.x, last_version: 42.2.29, min_java: 7}
            tested_java:
              42.7.x: [8, 11, 17]
            tested_postgresql:
              42.7.x: ["9.1", "10", "11", "12", "13"]
            branch_notes:
              42.2.x: legacy
            """.trimIndent()
        )

        val overlay = OverlayLoader.load(yaml)
        assertEquals(3, overlay.supportWindowYears)
        assertEquals(
            listOf(Breakpoint("42.0.0", "8"), Breakpoint("42.7.0", "11")),
            overlay.minJava,
        )
        assertEquals(listOf(Breakpoint("42.0.0", "8.4")), overlay.minPostgresql)
        assertEquals(
            listOf(Classifier("jre7", "42.2.x", "42.2.29", "7")),
            overlay.classifiers,
        )
        assertEquals(mapOf("42.7.x" to listOf("8", "11", "17")), overlay.testedJava)
        assertEquals(
            mapOf("42.7.x" to listOf("9.1", "10", "11", "12", "13")),
            overlay.testedPostgresql,
        )
        assertEquals(mapOf("42.2.x" to "legacy"), overlay.branchNotes)
    }

    @Test fun `omitted optional sections fall back to empty collections`() {
        val yaml = tmp.resolve("overlay.yaml")
        yaml.writeText("support_window_years: 7\n")

        val overlay = OverlayLoader.load(yaml)
        assertEquals(7, overlay.supportWindowYears)
        assertEquals(emptyList<Breakpoint>(), overlay.minJava)
        assertEquals(emptyList<Classifier>(), overlay.classifiers)
        assertEquals(emptyMap<String, List<String>>(), overlay.testedJava)
    }
}
