/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
    id("com.github.autostyle")
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

val String.v: String get() = rootProject.extra["$this.version"] as String

dependencies {
    implementation("com.igormaznitsa:jcp:${"jcp".v}")
}

kotlinDslPluginOptions {
    experimentalWarning.set(false)
}

tasks.withType<KotlinCompile> {
    sourceCompatibility = "unused"
    targetCompatibility = "unused"
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    // Ensure builds are reproducible
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirMode = "775".toInt(8)
    fileMode = "664".toInt(8)
}

autostyle {
    kotlin {
        ktlint {
            userData(mapOf("disabled_rules" to "no-wildcard-imports,import-ordering"))
        }
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
