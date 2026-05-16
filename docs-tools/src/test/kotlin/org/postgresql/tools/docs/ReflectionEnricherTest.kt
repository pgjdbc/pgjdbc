/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.postgresql.PGProperty

class ReflectionEnricherTest {

    @Test fun `populates name, default, description, required from a known PGProperty`() {
        val knownField = PGProperty.USER.name        // "USER"
        val knownName = PGProperty.USER.getName()    // "user"

        val record = PropertyRecord(knownField)
        ReflectionEnricher.enrich(listOf(record))

        assertEquals(knownName, record.name)
        assertEquals(PGProperty.USER.description, record.description)
        assertEquals(PGProperty.USER.isRequired, record.required)
        // USER has no default — must remain null after enrichment.
        assertEquals(PGProperty.USER.defaultValue, record.defaultValue)
    }

    @Test fun `populates choices when the enum value carries them`() {
        val record = PropertyRecord(PGProperty.AUTOSAVE.name)
        ReflectionEnricher.enrich(listOf(record))
        // Either non-null array with entries, or null — we just verify it
        // mirrors PGProperty.getChoices() without exception.
        assertEquals(
            PGProperty.AUTOSAVE.choices?.toList(),
            record.choices?.toList(),
        )
    }

    @Test fun `unknown fieldName leaves all reflection fields untouched`() {
        val record = PropertyRecord("DOES_NOT_EXIST")
        ReflectionEnricher.enrich(listOf(record))
        assertNull(record.name)
        assertNull(record.defaultValue)
        assertNull(record.description)
    }
}
