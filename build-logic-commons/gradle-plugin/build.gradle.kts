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
