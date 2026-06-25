plugins {
    id("java-base")
    id("build-logic.build-params")
    id("jacoco")
}

jacoco {
    toolVersion = "0.8.15"
    providers.gradleProperty("jacoco.version")
        .takeIf { it.isPresent }
        ?.let { toolVersion = it.get() }
}

val testTasks = tasks.withType<Test>()
val javaExecTasks = tasks.withType<JavaExec>()

val jacocoReport by rootProject.tasks.existing(JacocoReport::class)
val mainCode = sourceSets["main"]

// Relative paths (e.g. org/postgresql/Foo.java) of the Java sources in a source set.
fun SourceSet.relativeJavaPaths(): Set<String> =
    allJava.srcDirs.flatMap { root ->
        if (root.exists()) {
            root.walk()
                .filter { it.isFile && it.extension == "java" }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        } else {
            emptyList()
        }
    }.toSet()

// This configuration must be postponed since JacocoTaskExtension might be added inside
// configure block of a task (== before this code is run), and the multi-release source sets
// (java11, java17, ...) are created in the project build script, after this convention is applied.
afterEvaluate {
    for (t in arrayOf(testTasks, javaExecTasks)) {
        t.configureEach {
            extensions.findByType<JacocoTaskExtension>()?.apply {
                // We want collect code coverage for org.postgresql classes only
                includes?.add("org.postgresql.*")
            }
        }
    }

    // Multi-release source sets named "javaNN" compile into META-INF/versions/NN, and the JVM loads
    // them at runtime on JDK >= NN (the test classpath is set up the same way). Include the versions
    // the tests actually run (testJdkVersion >= NN) so coverage is attributed to the class file that
    // ran. Without this, JaCoCo reports "execution data does not match" for an overridden class (for
    // example the Java 17 Unix domain socket factory) and drops its coverage, and a class that lives
    // only in a versioned source set (for example the Unix domain socket adapter) is missing from
    // the report entirely.
    val multiReleaseSourceSets = sourceSets
        .matching { it.name.matches(Regex("java\\d+")) }
        .filter { buildParameters.testJdkVersion >= it.name.removePrefix("java").toInt() }
        .sortedBy { it.name.removePrefix("java").toInt() }

    // A versioned source set overrides a base class when it ships a source with the same path. Drop
    // those classes (and their inner classes) from the base output so only the versioned copy is
    // analysed: JaCoCo refuses two class files with the same name.
    val baseJavaPaths = mainCode.relativeJavaPaths()
    val overriddenClassPatterns = multiReleaseSourceSets
        .flatMap { it.relativeJavaPaths() }
        .filter { it in baseJavaPaths }
        .flatMap { javaPath ->
            val base = javaPath.removeSuffix(".java")
            listOf("$base.class", "$base\$*.class")
        }

    jacocoReport {
        // Note: this creates a lazy collection
        // Some projects might fail to create a file (e.g. no tests or no coverage),
        // So we check for file existence. Otherwise, JacocoMerge would fail
        val execFiles =
            files(testTasks, javaExecTasks).filter { it.exists() && it.name.endsWith(".exec") }
        executionData(execFiles)

        additionalSourceDirs.from(mainCode.allJava.srcDirs)
        sourceDirectories.from(mainCode.allSource.srcDirs)
        classDirectories.from(
            mainCode.output.classesDirs.asFileTree.matching { exclude(overriddenClassPatterns) }
        )

        for (versioned in multiReleaseSourceSets) {
            additionalSourceDirs.from(versioned.allJava.srcDirs)
            sourceDirectories.from(versioned.allSource.srcDirs)
            classDirectories.from(versioned.output.classesDirs)
        }
    }
}
