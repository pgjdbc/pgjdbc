plugins {
    id("java-library")
    id("org.gradle.kotlin.kotlin-dsl") // this is 'kotlin-dsl' without version
}

tasks.validatePlugins {
    failOnWarning.set(true)
    enableStricterValidation.set(true)
}

// -PjdkBuildVersion=0 means "build with the JVM Gradle runs on", as in the main build.
// Skip the toolchain then: where only a newer JDK is installed, as in the Fedora Copr
// chroot, none of 21, 17, and 11 resolves and configuration fails. Otherwise use the
// highest of those three the current JVM provides; 21 is the newest the Kotlin Gradle
// plugin targets.
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
