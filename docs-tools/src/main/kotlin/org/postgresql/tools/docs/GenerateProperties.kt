/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

@file:JvmName("GenerateProperties")

package org.postgresql.tools.docs

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Generates `docs/data/connection-properties.yaml` from `PGProperty.class`
 * plus its `@PgApi` / `@PgTags` / `@PgPropertyType` annotations.
 *
 * Pipeline:
 *  1. [ClassFileReader] — ASM reads CLASS-retention annotations and
 *     produces one [PropertyRecord] per enum constant.
 *  2. [ReflectionEnricher] — fills runtime metadata (`name`, `default`,
 *     `description`, `choices`, `required`) by reflection.
 *  3. [Validator] — checks completeness and cross-consistency; on failure
 *     prints a friendly per-property report with a suggested patch and
 *     exits non-zero.
 *  4. [YamlEmitter] — writes the YAML file (parent dirs auto-created).
 *
 * Invocation: `GenerateProperties [--allow-incomplete] <pgjdbc-main-classes-dir> <output-yaml-path>`.
 * The Gradle task `:docs-tools:generateProperties` supplies both arguments.
 */

private const val PG_PROPERTY_CLASS_RELATIVE_PATH = "org/postgresql/PGProperty.class"

/** Allow partial output while the mass annotation pass is in progress.
 *  When set, validation failures still print the friendly report but do
 *  not stop YAML emission of the properties that ARE fully annotated. */
private const val ALLOW_INCOMPLETE_FLAG = "--allow-incomplete"

fun main(args: Array<String>) {
    val allowIncomplete = ALLOW_INCOMPLETE_FLAG in args
    val positional = args.filterNot { it == ALLOW_INCOMPLETE_FLAG }

    if (positional.size < 2) {
        System.err.println("usage: GenerateProperties [--allow-incomplete] " +
            "<pgjdbc-main-classes-dir> <output-yaml-path>")
        exitProcess(2)
    }

    val classesDir = Paths.get(positional[0])
    val outputYaml = Paths.get(positional[1])

    val pgProperty = classesDir.resolve(PG_PROPERTY_CLASS_RELATIVE_PATH)
    if (!Files.isRegularFile(pgProperty)) {
        System.err.println("PGProperty.class not found at $pgProperty")
        System.err.println("Make sure :postgresql:classes ran first.")
        exitProcess(2)
    }

    // 1. Read annotations from bytecode.
    val records = ClassFileReader.read(pgProperty).toMutableList()

    // 2. Enrich with runtime field data.
    ReflectionEnricher.enrich(records)

    // 3. Validate. On failure, print the friendly report and either exit
    //    non-zero (strict mode) or filter incomplete records out and proceed
    //    (--allow-incomplete, for use during the mass annotation pass).
    val complaints = Validator.validate(records)
    if (complaints.isNotEmpty()) {
        Validator.printReport(complaints, System.err)
        if (!allowIncomplete) exitProcess(1)
        val incomplete = complaints.mapTo(HashSet()) { it.fieldName }
        records.removeAll { it.fieldName in incomplete }
        System.err.println()
        System.err.println("--allow-incomplete: emitting YAML for ${records.size}" +
            " fully-annotated propert${if (records.size == 1) "y" else "ies"}.")
    }

    // 4. Emit YAML.
    YamlEmitter.writeTo(records, outputYaml)
    println("Wrote ${records.size} connection properties to $outputYaml")
}
