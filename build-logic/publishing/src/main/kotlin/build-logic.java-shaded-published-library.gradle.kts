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

// Publish the `java` component rather than Shadow's `shadow` component. The `shadow` component
// flattens Gradle feature variants and drops the unshaded runtime dependencies, so the pom would
// omit waffle-jna (the sspi feature variant) and checker-qual. The `java` component maps the
// feature variant to an optional pom dependency and lists checker-qual, while the shaded
// dependencies live in a separate configuration that never reaches the runtime classpath and so
// stay out of the pom. The published main jar is replaced with the OSGi bundle by the consuming
// project.
//
// from(components[...]) eagerly populates the publication, which prevents Shadow from amending the
// Gradle Module Metadata it configures in its own afterEvaluate. Deferring our from() to a later
// afterEvaluate (registered after Shadow's, since Shadow is applied in the plugins block above)
// lets Shadow finish first. The sources and javadoc jars are added explicitly because Gradle
// Module Metadata is disabled, so the component's secondary variants are not published on their own.
afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>(project.name) {
                from(components["java"])

                artifact(tasks.named<Jar>("sourcesJar"))

                if (!buildParameters.skipJavadoc) {
                    artifact(tasks.named<Jar>("javadocJar"))
                }
            }
        }
    }
}
