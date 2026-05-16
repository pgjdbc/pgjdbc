/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.postgresql.annotations.PgTags
import org.postgresql.annotations.PgPropertyType

class ValidatorTest {

    @Test fun `fully-annotated record produces no complaints`() {
        assertTrue(Validator.validate(listOf(propertyRecord())).isEmpty())
    }

    @Test fun `missing PgApi, PgTags, PgPropertyType all reported in one pass`() {
        val r = PropertyRecord("UNANNOTATED").apply {
            name = "unannotated"
            defaultValue = "hello"
        }
        val complaint = Validator.validate(listOf(r)).single()
        assertEquals(listOf("@PgApi", "@PgTags", "@PgPropertyType"), complaint.missing)
        // Each missing annotation yields one suggestion line, in the same order.
        assertEquals(3, complaint.suggestion.size)
    }

    @Test fun `DEPRECATED status requires deprecatedIn and the Java marker`() {
        val r = propertyRecord(status = "DEPRECATED", hasJavaDeprecated = false)
        val complaint = Validator.validate(listOf(r)).single()
        val joined = complaint.consistency.joinToString("\n")
        assertTrue("deprecatedIn" in joined, joined)
        assertTrue("@Deprecated" in joined, joined)
    }

    @Test fun `HIDDEN requires both deprecatedIn and hiddenIn`() {
        val r = propertyRecord(status = "HIDDEN")
        val complaint = Validator.validate(listOf(r)).single()
        val joined = complaint.consistency.joinToString("\n")
        assertTrue("deprecatedIn" in joined, joined)
        assertTrue("hiddenIn" in joined, joined)
    }

    @Test fun `Java Deprecated on non-DEPRECATED record flagged as inconsistent`() {
        val r = propertyRecord(status = "STABLE", hasJavaDeprecated = true)
        val complaint = Validator.validate(listOf(r)).single()
        assertTrue(complaint.consistency.any { "bring them into sync" in it },
            complaint.consistency.toString())
    }

    @Test fun `inferType picks ENUM when choices present`() {
        val r = PropertyRecord("X").apply {
            name = "x"
            defaultValue = "always"
            choices = arrayOf("always", "never")
        }
        val complaint = Validator.validate(listOf(r)).single()
        assertTrue(complaint.suggestion.any { "PgPropertyType.Kind.ENUM" in it },
            complaint.suggestion.toString())
    }

    @Test fun `inferType picks BOOLEAN for true-false defaults`() {
        val r = PropertyRecord("X").apply {
            name = "x"
            defaultValue = "TRUE"
        }
        val complaint = Validator.validate(listOf(r)).single()
        assertTrue(complaint.suggestion.any { "PgPropertyType.Kind.BOOLEAN" in it },
            complaint.suggestion.toString())
    }

    @Test fun `inferType picks INT for integer defaults`() {
        val r = PropertyRecord("X").apply {
            name = "x"
            defaultValue = "-42"
        }
        val complaint = Validator.validate(listOf(r)).single()
        assertTrue(complaint.suggestion.any { "PgPropertyType.Kind.INT" in it },
            complaint.suggestion.toString())
    }

    @Test fun `inferTags maps SSL substring to SSL hint`() {
        val r = PropertyRecord("SSL_MODE").apply { name = "sslMode" }
        val complaint = Validator.validate(listOf(r)).single()
        assertTrue(complaint.suggestion.any { "PgTags.Tag.SSL" in it },
            complaint.suggestion.toString())
    }

    @Test fun `printReport returns the complaint count`() {
        val report = java.io.ByteArrayOutputStream()
        val complaints = Validator.validate(listOf(propertyRecord(status = null)))
        val n = Validator.printReport(complaints, java.io.PrintStream(report))
        assertEquals(1, n)
        assertFalse(report.toString(Charsets.UTF_8).isEmpty())
    }

    @Test fun `Available tags and types lists are derived from the enums`() {
        // Catches future drift if the report ever stops enumerating values()
        // and goes back to hardcoding names.
        val report = java.io.ByteArrayOutputStream()
        Validator.printReport(
            Validator.validate(listOf(propertyRecord(status = null))),
            java.io.PrintStream(report),
        )
        val text = report.toString(Charsets.UTF_8)
        for (tag in PgTags.Tag.values()) assertTrue(tag.name in text, "missing $tag in:\n$text")
        for (kind in PgPropertyType.Kind.values()) assertTrue(kind.name in text, "missing $kind in:\n$text")
    }
}
