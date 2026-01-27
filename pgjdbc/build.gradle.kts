/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

import aQute.bnd.gradle.Bundle
import com.github.autostyle.gradle.AutostyleTask
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
    id("build-logic.java-published-library")
    id("build-logic.test-junit5")
    id("build-logic.java-comment-preprocessor")
    id("build-logic.without-type-annotations")
    id("biz.aQute.bnd.builder") apply false
    id("com.gradleup.shadow")
    id("com.github.lburgazzoli.karaf")
    id("com.github.vlsi.gettext")
    id("com.github.vlsi.gradle-extensions")
    id("com.github.vlsi.ide")
    id("com.github.vlsi.stage-vote-release") apply false
}

buildscript {
    repositories {
        // E.g. for biz.aQute.bnd.builder which is not published to Gradle Plugin Portal
        mavenCentral()
    }
}

java {
    val sourceSets: SourceSetContainer by project
    registerFeature("sspi") {
        usingSourceSet(sourceSets["main"])
    }
    registerFeature("osgi") {
        usingSourceSet(sourceSets["main"])
    }
}

// Create a separate source set for Java 11+ specific code (e.g., java.lang.ref.Cleaner)
val java11 by sourceSets.creating {
    java {
        srcDir("src/main/java11")
    }
    // Make java11 source set depend on main source set (to access LazyCleaner base class)
    compileClasspath += sourceSets.main.get().output
}

if (buildParameters.testJdkVersion >= 11) {
    // By default, Gradle uses "test classes" dir for classpath, so multi-release jar is not used there
    // So we explicitly prepend the classpath with Java 11 classes
    tasks.test {
        classpath = java11.output + classpath
    }
}

// Configure the java11 source set to compile with Java 11
tasks.named<JavaCompile>(java11.compileJavaTaskName) {
    options.release.set(11)
    // Ensure main classes are compiled before java11 classes
    dependsOn(tasks.compileJava)
}

fun CopySpec.addMultiReleaseContents() {
    into("META-INF/versions/11") {
        from(java11.output)
    }
}

// Add java11 compiled classes to the main JAR
tasks.jar {
    addMultiReleaseContents()
}

val knows by tasks.existing {
    group = null // Hide the task from `./gradlew tasks` output
    description = "This is a dummy task, unfortunately the author refuses to remove it: https://github.com/johnrengelman/shadow/issues/122"
}

val shaded by configurations.creating

val karafFeatures by configurations.creating {
    isTransitive = false
}

val testKitSourcesWithoutAnnotations by configurations.dependencyScope("testKitSourcesWithoutAnnotations") {
    description = "Declares dependencies on sources-without-annotations"
}

val testKitSourcesWithoutAnnotationsResolved by configurations.resolvable("testKitSourcesWithoutAnnotationsResolved") {
    description = "Resolves sources-without-annotations dependencies"
    extendsFrom(testKitSourcesWithoutAnnotations)
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named("sources-without-annotations"))
    }
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
    constraints {
        api("com.github.waffle:waffle-jna:1.9.1")
        api("org.osgi:org.osgi.core:6.0.0")
        api("org.osgi:org.osgi.service.jdbc:1.0.0")
        testCompileOnly("junit:junit") {
            because("We use JUnit 5 for testing, so we do not like to have JUnit 4 on the classpath")
            version {
                rejectAll()
            }
        }
    }

    "sspiImplementation"("com.github.waffle:waffle-jna")
    // The dependencies are provided by OSGi container,
    // so they should not be exposed as transitive dependencies
    "osgiCompileOnly"("org.osgi:org.osgi.core")
    "osgiCompileOnly"("org.osgi:org.osgi.service.jdbc")
    "testImplementation"("org.osgi:org.osgi.service.jdbc") {
        because("DataSourceFactory is needed for PGDataSourceFactoryTest")
    }
    shaded("com.ongres.scram:scram-client:3.2")

    implementation("org.checkerframework:checker-qual:3.52.0")
    java11.implementationConfigurationName("org.checkerframework:checker-qual:3.52.0")

    testKitSourcesWithoutAnnotations(projects.testkit)

    testImplementation(projects.testkit)
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

