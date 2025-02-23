import com.github.vlsi.gradle.dsl.configureEach

plugins {
    id("checkstyle")
    id("build-logic.toolchains")
    id("build-logic.build-params")
}

configurations.checkstyle {
    // See https://github.com/gradle/gradle/issues/27035#issuecomment-1814997295
    // TODO: remove the workaround as https://github.com/checkstyle/checkstyle/issues/14123 is resolved
    resolutionStrategy.capabilitiesResolution.withCapability("com.google.collections:google-collections") {
        select("com.google.guava:guava:0")
    }
}

checkstyle {
    toolVersion = "10.21.3"
    providers.gradleProperty("checkstyle.version")
        .takeIf { it.isPresent }
        ?.let { toolVersion = it.get() }

    // Current one is ~8.8
    // https://github.com/julianhyde/toolbox/issues/3
    isShowViolations = true
    // TOOD: move to /config
    val configDir = File(rootDir, "pgjdbc/src/main/checkstyle")
    configDirectory.set(configDir)
    configFile = configDir.resolve("checks.xml")
}

val checkstyleTasks = tasks.withType<Checkstyle>()
checkstyleTasks.configureEach {
    // Checkstyle 8.26 does not need classpath, see https://github.com/gradle/gradle/issues/14227
    classpath = files()
}

tasks.register("checkstyleAll") {
    dependsOn(checkstyleTasks)
}

plugins.withId("java")  {
    tasks.configureEach<Checkstyle> {
        buildParameters.buildJdk?.let {
            javaLauncher.convention(project.the<JavaToolchainService>().launcherFor(it))
        }
    }
}
