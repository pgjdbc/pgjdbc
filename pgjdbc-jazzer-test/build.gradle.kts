/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

plugins {
    id("build-logic.java-library")
    id("build-logic.test-junit5")
}

dependencies {
    implementation(projects.pgjdbcFuzzkit)
    implementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }
    testImplementation(projects.testkit)
    testImplementation("com.code-intelligence:jazzer-junit:0.30.0")
    // Scans the codec package for the decode-robustness coverage guard (JazzerCodecDecodeCoverageArchTest).
    testImplementation("com.tngtech.archunit:archunit:1.4.2")
}

val generatedFuzzTargetsDir = layout.buildDirectory.dir("generated/sources/fuzzTargets/java")
val generateJazzerFuzzTargets = tasks.register<JavaExec>("generateJazzerFuzzTargets") {
    description = "Generates the module's @FuzzTest source families from the shared fuzzkit models."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath(sourceSets.main.map { it.output.classesDirs }, configurations.runtimeClasspath)
    mainClass.set("org.postgresql.test.jazzer.FuzzTargetGenerator")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(generatedFuzzTargetsDir.get().asFile.absolutePath)
    })
    // Deliberately no declared outputs, for the same reason generateJazzerSeedCorpus declares none: a
    // declared output directory makes Gradle flag an implicit dependency for every Autostyle formatter
    // that globs the project tree. Generation is cheap and deterministic (identical bytes on a rerun), so
    // it simply runs each time rather than carrying up-to-date state.
}

sourceSets.test {
    java.srcDir(generatedFuzzTargetsDir)
}

tasks.compileTestJava {
    dependsOn(generateJazzerFuzzTargets)
}

tasks.withType<Checkstyle>().configureEach {
    exclude("**/Generated*FuzzTest.java")
}

val fuzzCorpusDir = layout.buildDirectory.dir("jazzer/corpus")

sourceSets.test {
    resources.srcDir(fuzzCorpusDir)
}

val generateJazzerSeedCorpus = tasks.register<JavaExec>("generateJazzerSeedCorpus") {
    description = "Generates the Jazzer seed corpus from the shared edge-case catalogues."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath(
        sourceSets.main.map { it.output.classesDirs },
        sourceSets.test.map { it.output.classesDirs },
        configurations.testRuntimeClasspath
    )
    mainClass.set("org.postgresql.test.jazzer.JazzerSeedCorpusGenerator")
    argumentProviders.add(CommandLineArgumentProvider { listOf(fuzzCorpusDir.get().asFile.absolutePath) })
    // Pin the default zone so any temporal edge case a future target seeds encodes deterministically,
    // matching the test task above.
    systemProperty("user.timezone", "UTC")
    // Deliberately no declared outputs: the seed files land in src/test/resources, whose tree the
    // Autostyle Kotlin-gradle formatter roots its own fileTree at, so declaring that directory as this
    // task's output makes Gradle flag an implicit dependency for every formatter that globs the project.
    // Generation is cheap and idempotent (identical bytes, so processTestResources' content snapshot does
    // not re-run downstream), so it simply runs each time rather than carrying up-to-date state.
}

tasks.processTestResources {
    dependsOn(generateJazzerSeedCorpus)
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
        // -Pjazzer.maxDuration=<libFuzzer duration> caps a guided campaign per @FuzzTest, overriding the
        // 5-minute annotation default; forwarded to the test JVM as the jazzer.max_duration option. Lets a
        // sweep run every target for a fixed short budget (for example 20s) instead of five minutes each.
        providers.gradleProperty("jazzer.maxDuration").orNull?.let {
            systemProperty("jazzer.max_duration", it)
        }
    }
    // JAZZER_COVERAGE=1 folds the generated corpus into a regression run; forward it when present.
    providers.environmentVariable("JAZZER_COVERAGE").orNull?.let { environment("JAZZER_COVERAGE", it) }
}
