/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cross-references annotation data and runtime data on each
 * {@link PropertyRecord} and produces a (possibly empty) list of
 * complaints suitable for surfacing to a human contributor.
 *
 * <p>The validator never short-circuits: it collects every issue across
 * every property, so a contributor fixing N missing annotations can do
 * so in a single pass instead of N gradle invocations.
 *
 * <p>For each issue the validator attempts to suggest a fix. Where the
 * value can be inferred with high confidence (notably {@code @PgPropertyType}
 * from the default/choices) the suggestion is concrete; otherwise the
 * suggestion is a placeholder that forces a deliberate human choice.
 */
final class Validator {

    private static final Pattern INTEGER = Pattern.compile("-?\\d+");

    private Validator() {
        // utility class
    }

    /** A single complaint about a single property, with a printable section. */
    static final class Complaint {
        final String fieldName;
        final List<String> missing = new ArrayList<>();
        final List<String> consistency = new ArrayList<>();
        final List<String> suggestion = new ArrayList<>();

        Complaint(String fieldName) {
            this.fieldName = fieldName;
        }

        boolean isEmpty() {
            return missing.isEmpty() && consistency.isEmpty();
        }
    }

    static List<Complaint> validate(List<PropertyRecord> records) {
        List<Complaint> complaints = new ArrayList<>();
        for (PropertyRecord r : records) {
            Complaint c = validateOne(r);
            if (!c.isEmpty()) {
                complaints.add(c);
            }
        }
        return complaints;
    }

    private static Complaint validateOne(PropertyRecord r) {
        Complaint c = new Complaint(r.fieldName);

        // 1. Presence: each PGProperty must declare all three annotations.
        if (r.status == null) {
            c.missing.add("@PgApi");
        }
        if (r.tags.isEmpty()) {
            c.missing.add("@PgTags");
        }
        if (r.type == null) {
            c.missing.add("@PgPropertyType");
        }

        // 2. Cross-consistency. Only meaningful when @PgApi is present.
        if (r.status != null) {
            if (r.introducedIn.isEmpty()) {
                c.consistency.add("@PgApi.introducedIn is required (set to the pgjdbc "
                    + "version in which this property first appeared)");
            }

            if ("DEPRECATED".equals(r.status)) {
                if (r.deprecatedIn.isEmpty()) {
                    c.consistency.add("status = DEPRECATED requires @PgApi.deprecatedIn");
                }
                if (!r.hasJavaDeprecated) {
                    c.consistency.add("status = DEPRECATED requires the plain "
                        + "@Deprecated marker as well, so the compiler/IDE warn");
                }
            } else if (r.hasJavaDeprecated) {
                c.consistency.add("@Deprecated is present but @PgApi.status is "
                    + r.status + "; bring them into sync");
            }

            if ("HIDDEN".equals(r.status)) {
                if (r.deprecatedIn.isEmpty()) {
                    c.consistency.add("status = HIDDEN requires @PgApi.deprecatedIn "
                        + "(HIDDEN must pass through DEPRECATED first)");
                }
                if (r.hiddenIn.isEmpty()) {
                    c.consistency.add("status = HIDDEN requires @PgApi.hiddenIn");
                }
            }
        }

        // 3. Compose a suggested patch when something is missing.
        if (!c.missing.isEmpty()) {
            c.suggestion.addAll(buildSuggestion(r, c.missing));
        }
        return c;
    }

    /* ----- Suggestion logic -------------------------------------------------- */

    private static List<String> buildSuggestion(PropertyRecord r, List<String> missing) {
        List<String> lines = new ArrayList<>();
        if (missing.contains("@PgApi")) {
            lines.add("@PgApi(status = PgApi.Status.STABLE, introducedIn = \"TODO\")"
                + "                 // verify status; introducedIn from git history");
        }
        if (missing.contains("@PgTags")) {
            String tagHint = inferTags(r);
            lines.add("@PgTags(" + tagHint + ")                                          "
                + "// pick from PgTags.Tag");
        }
        if (missing.contains("@PgPropertyType")) {
            String inferred = inferType(r);
            lines.add("@PgPropertyType(PgPropertyType.Kind." + inferred + ")"
                + "                                       // inferred from default/choices");
        }
        return lines;
    }

    /** Best-effort inference of @PgPropertyType from default and choices. */
    private static String inferType(PropertyRecord r) {
        if (r.choices != null && r.choices.length > 0) {
            return "ENUM";
        }
        String def = r.defaultValue;
        if ("true".equalsIgnoreCase(def) || "false".equalsIgnoreCase(def)) {
            return "BOOLEAN";
        }
        if (def != null && INTEGER.matcher(def).matches()) {
            // Could be INT/LONG, or any DURATION/SIZE variant — caller decides.
            return "INT /* or DURATION_SECONDS / DURATION_MILLIS / SIZE_BYTES */";
        }
        return "STRING";
    }

