/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

plugins {
    id("build-logic.repositories")
    id("build-logic.build-params")
    id("build-logic.root-build")
    id("org.nosphere.gradle.github.actions")
    // IDE configuration
    id("com.github.vlsi.ide")
    // Release
    id("com.github.vlsi.gradle-extensions")
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

val buildVersion = "pgjdbc".v + if (buildParameters.release) "" else "-SNAPSHOT"

println("Building pgjdbc $buildVersion")

val isReleaseVersion = buildParameters.release

dependencies {
    // nmcpAggregation declares the list of projects should be published to Central Portal
    // Currently we publish a single project only, however, if we add more, we need to add them here
    // as well.
    nmcpAggregation(projects.postgresql)
}

jacoco {
    toolVersion = "0.8.14"
    providers.gradleProperty("jacoco.version")
        .takeIf { it.isPresent }
        ?.let { toolVersion = it.get() }
}

val jacocoReport by tasks.registering(JacocoReport::class) {
    group = "Coverage reports"
    description = "Generates an aggregate report from all subprojects"
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
