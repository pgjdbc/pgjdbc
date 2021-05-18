/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

import com.github.spotbugs.SpotBugsTask
import com.github.vlsi.gradle.crlf.CrLfSpec
import com.github.vlsi.gradle.crlf.LineEndings
import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.git.FindGitAttributes
import com.github.vlsi.gradle.properties.dsl.props
import com.github.vlsi.gradle.properties.dsl.stringProperty
import com.github.vlsi.gradle.publishing.dsl.simplifyXml
import com.github.vlsi.gradle.publishing.dsl.versionFromResolution
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApisExtension
import org.postgresql.buildtools.JavaCommentPreprocessorTask

plugins {
    publishing
    // Verification
    checkstyle
    jacoco
    id("com.github.autostyle")
    id("com.github.spotbugs")
    id("org.owasp.dependencycheck")
    id("org.checkerframework") apply false
    id("com.github.johnrengelman.shadow") apply false
    id("de.thetaphi.forbiddenapis") apply false
    id("org.nosphere.gradle.github.actions")
    id("com.github.vlsi.jandex") apply false
    // IDE configuration
    id("org.jetbrains.gradle.plugin.idea-ext")
    id("com.github.vlsi.ide")
    // Release
    id("com.github.vlsi.crlf")
    id("com.github.vlsi.gradle-extensions")
    id("com.github.vlsi.license-gather") apply false
    id("com.github.vlsi.stage-vote-release")
}

fun reportsForHumans() = !(System.getenv()["CI"]?.toBoolean() ?: props.bool("CI"))

val lastEditYear = 2020 // TODO: by extra(lastEditYear("$rootDir/LICENSE"))

// Do not enable spotbugs by default. Execute it only when -Pspotbugs is present
val enableSpotBugs = props.bool("spotbugs", default = false)
val enableCheckerframework by props()
val skipCheckstyle by props()
val skipAutostyle by props()
val skipJavadoc by props()
val skipForbiddenApis by props()
val enableMavenLocal by props()
val enableGradleMetadata by props()
// For instance -PincludeTestTags=!org.postgresql.test.SlowTests
//           or -PincludeTestTags=!org.postgresql.test.Replication
val includeTestTags by props("")
// By default use Java implementation to sign artifacts
// When useGpgCmd=true, then gpg command line tool is used for signing
val useGpgCmd by props()
val slowSuiteLogThreshold = stringProperty("slowSuiteLogThreshold")?.toLong() ?: 0
val slowTestLogThreshold = stringProperty("slowTestLogThreshold")?.toLong() ?: 2000
val jacocoEnabled by extra {
    props.bool("coverage") || gradle.startParameter.taskNames.any { it.contains("jacoco") }
}

ide {
    // TODO: set copyright to PostgreSQL Global Development Group
    // copyrightToAsf()
    ideaInstructionsUri =
        uri("https://github.com/pgjdbc/pgjdbc")
    doNotDetectFrameworks("android", "jruby")
}

// This task scans the project for gitignore / gitattributes, and that is reused for building
// source/binary artifacts with the appropriate eol/executable file flags
// It enables to automatically exclude patterns from .gitignore
val gitProps by tasks.registering(FindGitAttributes::class) {
    // Scanning for .gitignore and .gitattributes files in a task avoids doing that
    // when distribution build is not required (e.g. code is just compiled)
    root.set(rootDir)
}

val String.v: String get() = rootProject.extra["$this.version"] as String

val buildVersion = "pgjdbc".v + releaseParams.snapshotSuffix

println("Building pgjdbc $buildVersion")

val isReleaseVersion = rootProject.releaseParams.release.get()

// Configures URLs to SVN and Nexus

