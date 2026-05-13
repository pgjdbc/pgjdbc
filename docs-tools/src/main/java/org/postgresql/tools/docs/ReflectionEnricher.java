/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs;

import org.postgresql.PGProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls per-property runtime metadata ({@code getName()},
 * {@code getDefaultValue()}, {@code getDescription()}, {@code getChoices()},
 * {@code isRequired()}) from the loaded {@link PGProperty} enum and
 * attaches it to {@link PropertyRecord} instances previously populated
 * with annotation data by {@link ClassFileReader}.
 *
 * <p>Records are matched by enum field name ({@code PGProperty.name()}).
 * A field declared in the source but missing from the loaded enum, or
 * vice-versa, indicates either a stale {@code .class} file or a
 * classpath problem and is reported through the {@link Validator}.
 */
final class ReflectionEnricher {

    private ReflectionEnricher() {
        // utility class
    }

    static void enrich(List<PropertyRecord> records) {
        Map<String, PGProperty> byFieldName = new HashMap<>();
        for (PGProperty p : PGProperty.values()) {
            byFieldName.put(p.name(), p);
        }

        for (PropertyRecord r : records) {
            PGProperty p = byFieldName.get(r.fieldName);
            if (p == null) {
                // Validator will flag this; nothing to enrich here.
                continue;
            }
            r.name         = p.getName();
            r.defaultValue = p.getDefaultValue();
            r.description  = p.getDescription();
            r.choices      = p.getChoices();
            r.required     = p.isRequired();
        }
    }
}