val preprocessVersion by tasks.registering(buildlogic.JavaCommentPreprocessorTask::class) {
    baseDir.set(projectDir)
    sourceFolders.add("src/main/version")
}

// <editor-fold defaultstate="collapsed" desc="Workaround Gradle 7 warning on check style tasks depending on preprocessVersion results">
tasks.withType<Checkstyle>().configureEach {
    mustRunAfter(preprocessVersion)
}

tasks.withType<AutostyleTask>().configureEach {
    mustRunAfter(preprocessVersion)
}
// </editor-fold>

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
        attributes["Multi-Release"] = "true"
    }
}

tasks.shadowJar {
    configurations = listOf(shaded)
    exclude("META-INF/maven/**")
    // ignore module-info.class not used in shaded dependency
    exclude("META-INF/versions/9/module-info.class")
    // ignore service file not used in shaded dependency
    exclude("META-INF/services/com.ongres.stringprep.Profile")
    // We explicitly exclude all license-like files, and we re-add them in osgiJar later
    // It looks like shadowJar can't filter out META-INF/LICENSE, and files with the same name
    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
    exclude("LICENSE")
    exclude("NOTICE")
    addMultiReleaseContents()
    listOf(
            "com.ongres"
    ).forEach {
        relocate(it, "${project.group}.shaded.$it")
    }
}

val osgiJar by tasks.registering(Bundle::class) {
    archiveClassifier.set("osgi")
    from(tasks.shadowJar.map { zipTree(it.archiveFile) })
    into("META-INF") {
        dependencyLicenses(shadedLicenseFiles)
    }
    bundle {
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
            Bundle-Copyright: Copyright (c) 2003-2024, PostgreSQL Global Development Group
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

// <editor-fold defaultstate="collapsed" desc="Source distribution for building pgjdbc with minimal features">
val sourceDistribution by tasks.registering(Tar::class) {
    dependsOn(tasks.removeTypeAnnotations)
    dependsOn(testKitSourcesWithoutAnnotationsResolved)
    val withoutAnnotations = tasks.removeTypeAnnotations.get().destinationDir
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

    val testRuntimeClasspath = configurations.testRuntimeClasspath
    dependsOn(testRuntimeClasspath)

    val props by lazy {
        // Associate group:module with version, so %{group:module} can be used in reduced-pom.xml
        testRuntimeClasspath.get().incoming.resolutionResult.allComponents
            .associateBy({ it.moduleVersion!!.module.toString() }, { it.moduleVersion!!.version })
    }

    from("reduced-pom.xml") {
        rename { "pom.xml" }
        filter {
            it.replace(Regex("%\\{([^}]+)\\}")) {
                props[it.groups[1]!!.value] ?: throw GradleException("Unknown property in reduced-pom.xml: ${it.value}")
            }
        }
    }
    dependsOn(tasks.generateKar)
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
        from(testKitSourcesWithoutAnnotationsResolved.elements.map { set ->
            set.map {
                fileTree("$it/src/main")
            }
        })
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

val extractedSourceDistributionDir = layout.buildDirectory.dir("extracted-source-distribution")

val extractSourceDistribution by tasks.registering(Sync::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Extracts source distribution into build/extracted-source-distribution for manual analysis"
    from(tarTree(sourceDistribution.map { it.archiveFile.get() }))
    into(extractedSourceDistributionDir.map { it.asFile })
    eachFile {
        // Strip the first directory which is postgresql-version-jdbc-src
        path = path.substringAfter('/')
    }
}

val sourceDistributionTest by tasks.registering(Exec::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Executes Maven build against source distribution"
    dependsOn(extractSourceDistribution)
    workingDir(extractedSourceDistributionDir)
    executable = "mvn"
    args("--batch-mode", "--fail-at-end", "--show-version")
    args("verify")
}
// </editor-fold>

tasks.test {
    // Gradle detected a problem with the following location: '/.../pgjdbc/pgjdbc/build/libs/postgresql-42.7.1-SNAPSHOT.jar'.
    mustRunAfter(tasks.generateKar)
}

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
