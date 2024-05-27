plugins {
    id("build-logic.kotlin-dsl-gradle-plugin")
}

dependencies {
    implementation(project(":basics"))
    implementation(project(":build-parameters"))
    implementation("com.github.autostyle:com.github.autostyle.gradle.plugin:4.0")
    implementation("com.github.spotbugs:com.github.spotbugs.gradle.plugin:6.0.15")
    implementation("com.github.vlsi.gradle-extensions:com.github.vlsi.gradle-extensions.gradle.plugin:1.90")
    implementation("de.thetaphi.forbiddenapis:de.thetaphi.forbiddenapis.gradle.plugin:3.7")
    implementation("net.ltgt.errorprone:net.ltgt.errorprone.gradle.plugin:3.1.0")
    implementation("org.checkerframework:org.checkerframework.gradle.plugin:0.6.37")
    implementation("org.openrewrite.rewrite:org.openrewrite.rewrite.gradle.plugin:6.13.0")
}
