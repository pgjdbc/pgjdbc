plugins {
    id("java-base")
    id("org.jetbrains.dokka")
}

java {
    // Workaround https://github.com/gradle/gradle/issues/21933, so it adds javadocElements configuration
    withJavadocJar()
}

val dokkaJar by tasks.registering(Jar::class) {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Assembles a jar archive containing javadoc"
    from(tasks.dokkaJavadoc)
    archiveClassifier.set("javadoc")
}

configurations[JavaPlugin.JAVADOC_ELEMENTS_CONFIGURATION_NAME].outgoing.artifact(dokkaJar)
