import com.github.vlsi.gradle.publishing.dsl.versionFromResolution

plugins {
    id("build-logic.build-params")
    id("build-logic.java-library")
    id("build-logic.reproducible-builds")
    id("build-logic.publish-to-central")
    id("com.gradleup.shadow")
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
            // Gradle feature variants can't be mapped to Maven's pom
            suppressAllPomMetadataWarnings()

            // Use the resolved versions in pom.xml
            // Gradle might have different resolution rules, so we set the versions
            // that were used in Gradle build/test.
            versionFromResolution()
        }
    }
}

// Shadow plugin configures shadowRuntimeElements in afterEvaluate,
// so we must defer from(components["shadow"]) to afterEvaluate as well.
// This afterEvaluate is registered after Shadow's (since Shadow is applied
// in the plugins block above), so it runs after Shadow has finished configuring.
afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>(project.name) {
                from(components["shadow"])
            }
        }
    }
}
