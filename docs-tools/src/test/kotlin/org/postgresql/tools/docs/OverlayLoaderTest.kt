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

    @Test fun `missing overlay yields the default empty model`() {
        val overlay = OverlayLoader.load(tmp.resolve("nope.yaml"))
        assertEquals(emptyList<Classifier>(), overlay.classifiers)
    }

    @Test fun `classifiers block parses into the Overlay model`() {
        val yaml = tmp.resolve("overlay.yaml")
        yaml.writeText(
            """
            classifiers:
              - id: jre7
                branch: 42.2.x
                last_version: 42.2.29
              - id: jre6
                branch: 42.2.x
                last_version: 42.2.27
            """.trimIndent()
        )

        val overlay = OverlayLoader.load(yaml)
        assertEquals(
            listOf(
                Classifier(branch = "42.2.x", lastVersion = "42.2.29", id = "jre7"),
                Classifier(branch = "42.2.x", lastVersion = "42.2.27", id = "jre6"),
            ),
            overlay.classifiers,
        )
    }

    @Test fun `omitted classifiers section falls back to an empty list`() {
        val yaml = tmp.resolve("overlay.yaml")
        yaml.writeText("future_field: ignored\n")

        val overlay = OverlayLoader.load(yaml)
        assertEquals(emptyList<Classifier>(), overlay.classifiers)
    }
}
