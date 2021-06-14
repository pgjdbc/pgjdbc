/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

import aQute.bnd.gradle.Bundle
import aQute.bnd.gradle.BundleTaskConvention
import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.gettext.GettextTask
import com.github.vlsi.gradle.gettext.MsgAttribTask
import com.github.vlsi.gradle.gettext.MsgFmtTask
import com.github.vlsi.gradle.gettext.MsgMergeTask
import com.github.vlsi.gradle.license.GatherLicenseTask
import com.github.vlsi.gradle.properties.dsl.props
import com.github.vlsi.gradle.release.dsl.dependencyLicenses
import com.github.vlsi.gradle.release.dsl.licensesCopySpec

plugins {
    id("biz.aQute.bnd.builder") apply false
    id("com.github.johnrengelman.shadow")
    id("com.github.lburgazzoli.karaf")
    id("com.github.vlsi.gettext")
    id("com.github.vlsi.gradle-extensions")
    id("com.github.vlsi.ide")
}

buildscript {
    repositories {
        // E.g. for biz.aQute.bnd.builder which is not published to Gradle Plugin Portal
        mavenCentral()
    }
}

val shaded by configurations.creating

val karafFeatures by configurations.creating {
    isTransitive = false
}

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

val String.v: String get() = rootProject.extra["$this.version"] as String

dependencies {
    shaded(platform(project(":bom")))
    shaded("com.ongres.scram:client")

    implementation("org.checkerframework:checker-qual")
    testImplementation("se.jiderhamn:classloader-leak-test-framework")
}

val skipReplicationTests by props()
val enableGettext by props()

if (skipReplicationTests) {
    tasks.configureEach<Test> {
        exclude("org/postgresql/replication/**")
        exclude("org/postgresql/test/jdbc2/CopyBothResponseTest*")
    }
}

tasks.configureEach<Test> {
    outputs.cacheIf("test results on the database configuration, so we can't cache it") {
        false
    }
}

val preprocessVersion by tasks.registering(org.postgresql.buildtools.JavaCommentPreprocessorTask::class) {
    baseDir.set(projectDir)
    sourceFolders.add("src/main/version")
}

ide {
    generatedJavaSources(
        preprocessVersion,
        preprocessVersion.get().outputDirectory.get().asFile,
        sourceSets.main
    )
}

// <editor-fold defaultstate="collapsed" desc="Gettext tasks">
tasks.configureEach<Checkstyle> {
    exclude("**/messages_*")
}

val update_pot_with_new_messages by tasks.registering(GettextTask::class) {
    sourceFiles.from(sourceSets.main.get().allJava)
    keywords.add("GT.tr")
}

val remove_obsolete_translations by tasks.registering(MsgAttribTask::class) {
    args.add("--no-obsolete") // remove obsolete messages
    // TODO: move *.po to resources?
    poFiles.from(files(sourceSets.main.get().allSource).filter { it.path.endsWith(".po") })
}

val add_new_messages_to_po by tasks.registering(MsgMergeTask::class) {
    poFiles.from(remove_obsolete_translations)
    potFile.set(update_pot_with_new_messages.map { it.outputPot.get() })
}

val generate_java_resources by tasks.registering(MsgFmtTask::class) {
    poFiles.from(add_new_messages_to_po)
    targetBundle.set("org.postgresql.translation.messages")
}

val generateGettextSources by tasks.registering {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Updates .po, .pot, and .java files in src/main/java/org/postgresql/translation"
    dependsOn(add_new_messages_to_po)
    dependsOn(generate_java_resources)
    doLast {
        copy {
            into("src/main/java")
            from(generate_java_resources)
            into("org/postgresql/translation") {
                from(update_pot_with_new_messages)
                from(add_new_messages_to_po)
            }
        }
    }
}

tasks.compileJava {
    if (enableGettext) {
        dependsOn(generateGettextSources)
    } else {
        mustRunAfter(generateGettextSources)
    }
}
// </editor-fold>

