/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

plugins {
    id("build-logic.java-library")
    id("build-logic.test-junit5")
}

// The coverage-guided fuzzer is the JUnit 5 fork of JQF plus its jetCheck argument
// provider. Both are consumed from the local Maven repository, so the fork must be
// installed first with `mvn install`. settings.xml points the local repository at
// ~/.m2, which is where Gradle's mavenLocal() also looks.
repositories {
    mavenLocal()
}

val jqfVersion = "2.2-SNAPSHOT"

// https://github.com/gradle/gradle/pull/16627
private inline fun <reified T : Named> AttributeContainer.attribute(attr: Attribute<T>, value: String) =
    attribute(attr, objects.named<T>(value))

// The jqf-instrument javaagent bytecode-instruments the driver for coverage feedback. It is
// resolved on its own so a single agent jar can be passed to -javaagent (ASM stays on the normal
// test classpath).
val jqfInstrumentAgent = configurations.dependencyScope("jqfInstrumentAgent")
val jqfInstrumentAgentClasspath = configurations.resolvable("jqfInstrumentAgentClasspath") {
    extendsFrom(jqfInstrumentAgent.get())
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, Category.LIBRARY)
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, LibraryElements.JAR)
        attribute(Usage.USAGE_ATTRIBUTE, Usage.JAVA_RUNTIME)
        attribute(Bundling.BUNDLING_ATTRIBUTE, Bundling.EXTERNAL)
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, buildParameters.targetJavaVersion)
    }
}

dependencies {
    constraints {
        testCompileOnly("junit:junit") {
            because("JQF's JUnit 5 path stays JUnit-4-free, matching the main pgjdbc build")
            version {
                rejectAll()
            }
        }
    }

    // The fuzzer-independent core: the coercion dictionaries and the offline codec oracles this
    // module's jetCheck generators and @FuzzTest targets assert against. Shared with pgjdbc-jazzer-test.
    testImplementation(projects.pgjdbcFuzzkit)
    // The jetCheck generators annotate nullable draws with @Nullable.
    testImplementation("org.checkerframework:checker-qual:3.55.1")

    // The shaded driver jar exposes the org.postgresql.api.codec surface plus the offline
    // PgCodecContext/PgType/PgStruct classes the fuzz target drives without a connection.
    testImplementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }

    // JQF: the JUnit 5 @FuzzTest engine and the jetCheck-backed argument generator.
    // jqf-generator-jetcheck is discovered through the ServiceLoader, so the auto-provider
    // tests need no further wiring.
    testImplementation("edu.berkeley.cs.jqf:jqf-junit5:$jqfVersion")
    testImplementation("edu.berkeley.cs.jqf:jqf-generator-jetcheck:$jqfVersion")

    jqfInstrumentAgent("edu.berkeley.cs.jqf:jqf-instrument:$jqfVersion")

    // The differential value fuzzer reuses the compat oracle core (probe + comparator + isolated
    // baseline loader) and TestUtil for the connection settings.
    testImplementation(projects.pgjdbcCompatTest)
    testImplementation(projects.testkit)
}

// The released baseline jar for the differential fuzzer: only its path is handed to the test JVM,
// which loads it in an isolated class loader (never on the classpath). Overridable via
// -Dpgjdbc.compat.legacyJar. Mirrors pgjdbc-compat-test.
val legacyDriver = configurations.dependencyScope("legacyDriver")
val legacyDriverClasspath = configurations.resolvable("legacyDriverClasspath") {
    extendsFrom(legacyDriver.get())
}

dependencies {
    legacyDriver(libs.pgjdbc.compat.baseline)
}

tasks.test {
    val override = providers.systemProperty("pgjdbc.compat.legacyJar")
    val resolved = legacyDriverClasspath.map {
        it.incoming.artifactView {
            componentFilter { it is ModuleComponentIdentifier && it.module == "postgresql" }
        }.files.singleFile.absolutePath
    }
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dpgjdbc.compat.legacyJar=${override.orNull ?: resolved.get()}")
    })
}

// Fuzzing is opt-in through -Djqf.fuzz=true. Without it the @FuzzTest nodes replay the saved
// corpus (plus the empty input) as bounded regression, which is what CI wants and needs no
// instrumentation.
val fuzzing = providers.systemProperty("jqf.fuzz").map { it.toBoolean() }.orElse(false)

tasks.test {
    // The Zest guidance is a single stateful object per campaign, so the @FuzzTest nodes
    // must not run in parallel.
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    // A campaign runs far longer than the 5-minute default the shared convention sets.
    systemProperty("junit.jupiter.execution.timeout.default", "1 h")
    // Pin the JVM default time zone so the coercion oracle is deterministic across machines: it builds
    // its offline context from TimeZone.getDefault() and its java.sql temporal values are created in the
    // default zone, so a host-dependent default would make temporal cases nondeterministic.
    systemProperty("user.timezone", "UTC")

    // Forward the JQF toggles from the Gradle invocation to the test JVM.
    for (key in listOf("jqf.fuzz", "jqf.fuzz.trials", "jqf.fuzz.out")) {
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    // Persist the corpus under build/ so a fuzzing run seeds the next one.
    val defaultCorpus = layout.buildDirectory.dir("jqf-corpus").get().asFile
    if (providers.systemProperty("jqf.fuzz.out").orNull == null) {
        systemProperty("jqf.fuzz.out", defaultCorpus.absolutePath)
    }
    doFirst {
        defaultCorpus.mkdirs()
    }

    if (fuzzing.get()) {
        // Focus coverage on the driver: instrument org.postgresql, exclude the test infrastructure
        // and third-party libraries. `includes` is honoured before `excludes`, so org.postgresql
        // survives the broad `org` exclude.
        systemProperty("janala.includes", "org.postgresql")
        systemProperty("janala.excludes",
            "com,edu,gradle,io,jakarta,javax,junit,kotlin,net,okio,org,sun,worker")
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            val jars = jqfInstrumentAgentClasspath.get().files
            val agentJar = jars.single { it.name.startsWith("jqf-instrument") }
            // The tracing runtime (SingleSnoop) and ASM must sit on the bootstrap classpath so
            // instrumented classes -- including the JDK collections JQF retransforms in premain --
            // can resolve them; otherwise every instrumentation fails and coverage stays flat.
            listOf(
                "-javaagent:$agentJar",
                "-Xbootclasspath/a:${jars.joinToString(File.pathSeparator)}"
            )
        })
    }
}
