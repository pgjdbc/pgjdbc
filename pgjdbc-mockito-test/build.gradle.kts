plugins {
    id("build-logic.java-library")
    id("build-logic.test-junit5")
}

// https://github.com/gradle/gradle/pull/16627
private inline fun <reified T : Named> AttributeContainer.attribute(attr: Attribute<T>, value: String) =
    attribute(attr, objects.named<T>(value))

val byteBuddyAgent = configurations.dependencyScope("byteBuddyAgent")
val byteBuddyAgentClasspath = configurations.resolvable("byteBuddyAgentClasspath") {
    extendsFrom(byteBuddyAgent.get())

    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, Category.LIBRARY)
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, LibraryElements.JAR)
        attribute(Usage.USAGE_ATTRIBUTE, Usage.JAVA_RUNTIME)
        attribute(Bundling.BUNDLING_ATTRIBUTE, Bundling.EXTERNAL)
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, buildParameters.targetJavaVersion)
    }
}

dependencies {
    testImplementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }
    byteBuddyAgent("net.bytebuddy:byte-buddy-agent:1.18.2")
    testImplementation(projects.testkit)
    testImplementation(platform("org.mockito:mockito-bom:5.20.0"))
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
}

tasks.test {
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-javaagent:${byteBuddyAgentClasspath.get().incoming.artifactView {
                componentFilter { it is ModuleComponentIdentifier && it.module == "byte-buddy-agent" }
            }.files.singleFile}"
        )
    })
}
