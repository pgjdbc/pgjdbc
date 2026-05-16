/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

/**
 * Builds a fully-populated [PropertyRecord] with sensible defaults so
 * individual tests only override the fields they care about.
 */
internal fun propertyRecord(
    fieldName: String = "ADAPTIVE_FETCH",
    name: String? = "adaptiveFetch",
    type: String? = "BOOLEAN",
    defaultValue: String? = "false",
    description: String? = "Specifies if number of rows fetched in ResultSet should be adaptive.",
    status: String? = "STABLE",
    introducedIn: String = "42.2.11",
    deprecatedIn: String = "",
    hiddenIn: String = "",
    hasJavaDeprecated: Boolean = false,
    tags: List<String> = listOf("FETCH", "NETWORK"),
    choices: Array<String>? = null,
    required: Boolean = false,
): PropertyRecord = PropertyRecord(fieldName).apply {
    this.name = name
    this.type = type
    this.defaultValue = defaultValue
    this.description = description
    this.status = status
    this.introducedIn = introducedIn
    this.deprecatedIn = deprecatedIn
    this.hiddenIn = hiddenIn
    this.hasJavaDeprecated = hasJavaDeprecated
    this.tags += tags
    this.choices = choices
    this.required = required
}
