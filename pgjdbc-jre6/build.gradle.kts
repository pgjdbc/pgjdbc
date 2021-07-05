/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

import aQute.bnd.gradle.Bundle
import aQute.bnd.gradle.BundleTaskConvention
import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.properties.dsl.props
import org.postgresql.buildtools.JavaCommentPreprocessorTask

plugins {
    `java-library`
    id("com.github.vlsi.ide")
    id("com.github.vlsi.gradle-extensions")
    id("biz.aQute.bnd.builder") apply false
}

buildscript {
    repositories {
        // E.g. for biz.aQute.bnd.builder which is not published to Gradle Plugin Portal
        mavenCentral()
    }
}

version = version.toString().replaceFirst(Regex("(-SNAPSHOT)?$"), ".jre6\$1")
setProperty("archivesBaseName", "postgresql")

val java6home by props("")

val String.v: String get() = rootProject.extra["$this.version"] as String

dependencies {
    implementation(platform(project(":bom")))
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_6
    targetCompatibility = JavaVersion.VERSION_1_6
}

if (java6home.isNotBlank()) {
    tasks.configureEach<JavaCompile> {
        options.forkOptions.executable = "$java6home/bin/java"
    }
} else if (JavaVersion.current() in JavaVersion.VERSION_1_10..JavaVersion.VERSION_11) {
    tasks.configureEach<JavaCompile> {
        options.compilerArgs.addAll(listOf("--release", "6"))
    }
}

tasks.processResources {
    from("${project(":postgresql").projectDir}/src/main/resources")
}

val sourceWithoutCheckerAnnotations by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    sourceWithoutCheckerAnnotations(project(":postgresql", "sourceWithoutCheckerAnnotations"))
}

val preprocessMain by tasks.registering(JavaCommentPreprocessorTask::class) {
    dependsOn(sourceWithoutCheckerAnnotations)
    baseDir.set(layout.file(provider { sourceWithoutCheckerAnnotations.singleFile }))
    sourceFolders.addAll("src/main/java", "src/main/version")
}

val preprocessTest by tasks.registering(JavaCommentPreprocessorTask::class) {
    baseDir.set(layout.file(provider { sourceWithoutCheckerAnnotations.singleFile }))
    sourceFolders.add("src/test/java")
}

ide {
    generatedJavaSources(
        preprocessMain,
        preprocessMain.get().outputDirectory.get().asFile,
        sourceSets.main
    )
    generatedJavaSources(
        preprocessTest,
        preprocessTest.get().outputDirectory.get().asFile,
        sourceSets.test
    )
}

tasks.test {
    // JUnit 5 requires Java 1.8+, so the tests can't be executed with Java 1.6 :-/
    enabled = false
}

tasks.compileTestJava {
    // JUnit 5 requires Java 1.8+, so the tests can't be executed with Java 1.6 :-/
    enabled = false
}

tasks.configureEach<JavaCommentPreprocessorTask> {
    excludedPatterns.addAll("**/jre7/", "**/jdbc41/")
    excludedPatterns.addAll("**/jre8/", "**/jdbc42/")
}

val osgiJar by tasks.registering(Bundle::class) {
    archiveClassifier.set("osgi")
    from(tasks.jar.map { zipTree(it.archiveFile) })
    withConvention(BundleTaskConvention::class) {
        bnd(
            """
            -exportcontents: !org.postgresql.shaded.*, org.postgresql.*
            -removeheaders: Created-By
            Bundle-Descriptiona: Java JDBC driver for PostgreSQL database
            Bundle-DocURL: https://jdbc.postgresql.org/
            Bundle-Vendor: PostgreSQL Global Development Group
            Import-Package: javax.sql, javax.transaction.xa, javax.naming, javax.security.sasl;resolution:=optional, *;resolution:=optional
            Bundle-Activator: org.postgresql.osgi.PGBundleActivator
            Bundle-SymbolicName: org.postgresql.jdbc
            Bundle-Name: PostgreSQL JDBC Driver
            Bundle-Copyright: Copyright (c) 2003-2020, PostgreSQL Global Development Group
            Require-Capability: osgi.ee;filter:="(&(|(osgi.ee=J2SE)(osgi.ee=JavaSE))(version>=1.6))"
            Provide-Capability: osgi.service;effective:=active;objectClass=org.osgi.service.jdbc.DataSourceFactory
            """
        )
    }
}

val extraMavenPublications by configurations.getting

(artifacts) {
    extraMavenPublications(osgiJar) {
        classifier = ""
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            artifactId = "postgresql"
            version = project.version.toString()
        }
    }
}
