/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

plugins {
    id("build-logic.java-library")
    id("build-logic.test-junit5")
}

// pgjdbc-fuzzkit is the fuzzer-independent core shared by the coverage-guided fuzzers
// (pgjdbc-jqf-test, pgjdbc-jazzer-test): the coercion dictionaries -- the read/write oracle data -- and
// the offline codec oracle runners that drive the driver's public codec surface without a connection.
// It knows nothing about any fuzzing engine, so each fuzzer depends on it and supplies only its own
// input generation and @FuzzTest targets.

dependencies {
    // The shaded driver jar exposes the org.postgresql.api.codec surface plus the offline
    // PgCodecContext/PgType/PgSQLInput classes the oracles drive. compileOnly keeps fuzzkit from
    // bundling the driver; each consumer already puts the shaded driver on its own test classpath.
    compileOnly(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }
    implementation("org.checkerframework:checker-qual:3.55.1")

    // The codec round-trip oracle asserts with JUnit 5, so the assertion API is a normal main
    // dependency of this support library. It stays an implementation detail -- no public oracle
    // signature exposes a JUnit type -- so consumers need not see it to call the oracle.
    implementation(platform("org.junit:junit-bom:5.14.4"))
    implementation("org.junit.jupiter:junit-jupiter-api")

    // fuzzkit's own unit tests (FidelityTest and the two example/pin tests) need the driver at runtime.
    testImplementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }

    testImplementation(projects.testkit)
}

tasks.test {
    // Pin the JVM default time zone so the coercion oracle is deterministic across machines: it builds
    // its offline context from TimeZone.getDefault() and its java.sql temporal values are created in the
    // default zone, so a host-dependent default would make temporal cases nondeterministic.
    systemProperty("user.timezone", "UTC")
}
