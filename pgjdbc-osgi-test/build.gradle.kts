import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("build-logic.java-library")
}

val pgjdbcRepository = configurations.create("pgjdbcRepository") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description =
        "Consumes local maven repository directory that contains the artifacts produced by :postgresql"
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named("maven-repository"))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

dependencies {
    pgjdbcRepository(projects.postgresql)

    testImplementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }
    testImplementation(projects.testkit)

    testImplementation("junit:junit:4.13.2")
    testImplementation("javax:javaee-api:8.0.1")
    testImplementation("org.osgi:org.osgi.service.jdbc:1.0.0")
    testImplementation("org.ops4j.pax.exam:pax-exam-container-native:4.14.0")
    // pax-exam is not yet compatible with junit5
    // see https://github.com/ops4j/org.ops4j.pax.exam2/issues/886
    testImplementation("org.ops4j.pax.exam:pax-exam-junit4:4.14.0")
    testImplementation("org.ops4j.pax.exam:pax-exam-link-mvn:4.14.0")
    testImplementation("org.ops4j.pax.url:pax-url-aether:3.0.3")
    testImplementation("org.apache.felix:org.apache.felix.framework:7.0.5")
    testImplementation("ch.qos.logback:logback-core:1.6.3")
    testImplementation("ch.qos.logback:logback-classic:1.6.3")
    testRuntimeOnly(platform("org.ow2.asm:asm-bom:9.10.1"))
    testRuntimeOnly("org.apache.aries.spifly:org.apache.aries.spifly.dynamic.bundle:1.3.7")
}

// <editor-fold defaultstate="collapsed" desc="Pass dependency versions to pax-exam container">
val depDir = layout.buildDirectory.dir("pax-dependencies")

val generateDependenciesProperties = tasks.register<WriteProperties>("generateDependenciesProperties") {
    description = "Generates dependencies.properties so pax-exam can use .versionAsInProject()"
    destinationFile.set(depDir.map { it.file("META-INF/maven/dependencies.properties") })
    property("groupId", project.group)
    property("artifactId", project.name)
    property("version", project.version)
    property("${project.group}/${project.name}/version", "${project.version}")
    dependsOn(configurations.testRuntimeClasspath)
    dependsOn(pgjdbcRepository)
    doFirst {
        configurations.testRuntimeClasspath.get().resolvedConfiguration.resolvedArtifacts.forEach {
            val prefix = "${it.moduleVersion.id.group}/${it.moduleVersion.id.name}"
            property("$prefix/scope", "compile")
            it.extension?.let { property("$prefix/type", it) }
            property("$prefix/version", it.moduleVersion.id.version)
        }
    }
}

sourceSets.test {
    output.dir(mapOf("builtBy" to generateDependenciesProperties), depDir)
}
// </editor-fold>

// pax-exam installs bundles from mvn: URLs, which pax-url-aether resolves with its own Aether resolver. That
// resolver cannot read Gradle's dependency cache, so the bundles are laid out below as a Maven repository
// that the test uses as its local repository in offline mode. Without this every CI job downloaded the same
// jars from Maven Central again, which rate-limits the shared runner addresses with HTTP 429.

// Bundles that DefaultPgjdbcOsgiOptions and the tests install with versionAsInProject(). The versions come
// from dependencies.properties, which is generated from testRuntimeClasspath, so the jars are taken from
// there as well.
val projectBundles = setOf(
    "org.ow2.asm:asm",
    "org.ow2.asm:asm-analysis",
    "org.ow2.asm:asm-commons",
    "org.ow2.asm:asm-tree",
    "org.ow2.asm:asm-util",
    "org.apache.aries.spifly:org.apache.aries.spifly.dynamic.bundle",
    "org.slf4j:slf4j-api",
    "ch.qos.logback:logback-core",
    "ch.qos.logback:logback-classic",
    "org.osgi:org.osgi.service.jdbc"
)

fun Configuration.moduleArtifacts(filter: (ModuleComponentIdentifier) -> Boolean) =
    incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier && filter(it) }
    }.artifacts.resolvedArtifacts

val projectBundleArtifacts = configurations.testRuntimeClasspath.get().moduleArtifacts {
    "${it.group}:${it.module}" in projectBundles
}