val licenseHeaderFile = file("config/license.header.java")

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

    apply(plugin = "com.github.vlsi.gradle-extensions")

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

    repositories {
        if (enableMavenLocal) {
            mavenLocal()
        }
        mavenCentral()
    }

    val javaMainUsed = file("src/main/java").isDirectory
    val javaTestUsed = file("src/test/java").isDirectory
    val javaUsed = javaMainUsed || javaTestUsed
    if (javaUsed) {
        apply(plugin = "java-library")
        if (jacocoEnabled) {
            apply(plugin = "jacoco")
        }
    }

    plugins.withId("java-library") {
        dependencies {
            "implementation"(platform(project(":bom")))
        }
    }

    val kotlinMainUsed = file("src/main/kotlin").isDirectory
    val kotlinTestUsed = file("src/test/kotlin").isDirectory
    val kotlinUsed = kotlinMainUsed || kotlinTestUsed
    if (kotlinUsed) {
        apply(plugin = "java-library")
        apply(plugin = "org.jetbrains.kotlin.jvm")
        dependencies {
            add(if (kotlinMainUsed) "implementation" else "testImplementation", kotlin("stdlib"))
        }
    }

    val hasTests = javaTestUsed || kotlinTestUsed
    if (hasTests) {
        // Add default tests dependencies
        dependencies {
            val testImplementation by configurations
            val testRuntimeOnly by configurations
            testImplementation("org.junit.jupiter:junit-jupiter-api")
            testImplementation("org.junit.jupiter:junit-jupiter-params")
            testImplementation("org.hamcrest:hamcrest")
            testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
            if (project.props.bool("junit4", default = true)) {
                // Allow projects to opt-out of junit dependency, so they can be JUnit5-only
                testImplementation("junit:junit")
                testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
            }
        }
    }

    if (!skipAutostyle) {
        apply(plugin = "com.github.autostyle")
        autostyle {
            kotlinGradle {
                ktlint()
                trimTrailingWhitespace()
                endWithNewline()
            }
            format("markdown") {
                target("**/*.md")
                endWithNewline()
            }
        }
    }
    val skipCheckstyle = skipCheckstyle || props.bool("skipCheckstyle")
    if (!skipCheckstyle) {
        apply<CheckstylePlugin>()
        dependencies {
            checkstyle("com.puppycrawl.tools:checkstyle:${"checkstyle".v}")
        }
        checkstyle {
            // Current one is ~8.8
            // https://github.com/julianhyde/toolbox/issues/3
            isShowViolations = true
            // TOOD: move to /config
            configDirectory.set(File(rootDir, "pgjdbc/src/main/checkstyle"))
            configFile = configDirectory.get().file("checks.xml").asFile
        }

        val checkstyleTasks = tasks.withType<Checkstyle>()
        checkstyleTasks.configureEach {
            // Checkstyle 8.26 does not need classpath, see https://github.com/gradle/gradle/issues/14227
            classpath = files()
        }

        tasks.register("checkstyleAll") {
            dependsOn(checkstyleTasks)
        }
    }
    if (!skipAutostyle || !skipCheckstyle) {
        tasks.register("style") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Formats code (license header, import order, whitespace at end of line, ...) and executes Checkstyle verifications"
            if (!skipAutostyle) {
                dependsOn("autostyleApply")
            }
            if (!skipCheckstyle) {
                dependsOn("checkstyleAll")
            }
        }
    }

    tasks.configureEach<AbstractArchiveTask> {
        // Ensure builds are reproducible
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dirMode = "775".toInt(8)
        fileMode = "664".toInt(8)
    }

    plugins.withType<SigningPlugin> {
        afterEvaluate {
            configure<SigningExtension> {
                val release = rootProject.releaseParams.release.get()
                // Note it would still try to sign the artifacts,
                // however it would fail only when signing a RELEASE version fails
                isRequired = release
                if (useGpgCmd) {
                    useGpgCmd()
                }
            }
        }
    }

    plugins.withType<JacocoPlugin> {
        the<JacocoPluginExtension>().toolVersion = "jacoco".v

        val testTasks = tasks.withType<Test>()
        val javaExecTasks = tasks.withType<JavaExec>()
        // This configuration must be postponed since JacocoTaskExtension might be added inside
        // configure block of a task (== before this code is run). See :src:dist-check:createBatchTask
        afterEvaluate {
            for (t in arrayOf(testTasks, javaExecTasks)) {
                t.configureEach {
                    extensions.findByType<JacocoTaskExtension>()?.apply {
                        // Do not collect coverage when not asked (e.g. via jacocoReport or -Pcoverage)
                        isEnabled = jacocoEnabled
                        // We don't want to collect coverage for third-party classes
                        includes?.add("org.postgresql.*")
                    }
                }
            }
        }

        jacocoReport {
            // Note: this creates a lazy collection
            // Some of the projects might fail to create a file (e.g. no tests or no coverage),
            // So we check for file existence. Otherwise JacocoMerge would fail
            val execFiles =
                    files(testTasks, javaExecTasks).filter { it.exists() && it.name.endsWith(".exec") }
            executionData(execFiles)
        }

        tasks.configureEach<JacocoReport> {
            reports {
                html.isEnabled = reportsForHumans()
                xml.isEnabled = !reportsForHumans()
            }
        }
    }

    tasks {
        // <editor-fold defaultstate="collapsed" desc="JavaCommentPreprocessor: configure version variables">
        configureEach<JavaCommentPreprocessorTask> {
            variables.apply {
                val jdbcSpec = props.string("jdbc.specification.version")
                put("mvn.project.property.postgresql.jdbc.spec", "JDBC$jdbcSpec")
                put("jdbc.specification.version", jdbcSpec)
            }

            val re = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*")

            val version = project.version.toString()
            val matchResult = re.find(version) ?: throw GradleException("Unable to parse major.minor.patch version parts from project.version '$version'")
            val (major, minor, patch) = matchResult.destructured

            variables.apply {
                put("version", version)
                put("version.major", major)
                put("version.minor", minor)
                put("version.patch", patch.ifBlank { "0" })
            }
        }
        // </editor-fold>

        // <editor-fold defaultstate="collapsed" desc="Javadoc configuration">
        configureEach<Javadoc> {
            (options as StandardJavadocDocletOptions).apply {
                // Please refrain from using non-ASCII chars below since the options are passed as
                // javadoc.options file which is parsed with "default encoding"
                noTimestamp.value = true
                showFromProtected()
                if (props.bool("failOnJavadocWarning", default = true)) {
                    // See JDK-8200363 (https://bugs.openjdk.java.net/browse/JDK-8200363)
                    // for information about the -Xwerror option.
                    addBooleanOption("Xwerror", true)
                }
                // javadoc: error - The code being documented uses modules but the packages
                // defined in https://docs.oracle.com/javase/9/docs/api/ are in the unnamed module
                source = "1.8"
                docEncoding = "UTF-8"
                charSet = "UTF-8"
                encoding = "UTF-8"
                docTitle = "PostgreSQL JDBC ${project.name} API version ${project.version}"
                windowTitle = "PostgreSQL JDBC ${project.name} API version ${project.version}"
                header = "<b>PostgreSQL JDBC</b>"
                bottom =
                    "Copyright &copy; 1997-$lastEditYear PostgreSQL Global Development Group. All Rights Reserved."
                if (JavaVersion.current() >= JavaVersion.VERSION_1_9) {
                    addBooleanOption("html5", true)
                    links("https://docs.oracle.com/javase/9/docs/api/")
                } else {
                    links("https://docs.oracle.com/javase/8/docs/api/")
                }
            }
        }
        // </editor-fold>
    }

    plugins.withType<JavaPlugin> {
        configure<JavaPluginConvention> {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        configure<JavaPluginExtension> {
            withSourcesJar()
            if (!skipJavadoc) {
                withJavadocJar()
            }
        }

        val sourceSets: SourceSetContainer by project

        apply(plugin = "com.github.vlsi.jandex")
        apply(plugin = "maven-publish")

        project.configure<com.github.vlsi.jandex.JandexExtension> {
            skipIndexFileGeneration()
        }

        if (!enableGradleMetadata) {
            tasks.withType<GenerateModuleMetadata> {
                enabled = false
            }
        }

        if (!skipForbiddenApis && !props.bool("skipCheckstyle")) {
            apply(plugin = "de.thetaphi.forbiddenapis")
            configure<CheckForbiddenApisExtension> {
                failOnUnsupportedJava = false
                bundledSignatures.addAll(
                    listOf(
                        // "jdk-deprecated",
                        "jdk-internal",
                        "jdk-non-portable"
                        // "jdk-system-out"
                        // "jdk-unsafe"
                    )
                )
            }
            tasks.configureEach<CheckForbiddenApis> {
                exclude("**/org/postgresql/util/internal/Unsafe.class")
            }
        }

        if (!skipAutostyle) {
            autostyle {
                java {
                    // targetExclude("**/test/java/*.java")
                    // TODO: implement license check (with copyright year)
                    // licenseHeaderFile(licenseHeaderFile)
                    importOrder(
                        "static ",
                        "org.postgresql.",
                        "",
                        "java.",
                        "javax."
                    )
                    removeUnusedImports()
                    trimTrailingWhitespace()
                    indentWithSpaces(4)
                    endWithNewline()
                }
            }
        }

        if (enableCheckerframework) {
            apply(plugin = "org.checkerframework")
            dependencies {
                "checkerFramework"("org.checkerframework:checker:${"checkerframework".v}")
                // CheckerFramework annotations might be used in the code as follows:
                // dependencies {
                //     "compileOnly"("org.checkerframework:checker-qual")
                //     "testCompileOnly"("org.checkerframework:checker-qual")
                // }
                if (JavaVersion.current() == JavaVersion.VERSION_1_8) {
                    // only needed for JDK 8
                    "checkerFrameworkAnnotatedJDK"("org.checkerframework:jdk8:${"checkerframework".v}")
                }
            }
            configure<org.checkerframework.gradle.plugin.CheckerFrameworkExtension> {
                skipVersionCheck = true
                excludeTests = true
                // See https://checkerframework.org/manual/#introduction
                checkers.add("org.checkerframework.checker.nullness.NullnessChecker")
                checkers.add("org.checkerframework.checker.optional.OptionalChecker")
                // checkers.add("org.checkerframework.checker.index.IndexChecker")
                checkers.add("org.checkerframework.checker.regex.RegexChecker")
                extraJavacArgs.add("-Astubs=" +
                        fileTree("$rootDir/config/checkerframework") {
                            include("*.astub")
                        }.asPath
                )
                // Translation classes are autogenerated, and they
                extraJavacArgs.add("-AskipDefs=^org\\.postgresql\\.translation\\.")
                // The below produces too many warnings :(
                // extraJavacArgs.add("-Alint=redundantNullComparison")
            }
        }

        if (jacocoEnabled) {
            // Add each project to combined report
            val mainCode = sourceSets["main"]
            jacocoReport.configure {
                additionalSourceDirs.from(mainCode.allJava.srcDirs)
                sourceDirectories.from(mainCode.allSource.srcDirs)
                classDirectories.from(mainCode.output)
            }
        }

        if (enableSpotBugs) {
            apply(plugin = "com.github.spotbugs")
            spotbugs {
                toolVersion = "spotbugs".v
                reportLevel = "high"
                //  excludeFilter = file("$rootDir/src/main/config/spotbugs/spotbugs-filter.xml")
                // By default spotbugs verifies TEST classes as well, and we do not want that
                this.sourceSets = listOf(sourceSets["main"])
            }
            dependencies {
                // Parenthesis are needed here: https://github.com/gradle/gradle/issues/9248
                (constraints) {
                    "spotbugs"("org.ow2.asm:asm:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-all:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-analysis:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-commons:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-tree:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-util:${"asm".v}")
                }
            }
        }

        (sourceSets) {
            "main" {
                resources {
                    // TODO: remove when LICENSE is removed (it is used by Maven build for now)
                    exclude("src/main/resources/META-INF/LICENSE")
                }
            }
        }

        tasks {
            configureEach<Jar> {
                manifest {
                    attributes["Bundle-License"] = "BSD-2-Clause"
                    attributes["Implementation-Title"] = "PostgreSQL JDBC Driver"
                    attributes["Implementation-Version"] = project.version
                    val jdbcSpec = props.string("jdbc.specification.version")
                    if (jdbcSpec.isNotBlank()) {
                        attributes["Specification-Vendor"] = "Oracle Corporation"
                        attributes["Specification-Version"] = jdbcSpec
                        attributes["Specification-Title"] = "JDBC"
                    }
                    attributes["Implementation-Vendor"] = "PostgreSQL Global Development Group"
                    attributes["Implementation-Vendor-Id"] = "org.postgresql"
                }
            }

            configureEach<JavaCompile> {
                options.encoding = "UTF-8"
            }
            configureEach<Test> {
                useJUnitPlatform {
                    if (includeTestTags.isNotBlank()) {
                        includeTags.add(includeTestTags)
                    }
                }
                inputs.file("../build.properties")
                if (file("../build.local.properties").exists()) {
                    inputs.file("../build.local.properties")
                }
                inputs.file("../ssltest.properties")
                if (file("../ssltest.local.properties").exists()) {
                    inputs.file("../ssltest.local.properties")
                }
                testLogging {
                    showStandardStreams = true
                }
                exclude("**/*Suite*")
                jvmArgs("-Xmx1536m")
                jvmArgs("-Djdk.net.URLClassPath.disableClassPathURLCheck=true")
                // Pass the property to tests
                fun passProperty(name: String, default: String? = null) {
                    val value = System.getProperty(name) ?: default
                    value?.let { systemProperty(name, it) }
                }
                passProperty("preferQueryMode")
                passProperty("java.awt.headless")
                passProperty("junit.jupiter.execution.parallel.enabled", "true")
                passProperty("junit.jupiter.execution.timeout.default", "5 m")
                passProperty("user.language", "TR")
                passProperty("user.country", "tr")
                val props = System.getProperties()
                for (e in props.propertyNames() as `java.util`.Enumeration<String>) {
                    if (e.startsWith("pgjdbc.") || e.startsWith("java")) {
                        passProperty(e)
                    }
                }
                for (p in listOf("server", "port", "database", "username", "password",
                        "privilegedUser", "privilegedPassword",
                        "simpleProtocolOnly", "enable_ssl_tests")) {
                    passProperty(p)
                }
            }
            configureEach<SpotBugsTask> {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                if (enableSpotBugs) {
                    description = "$description (skipped by default, to enable it add -Dspotbugs)"
                }
                reports {
                    html.isEnabled = reportsForHumans()
                    xml.isEnabled = !reportsForHumans()
                }
                enabled = enableSpotBugs
            }

            afterEvaluate {
                // Add default license/notice when missing
                configureEach<Jar> {
                    CrLfSpec(LineEndings.LF).run {
                        into("META-INF") {
                            filteringCharset = "UTF-8"
                            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                            // Note: we need "generic Apache-2.0" text without third-party items
                            // So we use the text from $rootDir/config/ since source distribution
                            // contains altered text at $rootDir/LICENSE
                            textFrom("$rootDir/src/main/config/licenses/LICENSE")
                            textFrom("$rootDir/NOTICE")
                        }
                    }
                }
            }
        }

        configure<PublishingExtension> {
            if (!project.props.bool("nexus.publish", default = true)) {
                // Some of the artifacts do not need to be published
                return@configure
            }

            publications {
                // <editor-fold defaultstate="collapsed" desc="Override published artifacts (e.g. shaded instead of regular)">
                val extraMavenPublications by configurations.creating {
                    isVisible = false
                    isCanBeResolved = false
                    isCanBeConsumed = false
                }
                afterEvaluate {
                    named<MavenPublication>(project.name) {
                        extraMavenPublications.outgoing.artifacts.apply {
                            val keys = mapTo(HashSet()) {
                                it.classifier.orEmpty() to it.extension
                            }
                            artifacts.removeIf {
                                keys.contains(it.classifier.orEmpty() to it.extension)
                            }
                            forEach { artifact(it) }
                        }
                    }
                }
                // </editor-fold>
                // <editor-fold defaultstate="collapsed" desc="Configuration of the published pom.xml">
                create<MavenPublication>(project.name) {
                    artifactId = project.name
                    version = rootProject.version.toString()
                    from(components["java"])

                    // Gradle feature variants can't be mapped to Maven's pom
                    suppressAllPomMetadataWarnings()

                    // Use the resolved versions in pom.xml
                    // Gradle might have different resolution rules, so we set the versions
                    // that were used in Gradle build/test.
                    versionFromResolution()
                    pom {
                        simplifyXml()
                        name.set(
                            (project.findProperty("artifact.name") as? String) ?: "pgdjbc ${project.name.capitalize()}"
                        )
                        description.set(project.description ?: "PostgreSQL JDBC Driver ${project.name.capitalize()}")
                        inceptionYear.set("1997")
                        url.set("https://jdbc.postgresql.org")
                        licenses {
                            license {
                                name.set("BSD-2-Clause")
                                url.set("https://jdbc.postgresql.org/about/license.html")
                                comments.set("BSD-2-Clause, copyright PostgreSQL Global Development Group")
                                distribution.set("repo")
                            }
                        }
                        organization {
                            name.set("PostgreSQL Global Development Group")
                            url.set("https://jdbc.postgresql.org/")
                        }
                        developers {
                            developer {
                                id.set("davecramer")
                                name.set("Dave Cramer")
                            }
                            developer {
                                id.set("jurka")
                                name.set("Kris Jurka")
                            }
                            developer {
                                id.set("oliver")
                                name.set("Oliver Jowett")
                            }
                            developer {
                                id.set("ringerc")
                                name.set("Craig Ringer")
                            }
                            developer {
                                id.set("vlsi")
                                name.set("Vladimir Sitnikov")
                            }
                            developer {
                                id.set("bokken")
                                name.set("Brett Okken")
                            }
                        }
                        issueManagement {
                            system.set("GitHub issues")
                            url.set("https://github.com/pgjdbc/pgjdbc/issues")
                        }
                        mailingLists {
                            mailingList {
                                name.set("PostgreSQL JDBC development list")
                                subscribe.set("https://lists.postgresql.org/")
                                unsubscribe.set("https://lists.postgresql.org/unsubscribe/")
                                post.set("pgsql-jdbc@postgresql.org")
                                archive.set("https://www.postgresql.org/list/pgsql-jdbc/")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/pgjdbc/pgjdbc.git")
                            developerConnection.set("scm:git:https://github.com/pgjdbc/pgjdbc.git")
                            url.set("https://github.com/pgjdbc/pgjdbc")
                            tag.set("HEAD")
                        }
                    }
                }
                // </editor-fold>
            }
        }
    }
}

subprojects {
    if (project.path.startsWith(":postgresql")) {
        plugins.withId("java") {
            configure<JavaPluginExtension> {
                val sourceSets: SourceSetContainer by project
                registerFeature("sspi") {
                    usingSourceSet(sourceSets["main"])
                }
                registerFeature("osgi") {
                    usingSourceSet(sourceSets["main"])
                }
            }
            dependencies {
                "sspiImplementation"("com.github.waffle:waffle-jna")
                // The dependencies are provided by OSGi container,
                // so they should not be exposed as transitive dependencies
                "osgiCompileOnly"("org.osgi:org.osgi.core")
                "osgiCompileOnly"("org.osgi:org.osgi.service.jdbc")
                "testImplementation"("org.osgi:org.osgi.service.jdbc") {
                    because("DataSourceFactory is needed for PGDataSourceFactoryTest")
                }
            }
        }
    }
}
