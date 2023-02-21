import org.gradle.kotlin.dsl.apply
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    id("build-logic.build-params")
}

if (!buildParameters.skipAutostyle) {
    apply(plugin = "build-logic.autostyle")
}

val javaIsGoodForCheckstyle = JavaVersion.current().isJava11Compatible

val skipCheckstyle = buildParameters.skipCheckstyle || !javaIsGoodForCheckstyle

if (!skipCheckstyle) {
    apply(plugin = "build-logic.checkstyle")
} else if (!javaIsGoodForCheckstyle) {
    tasks.register("checkstyleAll") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Runs Checkstyle source code verifications. Java 11+ is needed. The current Java is ${JavaVersion.current()}"
        doLast {
            throw IllegalArgumentException("Checkstyle requires Java 11+, the current Java is ${JavaVersion.current()}")
        }
    }
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
            dependsOn("forbiddenApis")
        }
    }
}
