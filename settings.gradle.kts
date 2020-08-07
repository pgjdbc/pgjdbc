/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

pluginManagement {
    plugins {
        fun String.v() = extra["$this.version"].toString()
        fun PluginDependenciesSpec.idv(id: String, key: String = id) = id(id) version key.v()

        idv("biz.aQute.bnd.builder")
        idv("com.github.autostyle")
        idv("com.github.burrunan.s3-build-cache")
        idv("com.github.johnrengelman.shadow")
        idv("com.github.lburgazzoli.karaf")
        idv("com.github.spotbugs")
        idv("com.github.vlsi.crlf", "com.github.vlsi.vlsi-release-plugins")
        idv("com.github.vlsi.gettext", "com.github.vlsi.vlsi-release-plugins")
        idv("com.github.vlsi.gradle-extensions", "com.github.vlsi.vlsi-release-plugins")
        idv("com.github.vlsi.ide", "com.github.vlsi.vlsi-release-plugins")
        idv("com.github.vlsi.license-gather", "com.github.vlsi.vlsi-release-plugins")
        idv("com.github.vlsi.stage-vote-release", "com.github.vlsi.vlsi-release-plugins")
        idv("me.champeau.gradle.jmh")
        idv("org.checkerframework")
        idv("org.jetbrains.gradle.plugin.idea-ext")
        idv("org.nosphere.gradle.github.actions")
        idv("org.owasp.dependencycheck")
        kotlin("jvm") version "kotlin".v()
    }
}

plugins {
    `gradle-enterprise`
    id("com.github.burrunan.s3-build-cache")
}

// This is the name of a current project
// Note: it cannot be inferred from the directory name as developer might clone pgjdbc to pgjdbc_tmp (or whatever) folder
rootProject.name = "pgjdbc"

include(
    "bom",
    "benchmarks",
    "postgresql"
)

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
            region = "us-east-2"
            bucket = "pgjdbc-gradle-cache"
            isPush = isCiServer && pushAllowed && !awsAccessKeyId.isNullOrBlank()
        }
    }
}

// By default, Java7-processed sourcse are attached to IDE.
// However, it might be confusing as IDE always suggests multiple files for a class like PgConnection
// Note: IDEA caches after_sync task names.
// If you face "project :postgresql-jre7 is not found" issues when reimport
// you might need to remove items like :postgresql-jre7:preprocessMain from .idea/workspace.xml

if (property("pgjdbc.skip.jre6")?.ifBlank { "true" }?.toBoolean() != true) {
    include("postgresql-jre6")
    project(":postgresql-jre6").projectDir = file("pgjdbc-jre6")
}

if (property("pgjdbc.skip.jre7")?.ifBlank { "true" }?.toBoolean() != true) {
    include("postgresql-jre7")
    project(":postgresql-jre7").projectDir = file("pgjdbc-jre7")
}

// This enables to use local clone of vlsi-release-plugins for debugging purposes
property("localReleasePlugins")?.ifBlank { "../vlsi-release-plugins" }?.let {
    println("Importing project '$it'")
    includeBuild(it)
}
