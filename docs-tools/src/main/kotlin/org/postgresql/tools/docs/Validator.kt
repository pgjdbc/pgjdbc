/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

import org.postgresql.annotations.PgTags
import org.postgresql.annotations.PgPropertyType
import java.io.PrintStream
import java.util.*
import kotlin.enums.EnumEntries

/**
 * Cross-references annotation data and runtime data on each
 * [PropertyRecord] and produces a (possibly empty) list of complaints
 * suitable for surfacing to a human contributor.
 *
 * The validator never short-circuits: it collects every issue across
 * every property, so a contributor fixing N missing annotations can do
 * so in a single pass instead of N gradle invocations.
 *
 * For each issue the validator attempts to suggest a fix. Where the
 * value can be inferred with high confidence (notably `@PgPropertyType` from the
 * default/choices) the suggestion is concrete; otherwise the suggestion
 * is a placeholder that forces a deliberate human choice.
 */
internal object Validator {

    private val INTEGER = Regex("-?\\d+")

    /** A single complaint about a single property, with a printable section. */
    class Complaint(val fieldName: String) {
        val missing: MutableList<String> = mutableListOf()
        val consistency: MutableList<String> = mutableListOf()
        val suggestion: MutableList<String> = mutableListOf()

        val isEmpty: Boolean
            get() = missing.isEmpty() && consistency.isEmpty()
    }

    fun validate(records: List<PropertyRecord>): List<Complaint> =
        records.map(::validateOne).filterNot { it.isEmpty }

    private fun validateOne(r: PropertyRecord): Complaint {
        val c = Complaint(r.fieldName)

        // 1. Presence: each PGProperty must declare all three annotations.
        if (r.status == null) c.missing += "@PgApi"
        if (r.tags.isEmpty()) c.missing += "@PgTags"
        if (r.type == null)   c.missing += "@PgPropertyType"

        // 2. Cross-consistency. Only meaningful when @PgApi is present.
        r.status?.let { status ->
            if (r.introducedIn.isEmpty()) {
                c.consistency += "@PgApi.introducedIn is required (set to the pgjdbc " +
                    "version in which this property first appeared)"
            }
            when (status) {
                "DEPRECATED" -> {
                    if (r.deprecatedIn.isEmpty()) {
                        c.consistency += "status = DEPRECATED requires @PgApi.deprecatedIn"
                    }
                    if (!r.hasJavaDeprecated) {
                        c.consistency += "status = DEPRECATED requires the plain " +
                            "@Deprecated marker as well, so the compiler/IDE warn"
                    }
                }
                "HIDDEN" -> {
                    if (r.deprecatedIn.isEmpty()) {
                        c.consistency += "status = HIDDEN requires @PgApi.deprecatedIn " +
                            "(HIDDEN must pass through DEPRECATED first)"
                    }
                    if (r.hiddenIn.isEmpty()) {
                        c.consistency += "status = HIDDEN requires @PgApi.hiddenIn"
                    }
                }
                else -> {
                    if (r.hasJavaDeprecated) {
                        c.consistency += "@Deprecated is present but @PgApi.status is " +
                            "$status; bring them into sync"
                    }
                }
            }
        }

        // 3. Compose a suggested patch when something is missing.
        if (c.missing.isNotEmpty()) {
            c.suggestion += buildSuggestion(r, c.missing)
        }
        return c
    }

    /* ----- Suggestion logic ------------------------------------------------ */

    private fun buildSuggestion(r: PropertyRecord, missing: List<String>): List<String> = buildList {
        if ("@PgApi" in missing) {
            add("@PgApi(status = PgApi.Status.STABLE, introducedIn = \"TODO\")" +
                "                 // verify status; introducedIn from git history")
        }
        if ("@PgTags" in missing) {
            add("@PgTags(${inferTags(r)})                                          " +
                "// pick from PgTags.Tag")
        }
        if ("@PgPropertyType" in missing) {
            add("@PgPropertyType(PgPropertyType.Kind.${inferType(r)})" +
                "                                       // inferred from default/choices")
        }
    }

    /** Best-effort inference of @PgPropertyType from default and choices. The
     *  return value references [PgPropertyType.Kind] constants by name so a
     *  rename in the enum surfaces here at compile time. */
    private fun inferType(r: PropertyRecord): String {
        if (!r.choices.isNullOrEmpty()) return PgPropertyType.Kind.ENUM.name
        val def = r.defaultValue
        if (def.equals("true", ignoreCase = true) || def.equals("false", ignoreCase = true)) {
            return PgPropertyType.Kind.BOOLEAN.name
        }
        if (def != null && INTEGER.matches(def)) {
            // Could be INT/LONG, or any DURATION/SIZE variant — caller decides.
            return "${PgPropertyType.Kind.INT.name} /* or " +
                "${PgPropertyType.Kind.DURATION_SECONDS.name} / " +
                "${PgPropertyType.Kind.DURATION_MILLIS.name} / " +
                "${PgPropertyType.Kind.SIZE_BYTES.name} */"
        }
        return PgPropertyType.Kind.STRING.name
    }

