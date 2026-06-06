plugins {
    id("java-library")
    id("org.gradle.kotlin.kotlin-dsl") // this is 'kotlin-dsl' without version
}

tasks.validatePlugins {
    failOnWarning.set(true)
    enableStricterValidation.set(true)
}

// jdkBuildVersion=0 means "build with the current JVM" (as in the main build); skip the
// toolchain so Gradle uses the JVM it already runs on and needs no separate JDK installed.
// Otherwise pin a Kotlin-supported target (21, 17, or 11) the current JVM can provide.
if (providers.gradleProperty("jdkBuildVersion").orNull != "0") {
    listOf(21, 17, 11)
        .firstOrNull { JavaVersion.toVersion(it) <= JavaVersion.current() }
        ?.let { buildScriptJvmTarget ->
            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(buildScriptJvmTarget))
                }
            }
        }
}