// <editor-fold defaultstate="collapsed" desc="Third-party license gathering">
val getShadedDependencyLicenses by tasks.registering(GatherLicenseTask::class) {
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

tasks.configureEach<Jar> {
    manifest {
        attributes["Main-Class"] = "org.postgresql.util.PGJDBCMain"
        attributes["Automatic-Module-Name"] = "org.postgresql.jdbc"
    }
}

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
            Bundle-Description: Java JDBC driver for PostgreSQL database
            Bundle-DocURL: https://jdbc.postgresql.org/
            Bundle-Vendor: PostgreSQL Global Development Group
            Import-Package: javax.sql, javax.transaction.xa, javax.naming, javax.security.sasl;resolution:=optional, *;resolution:=optional
            Bundle-Activator: org.postgresql.osgi.PGBundleActivator
            Bundle-SymbolicName: org.postgresql.jdbc
            Bundle-Name: PostgreSQL JDBC Driver
            Bundle-Copyright: Copyright (c) 2003-2020, PostgreSQL Global Development Group
            Require-Capability: osgi.ee;filter:="(&(|(osgi.ee=J2SE)(osgi.ee=JavaSE))(version>=1.8))"
            Provide-Capability: osgi.service;effective:=active;objectClass=org.osgi.service.jdbc.DataSourceFactory;osgi.jdbc.driver.class=org.postgresql.Driver;osgi.jdbc.driver.name=PostgreSQL JDBC Driver
            """
        )
    }
}

karaf {
    features.apply {
        xsdVersion = "1.5.0"
        feature(closureOf<com.github.lburgazzoli.gradle.plugin.karaf.features.model.FeatureDescriptor> {
            name = "postgresql"
            description = "PostgreSQL JDBC driver karaf feature"
            version = project.version.toString()
            details = "Java JDBC 4.2 (JRE 8+) driver for PostgreSQL database"
            feature("transaction-api")
            includeProject = true
            bundle(
                project.group.toString(),
                closureOf<com.github.lburgazzoli.gradle.plugin.karaf.features.model.BundleDescriptor> {
                    wrap = false
                })
            // List argument clears the "default" configurations
            configurations(listOf(karafFeatures))
        })
    }
}

// <editor-fold defaultstate="collapsed" desc="Trim checkerframework annotations from the source code">
val withoutAnnotations = layout.buildDirectory.dir("without-annotations").get().asFile

val sourceWithoutCheckerAnnotations by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = true
}

val hiddenAnnotation = Regex(
    "@(?:Nullable|NonNull|PolyNull|MonotonicNonNull|RequiresNonNull|EnsuresNonNull|" +
            "Regex|" +
            "Pure|" +
            "KeyFor|" +
            "Positive|NonNegative|IntRange|" +
            "GuardedBy|UnderInitialization|" +
            "DefaultQualifier)(?:\\([^)]*\\))?")
val hiddenImports = Regex("import org.checkerframework")

val removeTypeAnnotations by tasks.registering(Sync::class) {
    destinationDir = withoutAnnotations
    inputs.property("regexpsUpdatedOn", "2020-08-25")
    from(projectDir) {
        filteringCharset = `java.nio.charset`.StandardCharsets.UTF_8.name()
        filter { x: String ->
            x.replace(hiddenAnnotation, "/* $0 */")
                .replace(hiddenImports, "// $0")
        }
        include("src/**")
    }
}

(artifacts) {
    sourceWithoutCheckerAnnotations(withoutAnnotations) {
        builtBy(removeTypeAnnotations)
    }
}
// </editor-fold>

// <editor-fold defaultstate="collapsed" desc="Source distribution for building pgjdbc with minimal features">
val sourceDistribution by tasks.registering(Tar::class) {
    dependsOn(removeTypeAnnotations)
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Source distribution for building pgjdbc with minimal features"
    archiveClassifier.set("jdbc-src")
    archiveExtension.set("tar.gz")
    compression = Compression.GZIP
    includeEmptyDirs = false

    into(provider { archiveBaseName.get() + "-" + archiveVersion.get() + "-" + archiveClassifier.get() })

    from(rootDir) {
        include("build.properties")
        include("ssltest.properties")
        include("LICENSE")
        include("README.md")
    }

    val props = listOf(
        "pgjdbc.version",
        "junit4.version",
        "junit5.version",
        "classloader-leak-test-framework.version",
        "com.ongres.scram.client.version"
    ).associate { propertyName ->
        val value = project.findProperty(propertyName) as String
        inputs.property(propertyName, project.findProperty(propertyName))
        "%{$propertyName}" to value
    }

    from("reduced-pom.xml") {
        rename { "pom.xml" }
        filter {
            it.replace(Regex("%\\{[^}]+\\}")) {
                props[it.value] ?: throw GradleException("Unknown property in reduced-pom.xml: $it")
            }
        }
    }
    into("src/main/resources") {
        from(tasks.jar.map {
            zipTree(it.archiveFile).matching {
                include("META-INF/MANIFEST.MF")
            }
        })
        into("META-INF") {
            dependencyLicenses(shadedLicenseFiles)
        }
    }
    into("src/main") {
        into("java") {
            from(preprocessVersion)
        }
        from("$withoutAnnotations/src/main") {
            exclude("resources/META-INF/LICENSE")
            exclude("checkstyle")
            exclude("*/org/postgresql/osgi/**")
            exclude("*/org/postgresql/sspi/NTDSAPI.java")
            exclude("*/org/postgresql/sspi/NTDSAPIWrapper.java")
            exclude("*/org/postgresql/sspi/SSPIClient.java")
        }
    }
    into("src/test") {
        from("$withoutAnnotations/src/test") {
            exclude("*/org/postgresql/test/osgi/**")
            exclude("**/*Suite*")
            exclude("*/org/postgresql/test/sspi/*.java")
            exclude("*/org/postgresql/replication/**")
        }
    }
    into("certdir") {
        from("$rootDir/certdir") {
            include("good*")
            include("bad*")
            include("Makefile")
            include("README.md")
            from("server") {
                include("root*")
                include("server*")
                include("pg_hba.conf")
            }
        }
    }
}
// </editor-fold>

val extraMavenPublications by configurations.getting

(artifacts) {
    extraMavenPublications(sourceDistribution)
    extraMavenPublications(osgiJar) {
        classifier = ""
    }
    extraMavenPublications(karaf.features.outputFile) {
        builtBy(tasks.named("generateFeatures"))
        classifier = "features"
    }
}

// <editor-fold defaultstate="collapsed" desc="Populates build/local-maven-repo with artifacts produced by the current project for testing purposes">
val localRepoElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    description =
        "Shares local maven repository directory that contains the artifacts produced by the current project"
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named("maven-repository"))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

val localRepoDir = layout.buildDirectory.dir("local-maven-repo")

publishing {
    repositories {
        maven {
            name = "local"
            url = uri(localRepoDir)
        }
    }
}

localRepoElements.outgoing.artifact(localRepoDir) {
    builtBy(tasks.named("publishAllPublicationsToLocalRepository"))
}

val cleanLocalRepository by tasks.registering(Delete::class) {
    description = "Clears local-maven-repo so timestamp-based snapshot artifacts do not consume space"
    delete(localRepoDir)
}

tasks.withType<PublishToMavenRepository>()
    .matching { it.name.contains("ToLocalRepository") }
    .configureEach {
        dependsOn(cleanLocalRepository)
    }
// </editor-fold>
