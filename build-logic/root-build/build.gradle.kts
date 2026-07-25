plugins {
    id("build-logic.kotlin-dsl-gradle-plugin")
}

dependencies {
    implementation(project(":build-parameters"))
    implementation("com.gradleup.nmcp.aggregation:com.gradleup.nmcp.aggregation.gradle.plugin:1.5.0")
    // Autostyle is applied here (root project only) to cover docs/ and .github/,
    // which the per-subproject Java/Kotlin formats never reach.
    implementation("com.github.autostyle:com.github.autostyle.gradle.plugin:4.0")
}
