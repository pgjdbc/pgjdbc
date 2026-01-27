import org.openrewrite.gradle.RewriteDryRunTask
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

plugins {
    id("org.openrewrite.rewrite")
}

dependencies {
    rewrite(platform("org.openrewrite.recipe:rewrite-recipe-bom:latest.integration"))
    rewrite("org.openrewrite.recipe:rewrite-static-analysis")
    rewrite("org.openrewrite.recipe:rewrite-testing-frameworks")
}

rewrite {
    configFile = project.rootProject.file("config/openrewrite/rewrite.yml")
    // See RewriteDryRunTask workaround below
    failOnDryRunResults = false

    activeStyle("io.github.pgjdbc.style.Style")

    // See config/openrewrite/rewrite.yml
    activeRecipe("io.github.pgjdbc.staticanalysis.CodeCleanup")
    // See https://github.com/openrewrite/rewrite-static-analysis/blob/8c803a9c50b480841a4af031f60bac5ee443eb4e/src/main/resources/META-INF/rewrite/common-static-analysis.yml#L21
    activeRecipe("io.github.pgjdbc.staticanalysis.CommonStaticAnalysis")
    plugins.withId("build-logic.test-junit5") {
        // See https://github.com/openrewrite/rewrite-testing-frameworks/blob/47ccd370247f1171fa9df005da8a9a3342d19f3f/src/main/resources/META-INF/rewrite/junit5.yml#L18C7-L18C62
        activeRecipe("org.openrewrite.java.testing.junit5.JUnit5BestPractices")
        // See https://github.com/openrewrite/rewrite-testing-frameworks/blob/47ccd370247f1171fa9df005da8a9a3342d19f3f/src/main/resources/META-INF/rewrite/junit5.yml#L255C7-L255C60
        activeRecipe("org.openrewrite.java.testing.junit5.CleanupAssertions")
    }

    // This is auto-generated code, so there's no need to clean it up
    exclusion("pgjdbc/src/main/java/org/postgresql/translation/messages_*.java")
}

// See https://github.com/openrewrite/rewrite-gradle-plugin/issues/255
tasks.withType<RewriteDryRunTask>().configureEach {
    doFirst {
        reportPath.deleteIfExists()
    }
    doLast {
        if (reportPath.exists()) {
            throw GradleException(
                "The following files have format violations. " +
                        "Execute ./gradlew ${path.replace("Dry", "")} to apply the changes:\n" +
                        reportPath.readText()
            )
        }
    }
}
