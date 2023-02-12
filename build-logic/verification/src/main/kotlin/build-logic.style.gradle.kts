import org.gradle.kotlin.dsl.apply
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    id("build-logic.build-params")
}

if (!buildParameters.skipAutostyle) {
    apply(plugin = "build-logic.autostyle")
}

val skipCheckstyle = buildParameters.skipCheckstyle || run {
    logger.info("Checkstyle requires Java 11+")
    !JavaVersion.current().isJava11Compatible
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
    if (buildParameters.enableErrorprone) {
        apply(plugin = "build-logic.errorprone")
    }
    if (buildParameters.spotbugs) {
        apply(plugin = "build-logic.spotbugs")
    }
}

if (!buildParameters.skipAutostyle || !skipCheckstyle || !buildParameters.skipForbiddenApis) {
    tasks.register("style") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Formats code (license header, import order, whitespace at end of line, ...) and executes Checkstyle verifications"
        if (!buildParameters.skipAutostyle) {
            dependsOn("autostyleApply")
        }
        if (!skipCheckstyle) {
            dependsOn("checkstyleAll")
        }
        if (!buildParameters.skipForbiddenApis) {
            dependsOn("forbiddenApisAll")
        }
    }
}
