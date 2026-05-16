/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

import org.postgresql.PGProperty

/**
 * Pulls per-property runtime metadata (`getName()`, `getDefaultValue()`,
 * `getDescription()`, `getChoices()`, `isRequired()`) from the loaded
 * [PGProperty] enum and attaches it to [PropertyRecord] instances
 * previously populated with annotation data by [ClassFileReader].
 *
 * Records are matched by enum field name ([PGProperty.name]). A field
 * declared in the source but missing from the loaded enum, or vice-versa,
 * indicates either a stale `.class` file or a classpath problem and is
 * reported through [Validator].
 */
internal object ReflectionEnricher {
    fun enrich(records: List<PropertyRecord>) {
        val byFieldName: Map<String, PGProperty> =
            PGProperty.entries.associateBy { it.name }

        for (r in records) {
            val p = byFieldName[r.fieldName] ?: continue
            // p.getName() is shadowed by Enum.name; reach it through the
            // getter call site.
            r.name = p.getName()
            r.defaultValue = p.defaultValue
            r.description = p.description
            r.choices = p.choices
            r.required = p.isRequired
        }
    }
}
