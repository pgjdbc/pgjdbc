import com.github.vlsi.gradle.publishing.dsl.versionFromResolution

plugins {
    id("build-logic.build-params")
    id("build-logic.java")
    id("java-library")
    id("build-logic.publish-to-central")
    id("com.gradleup.shadow")
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

                artifact(tasks.named<Jar>("sourcesJar"))

                if (!buildParameters.skipJavadoc) {
                    artifact(tasks.named<Jar>("javadocJar"))
                }
            }
        }
    }
}
