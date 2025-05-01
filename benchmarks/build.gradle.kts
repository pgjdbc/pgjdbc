/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
plugins {
    id("java-test-fixtures")
    id("build-logic.java-library")
    id("me.champeau.jmh")
}

dependencies {
    // Make jmhCompileClasspath resolvable
    jmhImplementation(project(":postgresql"))
    jmhImplementation(testFixtures(project(":postgresql")))
    jmhImplementation("org.roaringbitmap:RoaringBitmap:1.3.0")
    jmhImplementation("it.unimi.dsi:fastutil:8.5.15")
    jmhRuntimeOnly("com.ongres.scram:client:2.1")
    jmhImplementation("org.openjdk.jmh:jmh-core:1.37")
    jmhImplementation("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

// See https://github.com/melix/jmh-gradle-plugin
// Unfortunately, current jmh-gradle-plugin does not allow to customize jmh parameters from the
// command line, so the workarounds are:
// a) Build and execute the jar itself: ./gradlew jmhJar && java -jar build/libs/calcite-...jar JMH_OPTIONS
// b) Execute benchmarks via .main() methods from IDE (you might want to activate "power save mode"
//    in the IDE to minimize the impact of the IDE itself on the benchmark results)

tasks.withType<JavaExec>().configureEach {
    // Execution of .main methods from IDEA should re-generate benchmark classes if required
    dependsOn("jmhCompileGeneratedClasses")
    // Take build.properties from the root project
    systemProperty("build.properties.relative.path", isolated.rootProject.projectDirectory.asFile)
    doFirst {
        // At best jmh plugin should add the generated directories to the Gradle model, however,
        // currently it builds the jar only :-/
        // IntelliJ IDEA "execute main method" adds a JavaExec task, so we configure it
        classpath(layout.buildDirectory.dir("jmh-generated-classes"))
        classpath(layout.buildDirectory.dir("jmh-generated-resources"))
    }
}

if ("style" in tasks.names) {
    tasks.style {
        // This enables running `./gradlew style` or `./gradlew -PenableErrorprone` to verify
        // benchmark code with ErrorProne as well
        dependsOn(tasks.compileJmhJava)
    }
}
