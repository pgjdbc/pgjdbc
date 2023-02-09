import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java-library")
    id("build-logic.java")
    id("build-logic.test-base")
    id("com.github.vlsi.gradle-extensions")
    id("com.github.autostyle")
    kotlin("jvm")
}

java {
    withSourcesJar()
}

autostyle {
    kotlin {
        file("$rootDir/config/licenseHeaderRaw").takeIf { it.exists() }?.let {
            licenseHeader(it.readText())
        }
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "11"
    }
}
