/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated structured data about a single {@code PGProperty} enum
 * constant: identity, annotation-derived metadata, and runtime metadata
 * pulled by reflection from the loaded enum value.
 *
 * <p>Populated in two phases — first by {@link ClassFileReader} (ASM,
 * reads CLASS-retention annotations from bytecode), then by
 * {@link ReflectionEnricher} (reads {@code name / default / description /
 * choices / required} via reflection on the loaded enum).
 */
final class PropertyRecord {

    /** Field name on the enum, e.g. {@code "ADAPTIVE_FETCH"}. */
    final String fieldName;

    /* ----- from @PgApi ----- */
    String status;             // STABLE, EXPERIMENTAL, ..., DEPRECATED, HIDDEN
    String introducedIn = "";
    String deprecatedIn = "";
    String hiddenIn = "";

    /* ----- from @PgTags ----- */
    final List<String> tags = new ArrayList<>();

    /* ----- from @PgPropertyType ----- */
    String type;               // BOOLEAN, INT, ..., DURATION_SECONDS, ...

    /* ----- from the plain JDK @Deprecated marker ----- */
    boolean hasJavaDeprecated;

    /* ----- from reflection on the loaded PGProperty enum value ----- */
    String name;               // PGProperty.getName(), e.g. "adaptiveFetch"
    String defaultValue;       // may be null
    String description;
    String[] choices;          // may be null; non-null when getChoices() != null
    boolean required;

    PropertyRecord(String fieldName) {
        this.fieldName = fieldName;
    }
}
