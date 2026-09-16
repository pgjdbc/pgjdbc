import org.gradle.kotlin.dsl.support.expectedKotlinDslPluginsVersion

plugins {
    `kotlin-dsl`
}

group = "org.postgresql.build-logic"

dependencies {
    // We use precompiled script plugins (== plugins written as src/kotlin/build-logic.*.gradle.kts files,
    // and we need to declare dependency on org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin
    // to make it work.
    // See https://github.com/gradle/gradle/issues/17016 regarding expectedKotlinDslPluginsVersion
    implementation("org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin:$expectedKotlinDslPluginsVersion")
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
