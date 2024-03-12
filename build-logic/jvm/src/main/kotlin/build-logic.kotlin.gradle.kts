import com.github.vlsi.gradle.dsl.configureEach
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

val String.v: String get() = rootProject.extra["$this.version"] as String
autostyle {
    kotlin {
        file("$rootDir/config/licenseHeaderRaw").takeIf { it.exists() }?.let {
            licenseHeader(it.readText())
        }
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.configureEach<KotlinCompile> {
     kotlinOptions {
        if (!name.startsWith("compileTest")) {
            apiVersion = "kotlin.api".v
        }
        freeCompilerArgs += "-Xjvm-default=all"
        val jdkRelease = buildParameters.targetJavaVersion.let {
            when {
                it < 9 -> "1.8"
                else -> it.toString()
            }
        }
        // jdk-release requires Java 9+
        buildParameters.buildJdkVersion
            .takeIf { it > 8 }
            ?.let {
                freeCompilerArgs += "-Xjdk-release=$jdkRelease"
            }
        kotlinOptions.jvmTarget = jdkRelease
    }
}
