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
        apiv("com.github.waffle:waffle-jna")
        apiv("com.ongres.scram:client", "com.ongres.scram.client")
        apiv("junit:junit", "junit4")
        apiv("org.hamcrest:hamcrest")
        apiv("org.hamcrest:hamcrest-core", "hamcrest")
        apiv("org.hamcrest:hamcrest-library", "hamcrest")
        apiv("org.junit.jupiter:junit-jupiter-api", "junit5")
        apiv("org.junit.jupiter:junit-jupiter-params", "junit5")
        apiv("org.openjdk.jmh:jmh-generator-annprocess", "jmh")
        apiv("org.osgi:org.osgi.core")
        apiv("se.jiderhamn:classloader-leak-test-framework")
        apiv("org.osgi:org.osgi.enterprise")
        apiv("org.slf4j:slf4j-api", "slf4j")
        apiv("org.slf4j:slf4j-log4j12", "slf4j")
        runtimev("org.junit.jupiter:junit-jupiter-engine", "junit5")
        runtimev("org.junit.vintage:junit-vintage-engine", "junit5")
        runtimev("org.openjdk.jmh:jmh-core", "jmh")
    }
}
