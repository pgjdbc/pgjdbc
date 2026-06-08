import com.github.vlsi.gradle.publishing.dsl.versionFromResolution

plugins {
    id("build-logic.build-params")
    id("build-logic.java")
    id("java-library")
    id("build-logic.publish-to-central")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("755") }
    filePermissions { unix("644") }
}

java {
    withSourcesJar()
    if (!buildParameters.skipJavadoc) {
        withJavadocJar()
    }
}

publishing {
    publications {
        create<MavenPublication>(project.name) {
            from(components["java"])

            // Gradle feature variants can't be mapped to Maven's pom
            suppressAllPomMetadataWarnings()

            // Use the resolved versions in pom.xml
            // Gradle might have different resolution rules, so we set the versions
            // that were used in Gradle build/test.
            versionFromResolution()
        }
    }
}
