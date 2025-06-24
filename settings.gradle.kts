/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

pluginManagement {
    plugins {
        id("biz.aQute.bnd.builder") version "7.1.0"
        id("com.github.burrunan.s3-build-cache") version "1.9.2"
        id("com.github.johnrengelman.shadow") version "8.1.1"
        id("com.github.lburgazzoli.karaf") version "0.5.6"
        id("com.github.vlsi.crlf") version "2.0.0"
        id("com.github.vlsi.gettext") version "2.0.0"
        id("com.github.vlsi.gradle-extensions") version "2.0.0"
        id("com.github.vlsi.license-gather") version "2.0.0"
        id("com.github.vlsi.ide") version "2.0.0"
        id("com.github.vlsi.stage-vote-release") version "2.0.0"
        id("org.nosphere.gradle.github.actions") version "1.4.0"
        id("me.champeau.jmh") version "0.7.3"
        kotlin("jvm") version "2.1.21"
    }
}

plugins {
    id("com.gradle.develocity") version "4.0.2"
    id("com.github.burrunan.s3-build-cache")
}

if (JavaVersion.current() < JavaVersion.VERSION_17) {
    throw UnsupportedOperationException("Please use Java 17 or 21 for launching Gradle when building pgjdbc, the current Java is ${JavaVersion.current().majorVersion}")
}

// This is the name of a current project
// Note: it cannot be inferred from the directory name as developer might clone pgjdbc to pgjdbc_tmp (or whatever) folder
rootProject.name = "pgjdbc"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

includeBuild("build-logic")

// Renovate treats names as dependency coordinates when vararg include(...) is used, so we have separate include calls here
include("benchmarks")
include("pgjdbc-osgi-test")
include("postgresql")
include("testkit")

project(":postgresql").projectDir = file("pgjdbc")

// See https://github.com/gradle/gradle/issues/1348#issuecomment-284758705 and
// https://github.com/gradle/gradle/issues/5321#issuecomment-387561204
// Gradle inherits Ant "default excludes", however we do want to archive those files
org.apache.tools.ant.DirectoryScanner.removeDefaultExclude("**/.gitattributes")
org.apache.tools.ant.DirectoryScanner.removeDefaultExclude("**/.gitignore")

fun property(name: String) =
    when (extra.has(name)) {
        true -> extra.get(name) as? String
        else -> null
    }

val isCiServer = System.getenv().containsKey("CI")

develocity {
    buildScan {
        if (isCiServer) {
            termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
            termsOfUseAgree = "yes"
            tag("CI")
        } else {
            // Dot not publish build scans from the local builds unless user executes with --scan
            publishing.onlyIf { false }
        }
    }
}

// Cache build artifacts, so expensive operations do not need to be re-computed
buildCache {
    local {
        isEnabled = !isCiServer || System.getenv().containsKey("GITHUB_ACTIONS")
    }
    if (property("s3.build.cache")?.ifBlank { "true" }?.toBoolean() == true) {
        val pushAllowed = property("s3.build.cache.push")?.ifBlank { "true" }?.toBoolean() ?: true
        remote<com.github.burrunan.s3cache.AwsS3BuildCache> {
            region = "us-east-1"
            bucket = "pgjdbc-build-cache"
            isPush = isCiServer && pushAllowed && !awsAccessKeyId.isNullOrBlank()
        }
    }
}

// This enables to use local clone of vlsi-release-plugins for debugging purposes
property("localReleasePlugins")?.ifBlank { "../vlsi-release-plugins" }?.let {
    println("Importing project '$it'")
    includeBuild(it)
}
