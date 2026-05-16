/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

/**
 * Aggregated structured data about a single `PGProperty` enum constant:
 * identity, annotation-derived metadata, and runtime metadata pulled by
 * reflection from the loaded enum value.
 *
 * Populated in two phases — first by [ClassFileReader] (ASM, reads
 * CLASS-retention annotations from bytecode), then by [ReflectionEnricher]
 * (reads `name / default / description / choices / required` via reflection
 * on the loaded enum). All fields except [fieldName] start unset.
 */
internal class PropertyRecord(
    /** Field name on the enum, e.g. `"ADAPTIVE_FETCH"`. */
    val fieldName: String,
) {
    /* ----- from @PgApi ----- */
    var status: String? = null           // STABLE, EXPERIMENTAL, …, DEPRECATED, HIDDEN
    var introducedIn: String = ""
    var deprecatedIn: String = ""
    var hiddenIn: String = ""

    /* ----- from @PgTags ----- */
    val tags: MutableList<String> = mutableListOf()

    /* ----- from @PgPropertyType ----- */
    var type: String? = null             // BOOLEAN, INT, …, DURATION_SECONDS, …

    /* ----- from the plain JDK @Deprecated marker ----- */
    var hasJavaDeprecated: Boolean = false

    /* ----- from reflection on the loaded PGProperty enum value ----- */
    var name: String? = null             // PGProperty.getName(), e.g. "adaptiveFetch"
    var defaultValue: String? = null     // may be null
    var description: String? = null
    var choices: Array<String>? = null   // null when getChoices() returns null
    var required: Boolean = false
}
