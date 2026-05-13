plugins {
    id("build-logic.java-library")
    id("build-logic.test-junit5")
}

// spring-jdbc 6.x publishes Gradle Module Metadata declaring
// org.gradle.jvm.version=17, so the consumer attributes must match
// (the build-logic.java plugin defaults the entire build to JVM 8).
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }
    testImplementation(projects.testkit)

    testImplementation("org.springframework:spring-jdbc:6.1.1")
}
