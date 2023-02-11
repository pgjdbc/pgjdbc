plugins {
    id("org.gradlex.build-parameters") version "1.4.2"
    id("com.github.vlsi.gradle-extensions") version "1.86"
    id("build-logic.kotlin-dsl-gradle-plugin")
}

buildParameters {
    // Other plugins can contribute parameters, so below list is not exhaustive
    enableValidation.set(false)
    pluginId("build-logic.build-params")
    bool("enableMavenLocal") {
        defaultValue.set(true)
        description.set("Add mavenLocal() to repositories")
    }
    bool("coverage") {
        defaultValue.set(false)
        description.set("Collect test coverage")
    }
    bool("spotbugs") {
        defaultValue.set(false)
        description.set("Run SpotBugs verifications")
    }
    bool("enableCheckerframework") {
        defaultValue.set(false)
        description.set("Run CheckerFramework (nullness) verifications")
    }
    bool("enableErrorprone") {
        defaultValue.set(false)
        description.set("Run ErrorProne verifications")
    }
    bool("skipCheckstyle") {
        defaultValue.set(false)
        description.set("Skip Checkstyle verifications")
    }
    bool("skipAutostyle") {
        defaultValue.set(false)
        description.set("Skip AutoStyle verifications")
    }
    bool("skipForbiddenApis") {
        defaultValue.set(false)
        description.set("Skip forbidden-apis verifications")
    }
    bool("skipJavadoc") {
        defaultValue.set(false)
        description.set("Skip javadoc generation")
    }
    bool("failOnJavadocWarning") {
        defaultValue.set(true)
        description.set("Fail build on javadoc warnings")
    }
    bool("enableGradleMetadata") {
        defaultValue.set(false)
        description.set("Generate and publish Gradle Module Metadata")
    }
    // Note: it does not work in tr_TR locale due to https://github.com/gradlex-org/build-parameters/issues/87
    string("includeTestTags") {
        defaultValue.set("")
        description.set("Lists tags to include in test execution. For instance -PincludeTestTags=!org.postgresql.test.SlowTests, or or -PincludeTestTags=!org.postgresql.test.Replication")
    }
    bool("useGpgCmd") {
        defaultValue.set(false)
        description.set("By default use Java implementation to sign artifacts. When useGpgCmd=true, then gpg command line tool is used for signing artifacts")
    }
}
