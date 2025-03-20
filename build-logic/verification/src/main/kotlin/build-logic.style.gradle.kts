import com.github.autostyle.gradle.AutostyleTask
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import org.gradle.kotlin.dsl.apply
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    id("build-logic.build-params")
}

if (!buildParameters.skipAutostyle) {
    apply(plugin = "build-logic.autostyle")
    if (!buildParameters.skipCheckstyle) {
        tasks.withType<Checkstyle>().configureEach {
            mustRunAfter(tasks.withType<AutostyleTask>())
        }
    }
}

val skipCheckstyle = buildParameters.skipCheckstyle || run {
    logger.info("Checkstyle requires Java 11+")
    buildParameters.buildJdkVersion < 11
}

// OpenRewrite Gradle plugin applies to allprojects when it is applied to the root project
// So the workaround is to avoid applying openrewrite to the root
val skipOpenrewrite = project == rootProject || buildParameters.skipOpenrewrite

if (!skipOpenrewrite) {
    apply(plugin = "build-logic.openrewrite")
}

if (!skipCheckstyle) {
    apply(plugin = "build-logic.checkstyle")
}

if (!buildParameters.skipForbiddenApis) {
    apply(plugin = "build-logic.forbidden-apis")
}

plugins.withId("java-base") {
    if (buildParameters.enableCheckerframework) {
        apply(plugin = "build-logic.checkerframework")
    }
    // Enable errorprone when executing ./gradlew style, ./gradlew :style, etc
    val styleCheckRequested = gradle.startParameter.taskNames.any {
        it == "style" || it == "styleCheck" || it == ":style" || it == ":styleCheck"
    }
    if (buildParameters.enableErrorprone || styleCheckRequested) {
        apply(plugin = "build-logic.errorprone")
    }
    if (buildParameters.spotbugs) {
        apply(plugin = "build-logic.spotbugs")
    }
}

if (!buildParameters.skipAutostyle || !skipCheckstyle || !buildParameters.skipForbiddenApis || !skipOpenrewrite) {
    tasks.register("style") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Formats code (license header, import order, whitespace at end of line, ...) and executes Checkstyle verifications"
        if (!buildParameters.skipAutostyle) {
            dependsOn("autostyleApply")
        }
        if (!skipOpenrewrite) {
            dependsOn("rewriteRun")
        }
        if (!skipCheckstyle) {
            dependsOn("checkstyleAll")
        }
        if (!buildParameters.skipForbiddenApis) {
            dependsOn("forbiddenApis")
        }
    }
    tasks.register("styleCheck") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Report code style violations (license header, import order, whitespace at end of line, ...)"
        if (!buildParameters.skipAutostyle) {
            dependsOn("autostyleCheck")
        }
        if (!skipOpenrewrite) {
            dependsOn("rewriteDryRun")
        }
        if (!skipCheckstyle) {
            dependsOn("checkstyleAll")
        }
        if (!buildParameters.skipForbiddenApis) {
            dependsOn("forbiddenApis")
        }
    }
}

// OpenRewrite fixes many warnings, so it should run the first
if (!skipOpenrewrite) {
    if (!buildParameters.skipForbiddenApis) {
        tasks.withType<CheckForbiddenApis>().configureEach {
            mustRunAfter("rewriteRun", "rewriteDryRun")
        }
    }
    if (!buildParameters.skipCheckstyle) {
        tasks.withType<Checkstyle>().configureEach {
            mustRunAfter("rewriteRun", "rewriteDryRun")
        }
    }
    if (!buildParameters.skipAutostyle) {
        tasks.withType<AutostyleTask>().configureEach {
            mustRunAfter("rewriteRun", "rewriteDryRun")
        }
    }
}

if (!buildParameters.skipAutostyle) {
    tasks.withType<Checkstyle>().configureEach {
        mustRunAfter(tasks.withType<AutostyleTask>())
    }
}
