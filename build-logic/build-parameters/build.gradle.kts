plugins {
    id("org.gradlex.build-parameters") version "1.4.4"
    id("com.github.vlsi.gradle-extensions") version "1.90"
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
    integer("targetJavaVersion") {
        defaultValue.set(9)
        mandatory.set(true)
        description.set("Java version for source and target compatibility")
    }
    val projectName = "pgjdbc"
    integer("jdkBuildVersion") {
        defaultValue.set(17)
        mandatory.set(true)
        description.set("JDK version to use for building $projectName. If the value is 0, then the current Java is used. (see https://docs.gradle.org/8.4/userguide/toolchains.html#sec:consuming)")
    }
    string("jdkBuildVendor") {
        description.set("JDK vendor to use building $projectName (see https://docs.gradle.org/8.4/userguide/toolchains.html#sec:vendors)")
    }
    string("jdkBuildImplementation") {
        description.set("Vendor-specific virtual machine implementation to use building $projectName (see https://docs.gradle.org/8.4/userguide/toolchains.html#selecting_toolchains_by_virtual_machine_implementation)")
    }
    integer("jdkTestVersion") {
        description.set("JDK version to use for testing $projectName. If the value is 0, then the current Java is used. (see https://docs.gradle.org/current/userguide/toolchains.html#sec:vendors)")
    }
    string("jdkTestVendor") {
        description.set("JDK vendor to use testing $projectName (see https://docs.gradle.org/8.4/userguide/toolchains.html#sec:vendors)")
    }
    string("jdkTestImplementation") {
        description.set("Vendor-specific virtual machine implementation to use testing $projectName (see https://docs.gradle.org/8.4/userguide/toolchains.html#selecting_toolchains_by_virtual_machine_implementation)")
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
    bool("skipOpenrewrite") {
        // For now, we skip OpenRewrite since the -SNAPSHOT version is not very stable
        defaultValue.set(true)
        description.set("Skip OpenRewrite processing")
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
    bool("failOnJavacWarning") {
        defaultValue.set(true)
        description.set("Fail build on javac warnings")
    }
    bool("enableGradleMetadata") {
        defaultValue.set(false)
        description.set("Generate and publish Gradle Module Metadata")
    }
    // Note: it does not work in tr_TR locale due to https://github.com/gradlex-org/build-parameters/issues/87
    string("includeTestTags") {
        defaultValue.set("")
        description.set("Lists tags to include in test execution. For instance -PincludeTestTags=!org.postgresql.test.SlowTests, or or -PincludeTestTags=!replication")
    }
    bool("useGpgCmd") {
        defaultValue.set(false)
        description.set("By default use Java implementation to sign artifacts. When useGpgCmd=true, then gpg command line tool is used for signing artifacts")
    }
}
