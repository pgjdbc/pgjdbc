/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Generates {@code docs/data/connection-properties.yaml} from
 * {@code PGProperty.class} plus its {@code @PgApi} / {@code @PgTags} /
 * {@code @PgPropertyType} annotations.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>{@link ClassFileReader} — ASM reads CLASS-retention annotations
 *       and produces one {@link PropertyRecord} per enum constant.
 *   <li>{@link ReflectionEnricher} — fills runtime metadata
 *       ({@code name}, {@code default}, {@code description},
 *       {@code choices}, {@code required}) by reflection.
 *   <li>{@link Validator} — checks completeness and cross-consistency;
 *       if anything is wrong, prints a friendly per-property report
 *       with a suggested patch and exits non-zero.
 *   <li>{@link YamlEmitter} — writes the YAML file (parent dirs auto-
 *       created).
 * </ol>
 *
 * <h2>Invocation</h2>
 * <pre>
 *   GenerateProperties &lt;pgjdbc-main-classes-dir&gt; &lt;output-yaml-path&gt;
 * </pre>
 * The Gradle task {@code :docs-tools:generateProperties} passes both
 * arguments, so end users invoke it as
 * {@code ./gradlew :docs-tools:generateProperties}.
 */
public final class GenerateProperties {

    private static final String PG_PROPERTY_CLASS_RELATIVE_PATH =
        "org/postgresql/PGProperty.class";

    /** Allow partial output while the mass annotation pass is in progress.
     *  When set, validation failures still print the friendly report but
     *  do not stop YAML emission of the properties that ARE fully annotated. */
    private static final String ALLOW_INCOMPLETE_FLAG = "--allow-incomplete";

    private GenerateProperties() {
        // utility class
    }

    public static void main(String[] args) throws IOException {
        boolean allowIncomplete = false;
        java.util.List<String> positional = new java.util.ArrayList<>();
        for (String a : args) {
            if (ALLOW_INCOMPLETE_FLAG.equals(a)) {
                allowIncomplete = true;
            } else {
                positional.add(a);
            }
        }

        if (positional.size() < 2) {
            System.err.println("usage: GenerateProperties [--allow-incomplete] "
                + "<pgjdbc-main-classes-dir> <output-yaml-path>");
            System.exit(2);
        }

        Path classesDir = Paths.get(positional.get(0));
        Path outputYaml = Paths.get(positional.get(1));

        Path pgProperty = classesDir.resolve(PG_PROPERTY_CLASS_RELATIVE_PATH);
        if (!Files.isRegularFile(pgProperty)) {
            System.err.println("PGProperty.class not found at " + pgProperty);
            System.err.println("Make sure :postgresql:classes ran first.");
            System.exit(2);
        }

        // 1. Read annotations from bytecode.
        List<PropertyRecord> records = ClassFileReader.read(pgProperty);

        // 2. Enrich with runtime field data.
        ReflectionEnricher.enrich(records);

        // 3. Validate. On failure, print the friendly report and either exit
        //    non-zero (strict mode) or filter incomplete records out and
        //    proceed (--allow-incomplete, for use during the mass annotation
        //    pass).
        List<Validator.Complaint> complaints = Validator.validate(records);
        if (!complaints.isEmpty()) {
            Validator.printReport(complaints, System.err);
            if (!allowIncomplete) {
                System.exit(1);
            }
            java.util.Set<String> incomplete = new java.util.HashSet<>();
            for (Validator.Complaint c : complaints) {
                incomplete.add(c.fieldName);
            }
            records.removeIf(r -> incomplete.contains(r.fieldName));
            System.err.println();
            System.err.println("--allow-incomplete: emitting YAML for "
                + records.size() + " fully-annotated propert"
                + (records.size() == 1 ? "y" : "ies") + ".");
        }

        // 4. Emit YAML.
        YamlEmitter.writeTo(records, outputYaml);
        System.out.println("Wrote " + records.size()
            + " connection properties to " + outputYaml);
    }
}
