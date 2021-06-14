/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

plugins {
    `java-platform`
}

val String.v: String get() = rootProject.extra["$this.version"] as String

// Note: Gradle allows to declare dependency on "bom" as "api",
// and it makes the contraints to be transitively visible
// However Maven can't express that, so the approach is to use Gradle resolution
// and generate pom files with resolved versions
// See https://github.com/gradle/gradle/issues/9866

fun DependencyConstraintHandlerScope.apiv(
    notation: String,
    versionProp: String = notation.substringAfterLast(':')
) =
    "api"(notation + ":" + versionProp.v)

fun DependencyConstraintHandlerScope.runtimev(
    notation: String,
    versionProp: String = notation.substringAfterLast(':')
) =
    "runtime"(notation + ":" + versionProp.v)

dependencies {
    // Parenthesis are needed here: https://github.com/gradle/gradle/issues/9248
    (constraints) {
        // api means "the dependency is for both compilation and runtime"
        // runtime means "the dependency is only for runtime, not for compilation"
        // In other words, marking dependency as "runtime" would avoid accidental
        // dependency on it during compilation
        apiv("ch.qos.logback:logback-classic", "logback")
        apiv("ch.qos.logback:logback-core", "logback")
        apiv("com.github.waffle:waffle-jna")
        apiv("com.ongres.scram:client", "com.ongres.scram.client")
        apiv("javax:javaee-api")
        apiv("junit:junit", "junit4")
        apiv("org.apache.felix:org.apache.felix.framework")
        apiv("org.checkerframework:checker-qual", "checkerframework")
        apiv("org.hamcrest:hamcrest")
        apiv("org.hamcrest:hamcrest-core", "hamcrest")
        apiv("org.hamcrest:hamcrest-library", "hamcrest")
        apiv("org.junit.jupiter:junit-jupiter-api", "junit5")
        apiv("org.junit.jupiter:junit-jupiter-params", "junit5")
        apiv("org.openjdk.jmh:jmh-generator-annprocess", "jmh")
        apiv("org.ops4j.pax.exam:pax-exam-container-native", "org.ops4j.pax.exam")
        apiv("org.ops4j.pax.exam:pax-exam-junit4", "org.ops4j.pax.exam")
        apiv("org.ops4j.pax.exam:pax-exam-junit4", "org.ops4j.pax.exam")
        apiv("org.ops4j.pax.exam:pax-exam-link-mvn", "org.ops4j.pax.exam")
        apiv("org.ops4j.pax.url:pax-url-aether")
        apiv("org.osgi:org.osgi.core")
        apiv("org.osgi:org.osgi.service.jdbc")
        apiv("org.slf4j:slf4j-api", "slf4j")
        apiv("org.slf4j:slf4j-log4j12", "slf4j")
        apiv("se.jiderhamn:classloader-leak-test-framework")
        runtimev("org.junit.jupiter:junit-jupiter-engine", "junit5")
        runtimev("org.junit.vintage:junit-vintage-engine", "junit5")
        runtimev("org.openjdk.jmh:jmh-core", "jmh")
    }
}
