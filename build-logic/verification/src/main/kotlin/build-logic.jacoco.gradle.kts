plugins {
    id("java-base")
    id("jacoco")
}

jacoco {
    toolVersion = "0.8.8"
    providers.gradleProperty("jacoco.version")
        .takeIf { it.isPresent }
        ?.let { toolVersion = it.get() }
}

val testTasks = tasks.withType<Test>()
val javaExecTasks = tasks.withType<JavaExec>()

// This configuration must be postponed since JacocoTaskExtension might be added inside
// configure block of a task (== before this code is run)
afterEvaluate {
    for (t in arrayOf(testTasks, javaExecTasks)) {
        t.configureEach {
            extensions.findByType<JacocoTaskExtension>()?.apply {
                // We want collect code coverage for org.postgresql classes only
                includes?.add("org.postgresql.*")
            }
        }
    }
}

val jacocoReport by rootProject.tasks.existing(JacocoReport::class)
val mainCode = sourceSets["main"]

// TODO: rework with provide-consume configurations
jacocoReport {
    // Note: this creates a lazy collection
    // Some projects might fail to create a file (e.g. no tests or no coverage),
    // So we check for file existence. Otherwise, JacocoMerge would fail
    val execFiles =
        files(testTasks, javaExecTasks).filter { it.exists() && it.name.endsWith(".exec") }
    executionData(execFiles)
    additionalSourceDirs.from(mainCode.allJava.srcDirs)
    sourceDirectories.from(mainCode.allSource.srcDirs)
    classDirectories.from(mainCode.output)
}

// TODO: check which reports do we need
//tasks.configureEach<JacocoReport> {
//    reports {
//        html.required.set(reportsForHumans())
//        xml.required.set(!reportsForHumans())
//    }
//}
