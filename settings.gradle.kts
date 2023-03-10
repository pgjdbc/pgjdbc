/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

pluginManagement {
    plugins {
        id("biz.aQute.bnd.builder") version "6.4.0"
        id("com.github.burrunan.s3-build-cache") version "1.5"
        id("com.github.johnrengelman.shadow") version "7.1.2"
        id("com.github.lburgazzoli.karaf") version "0.5.6"
        id("com.github.vlsi.crlf") version "1.86"
        id("com.github.vlsi.gettext") version "1.86"
        id("com.github.vlsi.gradle-extensions") version "1.86"
        id("com.github.vlsi.license-gather") version "1.86"
        id("com.github.vlsi.ide") version "1.86"
        id("com.github.vlsi.stage-vote-release") version "1.86"
        id("org.nosphere.gradle.github.actions") version "1.3.2"
        id("me.champeau.jmh") version "0.7.0"
        kotlin("jvm") version "1.8.10"
    }
}

plugins {
    `gradle-enterprise`
    id("com.github.burrunan.s3-build-cache")
}

// This is the name of a current project
// Note: it cannot be inferred from the directory name as developer might clone pgjdbc to pgjdbc_tmp (or whatever) folder
rootProject.name = "pgjdbc"

includeBuild("build-logic")

// Renovate treats names as dependency coordinates when vararg include(...) is used, so we have separate include calls here
include("benchmarks")
include("pgjdbc-osgi-test")
include("postgresql")

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

if (isCiServer) {
    gradleEnterprise {
        buildScan {
            termsOfServiceUrl = "https://gradle.com/terms-of-service"
            termsOfServiceAgree = "yes"
            tag("CI")
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