    /** Substring-keyed tag hints, evaluated in priority order. Each value
     *  is a real [PgTags.Tag] enum constant — renaming the enum either
     *  breaks compilation or surfaces here, not in stale string literals. */
    private val TAG_HINTS: List<Pair<String, PgTags.Tag>> = listOf(
        "kerberos"    to PgTags.Tag.KERBEROS_GSS,
        "gss"         to PgTags.Tag.KERBEROS_GSS,
        "sspi"        to PgTags.Tag.KERBEROS_GSS,
        "jaas"        to PgTags.Tag.KERBEROS_GSS,
        "ssl"         to PgTags.Tag.SSL,
        "channel"     to PgTags.Tag.SSL,
        "scram"       to PgTags.Tag.AUTHENTICATION,
        "auth"        to PgTags.Tag.AUTHENTICATION,
        "password"    to PgTags.Tag.AUTHENTICATION,
        "timeout"     to PgTags.Tag.TIMEOUT,
        "tcp"         to PgTags.Tag.NETWORK,
        "socket"      to PgTags.Tag.NETWORK,
        "buffer"      to PgTags.Tag.NETWORK,
        "fetch"       to PgTags.Tag.FETCH,
        "prepare"     to PgTags.Tag.PREPARED_STATEMENTS,
        "batch"       to PgTags.Tag.BATCH,
        "binary"      to PgTags.Tag.BINARY_TRANSFER,
        "metadata"    to PgTags.Tag.METADATA,
        "encoding"    to PgTags.Tag.COMPATIBILITY,
        "replication" to PgTags.Tag.REPLICATION,
        "log"         to PgTags.Tag.LOGGING,
        "readonly"    to PgTags.Tag.TRANSACTION,
        "autosave"    to PgTags.Tag.TRANSACTION,
        "savepoint"   to PgTags.Tag.TRANSACTION,
        "host"        to PgTags.Tag.FAILOVER,
        "balance"     to PgTags.Tag.FAILOVER,
    )

    /** Heuristic name-based tag hint. Always wrapped in a comment to make
     *  it clear the contributor still needs to choose. */
    private fun inferTags(r: PropertyRecord): String {
        val name = (r.name ?: r.fieldName).lowercase(Locale.ROOT)
        val hit = TAG_HINTS.firstOrNull { (substring, _) -> substring in name }
        return if (hit != null) "/* candidate: */ PgTags.Tag.${hit.second.name}"
        else "/* pick from PgTags.Tag */"
    }

    /* ----- Formatting ------------------------------------------------------ */

    /** Emit the friendly fail-mode report. Returns the number of complaints
     *  printed (= exit code conventionally != 0 if non-zero). */
    fun printReport(complaints: List<Complaint>, out: PrintStream): Int {
        if (complaints.isEmpty()) return 0
        out.println()
        out.println("docs:generateProperties FAILED — annotation metadata is incomplete.")
        out.println()
        out.println("${complaints.size} PGProperty value" +
            (if (complaints.size == 1) "" else "s") +
            " need attention:")
        out.println()

        for (c in complaints) {
            out.println("  ${c.fieldName}")
            if (c.missing.isNotEmpty()) {
                out.println("    missing: ${c.missing.joinToString(", ")}")
            }
            for (line in c.consistency) {
                out.println("    error:   $line")
            }
            if (c.suggestion.isNotEmpty()) {
                out.println()
                out.println("    Suggested patch (verify each line):")
                for (s in c.suggestion) {
                    out.println("        $s")
                }
            }
            out.println()
        }

        out.println("Source file: pgjdbc/src/main/java/org/postgresql/PGProperty.java")
        out.println("Available tags: ${enumNames(PgTags.Tag.entries)}")
        out.println("Available types: ${enumNames(PgPropertyType.Kind.entries)}")
        return complaints.size
    }

    /** Format `[A, B, C]` from an enum's `values()` — same shape that
     *  `Arrays.toString(String[])` produced for the prior hardcoded list. */
    private fun enumNames(values: EnumEntries<*>): String =
        values.joinToString(prefix = "[", postfix = "]") { it.name }
}
