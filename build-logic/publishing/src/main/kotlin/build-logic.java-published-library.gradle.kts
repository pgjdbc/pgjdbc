import com.github.vlsi.gradle.publishing.dsl.versionFromResolution

plugins {
    id("build-logic.build-params")
    id("build-logic.java-library")
    id("build-logic.reproducible-builds")
    id("build-logic.publish-to-central")
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
