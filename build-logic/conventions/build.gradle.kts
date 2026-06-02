plugins {
    id("org.gradle.kotlin.kotlin-dsl")
}

group = "org.postgresql.build-logic"

dependencies {
    // Build parameters
    implementation(project(":build-parameters"))

    // Basics / JVM
    implementation("com.github.vlsi.crlf:com.github.vlsi.crlf.gradle.plugin:3.0.2")
    implementation("com.github.vlsi.gradle-extensions:com.github.vlsi.gradle-extensions.gradle.plugin:3.0.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin")
    implementation("com.github.autostyle:com.github.autostyle.gradle.plugin:4.0")
    implementation("com.github.vlsi.jandex:com.github.vlsi.jandex.gradle.plugin:3.0.2")

    // Java comment preprocessor
    implementation("com.igormaznitsa:jcp:7.3.0")

    // Publishing
    implementation("com.gradleup.nmcp:com.gradleup.nmcp.gradle.plugin:1.5.0")
    implementation("com.gradleup.nmcp.aggregation:com.gradleup.nmcp.aggregation.gradle.plugin:1.5.0")
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:9.4.1")

    // Verification
    // See https://github.com/gradle/gradle/issues/15383#issuecomment-779893192
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
    implementation("de.thetaphi.forbiddenapis:de.thetaphi.forbiddenapis.gradle.plugin:3.10")
    implementation("net.ltgt.errorprone:net.ltgt.errorprone.gradle.plugin:5.1.0")
    implementation("org.checkerframework:org.checkerframework.gradle.plugin:1.0.2")
    implementation("org.openrewrite.rewrite:org.openrewrite.rewrite.gradle.plugin:7.33.0")
}

tasks.validatePlugins {
    failOnWarning.set(true)
    enableStricterValidation.set(true)
}

// Use JDK 21, 17, or 11 for compiling build logic
listOf(21, 17, 11)
    .firstOrNull { JavaVersion.toVersion(it) <= JavaVersion.current() }
    ?.let { buildScriptJvmTarget ->
        java {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(buildScriptJvmTarget))
            }
        }
    }