// pax-exam boots the container with its own bundles (pax-exam, pax-swissbox, ops4j-base, the junit and
// hamcrest bundles, ...), each named by an mvn: URL in a META-INF/links/*.link file of pax-exam-link-mvn.
// The container installs exactly those versions, whatever testRuntimeClasspath resolves the same modules
// to, so the coordinates are read from the link files rather than repeated here. A few links, such as
// pax-logging, are not installed with the options the tests use; materializing them as well is harmless.
val paxExamLinkBundles: Provider<List<String>> = configurations.testRuntimeClasspath.get().moduleArtifacts {
    it.group == "org.ops4j.pax.exam" && it.module == "pax-exam-link-mvn"
}.map { artifacts ->
    artifacts.flatMap { artifact ->
        ZipFile(artifact.file).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("META-INF/links/") && it.name.endsWith(".link") }
                .map { zip.getInputStream(it).bufferedReader().readText().trim() }
                .toList()
        }
    }.map { url ->
        // mvn:group/artifact/version
        val parts = url.removePrefix("mvn:").split('/')
        check(url.startsWith("mvn:") && parts.size == 3) { "Unsupported bundle URL in pax-exam-link-mvn: $url" }
        parts.joinToString(":")
    }
}

val osgiBundles = configurations.create("osgiBundles") {
    isCanBeConsumed = false
    isCanBeResolved = true
    // The container installs exactly the version named in the link file, so no transitive dependencies
    isTransitive = false
    description = "Bundles pax-exam installs to boot the OSGi container, as named by pax-exam-link-mvn"
    dependencies.addAllLater(paxExamLinkBundles.map { notations -> notations.map { project.dependencies.create(it) } })
}

class MavenArtifact(
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val file: File,
    // The directory the file is copied to, group/with/slashes/artifact/version
    @get:Input
    val directory: String
)

@CacheableTask
abstract class MaterializeMavenRepository : DefaultTask() {
    @get:Inject
    abstract val fs: FileSystemOperations

    @get:Nested
    abstract val artifacts: ListProperty<MavenArtifact>

    // Jars out of a Maven repository directory, copied with their relative paths, except that unique snapshot
    // names such as postgresql-42.7.14-20260922.152546-1.jar become postgresql-42.7.14-SNAPSHOT.jar, which
    // is the name a local repository resolves a snapshot by. Every publish to local-maven-repo gives the
    // snapshot a new timestamp while the jars stay the same, so the input is fingerprinted by content only.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val repositoryJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun materialize() {
        fs.sync {
            into(outputDirectory)
            for (artifact in artifacts.get()) {
                from(artifact.file) {
                    into(artifact.directory)
                }
            }
            from(repositoryJars) {
                rename("(.*)-\\d{8}\\.\\d{6}-\\d+(.*)", "\$1-SNAPSHOT\$2")
            }
        }
    }
}

val paxMavenRepository = layout.buildDirectory.dir("pax-maven-repo")

val materializePaxMavenRepository = tasks.register<MaterializeMavenRepository>("materializePaxMavenRepository") {
    description = "Lays out the bundles the OSGi tests install as a Maven repository, so pax-url resolves them offline"
    val bundleArtifacts = projectBundleArtifacts.zip(osgiBundles.incoming.artifacts.resolvedArtifacts) { a, b -> a + b }
    artifacts.set(bundleArtifacts.map { resolved ->
        resolved.map { artifact ->
            val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
            MavenArtifact(artifact.file, "${id.group.replace('.', '/')}/${id.module}/${id.version}")
        }
    })
    repositoryJars.from(pgjdbcRepository.asFileTree.matching { include("**/*.jar") })
    outputDirectory.set(paxMavenRepository)
}

// Read outside the onlyIf spec below, which would otherwise capture the build script
val testJdkVersion = buildParameters.testJdkVersion

tasks.test {
    dependsOn(generateDependenciesProperties)
    inputs.dir(materializePaxMavenRepository.flatMap { it.outputDirectory })
        .withPropertyName("paxMavenRepository")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    onlyIf("Looks like pax.url does not support Java 8, see https://github.com/ops4j/org.ops4j.pax.url/issues/453") {
        testJdkVersion > 8
    }
    systemProperty("logback.configurationFile", file("src/test/resources/logback-test.xml"))
    // NativeTestContainer runs in this JVM, so pax-url reads these from the system properties directly.
    // Every bundle, the freshly built driver included, comes from the repository laid out above.
    systemProperty("org.ops4j.pax.url.mvn.localRepository", paxMavenRepository.get().asFile.absolutePath)
    // An empty value also prevents pax-url from adding the repositories of ~/.m2/settings.xml
    systemProperty("org.ops4j.pax.url.mvn.repositories", "")
    systemProperty("org.ops4j.pax.url.mvn.useFallbackRepositories", "false")
    systemProperty("org.ops4j.pax.url.mvn.offline", "true")
}
