import org.openrewrite.gradle.RewriteDryRunTask

plugins {
    id("org.openrewrite.rewrite")
}

dependencies {
    rewrite(platform("org.openrewrite.recipe:rewrite-recipe-bom:latest.integration"))
    rewrite("org.openrewrite.recipe:rewrite-static-analysis")
}

rewrite {
    configFile = project.rootProject.file("config/openrewrite/rewrite.yml")
    // See RewriteDryRunTask workaround below
    failOnDryRunResults = false

    activeStyle("io.github.pgjdbc.style.Style")

    // See config/openrewrite/rewrite.yml
    activeRecipe("io.github.pgjdbc.staticanalysis.CodeCleanup")

    // This is auto-generated code, so there's no need to clean it up
    exclusion("pgjdbc/src/main/java/org/postgresql/translation/messages_*.java")
}

// See https://github.com/openrewrite/rewrite-gradle-plugin/issues/255
tasks.withType<RewriteDryRunTask>().configureEach {
    doFirst {
        if (reportPath.exists()) {
            // RewriteDryRunTask keeps the report file if there are no violations, so we remove it
            reportPath.delete()
        }
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
