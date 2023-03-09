import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.kotlin.dsl.withType
import java.io.File

plugins {
    id("checkstyle")
}

checkstyle {
    toolVersion = "10.8.1"
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
