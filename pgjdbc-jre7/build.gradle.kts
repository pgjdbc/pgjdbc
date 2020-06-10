/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

import aQute.bnd.gradle.Bundle
import aQute.bnd.gradle.BundleTaskConvention
import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.properties.dsl.props
import com.github.vlsi.gradle.release.dsl.dependencyLicenses
import com.github.vlsi.gradle.release.dsl.licensesCopySpec
import org.postgresql.buildtools.JavaCommentPreprocessorTask

plugins {
    `java-library`
    id("com.github.vlsi.ide")
    id("com.github.vlsi.gradle-extensions")
    id("com.github.johnrengelman.shadow")
    id("biz.aQute.bnd.builder") apply false
}

buildscript {
    repositories {
        // E.g. for biz.aQute.bnd.builder which is not published to Gradle Plugin Portal
        mavenCentral()
    }
}

version = version.toString().replaceFirst(Regex("(-SNAPSHOT)?$"), ".jre7\$1")
setProperty("archivesBaseName", "postgresql")

val java7home by props("")

// <editor-fold defaultstate="collapsed" desc="Shade configuration">
val shaded by configurations.creating

configurations {
    compileOnly {
        extendsFrom(shaded)
    }
    // Add shaded dependencies to test as well
    // This enables to execute unit tests with original (non-shaded dependencies)
    testImplementation {
        extendsFrom(shaded)
    }
}
// </editor-fold>

val String.v: String get() = rootProject.extra["$this.version"] as String

dependencies {
    shaded(platform(project(":bom")))
    shaded("com.ongres.scram:client")
    testImplementation("se.jiderhamn:classloader-leak-test-framework")
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_7
    targetCompatibility = JavaVersion.VERSION_1_7
}

tasks.processResources {
    from("${project(":postgresql").projectDir}/src/main/resources")
}

val preprocessMain by tasks.registering(JavaCommentPreprocessorTask::class) {
    baseDir.set(project(":postgresql").projectDir)
    sourceFolders.addAll("src/main/java", "src/main/version")
}

val preprocessTest by tasks.registering(JavaCommentPreprocessorTask::class) {
    baseDir.set(project(":postgresql").projectDir)
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

if (java7home.isNotBlank()) {
    tasks.configureEach<JavaCompile> {
        options.forkOptions.executable = "$java7home/bin/java"
    }
} else if (JavaVersion.current() >= JavaVersion.VERSION_1_10) {
    tasks.configureEach<JavaCompile> {
        options.compilerArgs.addAll(listOf("--release", "7"))
    }
}

tasks.test {
    // JUnit 5 requires Java 1.8+, so the tests can't be executed with Java 1.7 :-/
    enabled = false
}

tasks.compileTestJava {
    // JUnit 5 requires Java 1.8+, so the tests can't be executed with Java 1.7 :-/
    enabled = false
}

tasks.configureEach<JavaCommentPreprocessorTask> {
    excludedPatterns.addAll("**/jre8/", "**/jdbc42/")
}

// <editor-fold defaultstate="collapsed" desc="Third-party license gathering">
val getShadedDependencyLicenses by tasks.registering(
    com.github.vlsi.gradle.license.GatherLicenseTask::class) {
    configuration(shaded)
    extraLicenseDir.set(file("$rootDir/licenses"))
    overrideLicense("com.ongres.scram:common") {
        licenseFiles = "scram"
    }
    overrideLicense("com.ongres.scram:client") {
        licenseFiles = "scram"
    }
    overrideLicense("com.ongres.stringprep:saslprep") {
        licenseFiles = "stringprep"
    }
    overrideLicense("com.ongres.stringprep:stringprep") {
        licenseFiles = "stringprep"
    }
}

val renderShadedLicense by tasks.registering(com.github.vlsi.gradle.release.Apache2LicenseRenderer::class) {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Generate LICENSE file for shaded jar"
    mainLicenseFile.set(File(rootDir, "LICENSE"))
    failOnIncompatibleLicense.set(false)
    artifactType.set(com.github.vlsi.gradle.release.ArtifactType.BINARY)
    metadata.from(getShadedDependencyLicenses)
}

val shadedLicenseFiles = licensesCopySpec(renderShadedLicense)
// </editor-fold>

tasks.shadowJar {
    configurations = listOf(shaded)
    exclude("META-INF/maven/**")
    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
    into("META-INF") {
        dependencyLicenses(shadedLicenseFiles)
    }
    listOf(
        "com.ongres"
    ).forEach {
        relocate(it, "${project.group}.shaded.$it")
    }
}

val osgiJar by tasks.registering(Bundle::class) {
    archiveClassifier.set("osgi")
    from(tasks.shadowJar.map { zipTree(it.archiveFile) })
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
            Require-Capability: osgi.ee;filter:="(&(|(osgi.ee=J2SE)(osgi.ee=JavaSE))(version>=1.7))"
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
