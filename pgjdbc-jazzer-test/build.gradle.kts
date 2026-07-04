/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

plugins {
    id("build-logic.java-library")
    id("build-logic.test-junit5")
}

// Jazzer is Code Intelligence's libFuzzer-backed coverage-guided fuzzer for the JVM. Unlike the JQF
// fork that pgjdbc-jqf-test consumes from mavenLocal, jazzer-junit is a normal Maven Central release,
// so the shared convention repositories resolve it with no extra wiring. This module is a side-by-side
// experiment against pgjdbc-jqf-test: it drives the same offline codec surface, so the two fuzzers can
// be compared on the same properties.
val jazzerVersion = "0.30.0"

dependencies {
    // The fuzzer-independent core: the coercion dictionaries and the offline codec oracles the
    // @FuzzTest targets drive. Shared with pgjdbc-jqf-test, so both fuzzers assert the same oracle.
    testImplementation(projects.pgjdbcFuzzkit)
    // fuzzkit's public API carries @Nullable on a few signatures; keep the annotation resolvable.
    testImplementation("org.checkerframework:checker-qual:3.55.1")

    // The shaded driver jar exposes the org.postgresql.api.codec surface plus the offline
    // PgCodecContext/PgType classes the fuzz target drives without a connection -- the same surface
    // pgjdbc-jqf-test uses.
    testImplementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }

    // Jazzer's JUnit 5 @FuzzTest engine. It brings jazzer-api (FuzzedDataProvider) and the structured
    // mutation framework -- the @NotNull/@InRange annotations and the primitive/String/array/record
    // mutators -- transitively, and plugs @FuzzTest into the standard Jupiter engine, so no argument
    // provider has to be written for the common shapes.
    testImplementation("com.code-intelligence:jazzer-junit:$jazzerVersion")
}

// Fuzzing is opt-in, either through -Pjazzer.fuzz=1 (reliable across the Gradle daemon) or the native
// JAZZER_FUZZ environment variable. Without it the @FuzzTest nodes replay the saved seed corpus (plus
// the empty input) as bounded regression -- what CI wants -- and need neither the native libFuzzer
// engine nor bytecode instrumentation.
val fuzzRequested =
    providers.gradleProperty("jazzer.fuzz").orNull?.takeIf { it.isNotBlank() && it != "false" && it != "0" }
        ?: providers.environmentVariable("JAZZER_FUZZ").orNull?.takeIf { it.isNotBlank() && it != "0" }

tasks.test {
    // Jazzer's guidance is a single libFuzzer campaign and it runs only one @FuzzTest per JVM, so the
    // nodes must not run in parallel.
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    // A campaign runs far longer than the 5-minute default the shared convention sets.
    systemProperty("junit.jupiter.execution.timeout.default", "1 h")
    // Pin the JVM default time zone so the coercion oracle is deterministic across machines: it builds
    // its offline context from TimeZone.getDefault() and its java.sql temporal values are created in the
    // default zone, so a host-dependent default would make temporal cases nondeterministic.
    systemProperty("user.timezone", "UTC")

    if (fuzzRequested != null) {
        environment("JAZZER_FUZZ", "1")
        // Java 21 warns (and a future release will fail) when an agent self-attaches at runtime;
        // Jazzer's fuzzing mode loads its instrumentation agent that way, so allow it up front.
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }
    // JAZZER_COVERAGE=1 folds the generated corpus into a regression run; forward it when present.
    providers.environmentVariable("JAZZER_COVERAGE").orNull?.let { environment("JAZZER_COVERAGE", it) }
}
