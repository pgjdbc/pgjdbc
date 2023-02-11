plugins {
    id("build-logic.kotlin-dsl-gradle-plugin")
}

dependencies {
    implementation(project(":basics"))
    implementation(project(":build-parameters"))
    implementation("com.github.autostyle:com.github.autostyle.gradle.plugin:3.2")
    implementation("com.github.spotbugs:com.github.spotbugs.gradle.plugin:5.0.13")
    implementation("com.github.vlsi.gradle-extensions:com.github.vlsi.gradle-extensions.gradle.plugin:1.86")
    implementation("de.thetaphi.forbiddenapis:de.thetaphi.forbiddenapis.gradle.plugin:3.4")
    implementation("net.ltgt.errorprone:net.ltgt.errorprone.gradle.plugin:3.0.1")
    implementation("org.checkerframework:org.checkerframework.gradle.plugin:0.6.23")
}