    /** Heuristic name-based tag hint. Always wrapped in a comment to make
     *  it clear the contributor still needs to choose. */
    private static String inferTags(PropertyRecord r) {
        String n = r.name == null ? r.fieldName : r.name;
        String lower = n.toLowerCase(Locale.ROOT);
        // Map of substring -> candidate tag, in priority order.
        Map<String, String> hints = new LinkedHashMap<>();
        hints.put("kerberos",  "PgTags.Tag.KERBEROS_GSS");
        hints.put("gss",       "PgTags.Tag.KERBEROS_GSS");
        hints.put("sspi",      "PgTags.Tag.KERBEROS_GSS");
        hints.put("jaas",      "PgTags.Tag.KERBEROS_GSS");
        hints.put("ssl",       "PgTags.Tag.SSL");
        hints.put("channel",   "PgTags.Tag.SSL");
        hints.put("scram",     "PgTags.Tag.AUTHENTICATION");
        hints.put("auth",      "PgTags.Tag.AUTHENTICATION");
        hints.put("password",  "PgTags.Tag.AUTHENTICATION");
        hints.put("timeout",   "PgTags.Tag.TIMEOUT");
        hints.put("tcp",       "PgTags.Tag.NETWORK");
        hints.put("socket",    "PgTags.Tag.NETWORK");
        hints.put("buffer",    "PgTags.Tag.NETWORK");
        hints.put("fetch",     "PgTags.Tag.FETCH");
        hints.put("prepare",   "PgTags.Tag.PREPARED_STATEMENTS");
        hints.put("batch",     "PgTags.Tag.BATCH");
        hints.put("binary",    "PgTags.Tag.BINARY_TRANSFER");
        hints.put("metadata",  "PgTags.Tag.METADATA");
        hints.put("encoding",  "PgTags.Tag.COMPATIBILITY");
        hints.put("replication", "PgTags.Tag.REPLICATION");
        hints.put("log",       "PgTags.Tag.LOGGING");
        hints.put("readonly",  "PgTags.Tag.TRANSACTION");
        hints.put("autosave",  "PgTags.Tag.TRANSACTION");
        hints.put("savepoint", "PgTags.Tag.TRANSACTION");
        hints.put("host",      "PgTags.Tag.FAILOVER");
        hints.put("balance",   "PgTags.Tag.FAILOVER");

        for (Map.Entry<String, String> e : hints.entrySet()) {
            if (lower.contains(e.getKey())) {
                return "/* candidate: */ " + e.getValue();
            }
        }
        return "/* pick from PgTags.Tag */";
    }

    /* ----- Formatting -------------------------------------------------------- */

    /** Emit the friendly fail-mode report. Returns the number of complaints
     *  printed (= exit code conventionally != 0 if non-zero). */
    static int printReport(List<Complaint> complaints, PrintStream out) {
        if (complaints.isEmpty()) {
            return 0;
        }
        out.println();
        out.println("docs:generateProperties FAILED — annotation metadata is incomplete.");
        out.println();
        out.println(complaints.size()
            + " PGProperty value" + (complaints.size() == 1 ? "" : "s")
            + " need attention:");
        out.println();

        for (Complaint c : complaints) {
            out.println("  " + c.fieldName);
            if (!c.missing.isEmpty()) {
                out.println("    missing: " + String.join(", ", c.missing));
            }
            for (String line : c.consistency) {
                out.println("    error:   " + line);
            }
            if (!c.suggestion.isEmpty()) {
                out.println();
                out.println("    Suggested patch (verify each line):");
                for (String s : c.suggestion) {
                    out.println("        " + s);
                }
            }
            out.println();
        }

        out.println("Source file: pgjdbc/src/main/java/org/postgresql/PGProperty.java");
        out.println("Available tags: " + Arrays.toString(tagNames()));
        out.println("Available types: " + Arrays.toString(typeNames()));
        return complaints.size();
    }

    private static String[] tagNames() {
        return new String[]{"SSL", "AUTHENTICATION", "KERBEROS_GSS", "CONNECTION",
            "TIMEOUT", "NETWORK", "FAILOVER", "FETCH", "PREPARED_STATEMENTS",
            "BATCH", "BINARY_TRANSFER", "METADATA", "TYPE_HANDLING",
            "REPLICATION", "LOGGING", "TRANSACTION", "COMPATIBILITY"};
    }

    private static String[] typeNames() {
        return new String[]{"BOOLEAN", "INT", "LONG", "STRING", "ENUM", "CLASS",
            "DURATION_SECONDS", "DURATION_MILLIS", "SIZE_BYTES", "SIZE_EXPRESSION"};
    }
}
