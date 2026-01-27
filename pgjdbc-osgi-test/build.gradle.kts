plugins {
    id("build-logic.java-library")
}

val pgjdbcRepository by configurations.creating {
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
    testImplementation("org.ops4j.pax.url:pax-url-aether:3.0.1")
    testImplementation("org.apache.felix:org.apache.felix.framework:7.0.5")
    testImplementation("ch.qos.logback:logback-core:1.5.21")
    testImplementation("ch.qos.logback:logback-classic:1.5.21")
    testRuntimeOnly(platform("org.ow2.asm:asm-bom:9.8"))
    testRuntimeOnly("org.apache.aries.spifly:org.apache.aries.spifly.dynamic.bundle:1.3.7")
}

// <editor-fold defaultstate="collapsed" desc="Pass dependency versions to pax-exam container">
val depDir = layout.buildDirectory.dir("pax-dependencies")

val generateDependenciesProperties by tasks.registering(WriteProperties::class) {
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

// This repository is used instead of ~/.m2/... to avoid clash with /.m2/ contents
val paxLocalCacheRepository = layout.buildDirectory.dir("pax-repo")

val cleanCachedPgjdbc by tasks.registering(Delete::class) {
    description =
        "Removes cached postgresql.jar from pax-repo folder so pax-exam always resolves the recent one"
    delete(paxLocalCacheRepository.map { it.dir("org/postgresql") })
}

tasks.test {
    dependsOn(generateDependenciesProperties)
    dependsOn(cleanCachedPgjdbc)
    dependsOn(pgjdbcRepository)
    onlyIf("Looks like pax.url does not support Java 8, see https://github.com/ops4j/org.ops4j.pax.url/issues/453") {
        buildParameters.testJdkVersion > 8
    }
    systemProperty("logback.configurationFile", file("src/test/resources/logback-test.xml"))
    // Regular systemProperty can't be used here as we need lazy evaluation of pgjdbcRepository
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dpgjdbc.org.ops4j.pax.url.mvn.repositories=" +
                    "file:${pgjdbcRepository.singleFile.absolutePath}@snapshots@id=pgjdbc-current" +
                    ",${project.findProperty("osgi.test.mavencentral.url")}@id=central",
            "-Dpgjdbc.org.ops4j.pax.url.mvn.localRepository=file:${paxLocalCacheRepository.get().asFile.absolutePath}@id=pax-repo"
        )
    })
}
