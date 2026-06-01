/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

import org.yaml.snakeyaml.Yaml
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal data class Classifier(
    val branch: String,
    val lastVersion: String,
    val id: String,
)

internal data class Overlay(
    val classifiers: List<Classifier> = emptyList(),
)

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
