/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
plugins {
    id("build-logic.java-library")
    id("me.champeau.jmh")
}

dependencies {
    // Make jmhCompileClasspath resolvable
    jmhImplementation(project(":postgresql"))
    jmhImplementation("org.openjdk.jmh:jmh-core:1.12")
    jmhImplementation("org.openjdk.jmh:jmh-generator-annprocess:1.12")
}

// See https://github.com/melix/jmh-gradle-plugin
// Unfortunately, current jmh-gradle-plugin does not allow to cusomize jmh parameters from the
// command line, so the workarounds are:
// a) Build and execute the jar itself: ./gradlew jmhJar && java -jar build/libs/calcite-...jar JMH_OPTIONS
// b) Execute benchmarks via .main() methods from IDE (you might want to activate "power save mode"
//    in the IDE to minimize the impact of the IDE itself on the benchmark results)

tasks.withType<JavaExec>().configureEach {
    // Execution of .main methods from IDEA should re-generate benchmark classes if required
    dependsOn("jmhCompileGeneratedClasses")
    doFirst {
        // At best jmh plugin should add the generated directories to the Gradle model, however,
        // currently it builds the jar only :-/
        // IntelliJ IDEA "execute main method" adds a JavaExec task, so we configure it
        classpath(File(buildDir, "jmh-generated-classes"))
        classpath(File(buildDir, "jmh-generated-resources"))
    }
}
