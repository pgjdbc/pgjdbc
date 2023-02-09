import com.github.vlsi.gradle.dsl.configureEach
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis

plugins {
    id("de.thetaphi.forbiddenapis")
}

forbiddenApis {
    failOnUnsupportedJava = false
    signaturesFiles = files("$rootDir/config/forbidden-apis/forbidden-apis.txt")
    bundledSignatures.addAll(
        listOf(
            // "jdk-deprecated",
            "jdk-internal",
            "jdk-non-portable"
            // "jdk-system-out"
            // "jdk-unsafe"
        )
    )
}

tasks.configureEach<CheckForbiddenApis> {
    exclude("**/org/postgresql/util/internal/Unsafe.class")
}
