/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

plugins {
    id("build-logic.repositories")
    id("org.nosphere.gradle.github.actions")
    // IDE configuration
    id("com.github.vlsi.ide")
    // Release
    id("com.github.vlsi.gradle-extensions")
    id("com.github.vlsi.stage-vote-release")
    id("jacoco")
}

buildscript {
    configurations.classpath {
        exclude("xerces")
    }
}

ide {
    // TODO: set copyright to PostgreSQL Global Development Group
    ideaInstructionsUri =
        uri("https://github.com/pgjdbc/pgjdbc")
    doNotDetectFrameworks("android", "jruby")
}

val String.v: String get() = rootProject.extra["$this.version"] as String

val buildVersion = "pgjdbc".v + releaseParams.snapshotSuffix

println("Building pgjdbc $buildVersion")

val isReleaseVersion = rootProject.releaseParams.release.get()

jacoco {
    toolVersion = "0.8.13"
    providers.gradleProperty("jacoco.version")
        .takeIf { it.isPresent }
        ?.let { toolVersion = it.get() }
}

val jacocoReport by tasks.registering(JacocoReport::class) {
    group = "Coverage reports"
    description = "Generates an aggregate report from all subprojects"
}

releaseParams {
    tlp.set("pgjdbc")
    organizationName.set("pgjdbc")
    componentName.set("pgjdbc")
    prefixForProperties.set("gh")
    svnDistEnabled.set(false)
    sitePreviewEnabled.set(false)
    releaseTag.set("REL$buildVersion")
    nexus {
        mavenCentral()
    }
    voteText.set {
        """
        ${it.componentName} v${it.version}-rc${it.rc} is ready for preview.

        Git SHA: ${it.gitSha}
        Staging repository: ${it.nexusRepositoryUri}
        """.trimIndent()
    }
}

allprojects {
    group = "org.postgresql"
    version = buildVersion
}

val parameters by tasks.registering {
    group = HelpTasksPlugin.HELP_GROUP
    description = "Displays build parameters (i.e. -P flags) that can be used to customize the build"
    dependsOn(gradle.includedBuild("build-logic").task(":build-parameters:parameters"))
}

plugins.withId("de.marcphilipp.nexus-publish") {
    configure<de.marcphilipp.gradle.nexus.NexusPublishExtension> {
        clientTimeout.set(java.time.Duration.ofMinutes(15))
    }
}

plugins.withId("io.codearte.nexus-staging") {
    configure<io.codearte.gradle.nexus.NexusStagingExtension> {
        numberOfRetries = 20 * 60 / 2
        delayBetweenRetriesInMillis = 2000
    }
}
