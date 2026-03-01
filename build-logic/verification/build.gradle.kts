plugins {
    id("build-logic.kotlin-dsl-gradle-plugin")
}

dependencies {
    implementation(project(":basics"))
    implementation(project(":build-parameters"))
    implementation("com.github.autostyle:com.github.autostyle.gradle.plugin:4.0")
    implementation("com.github.vlsi.gradle-extensions:com.github.vlsi.gradle-extensions.gradle.plugin:2.0.0")
    implementation("de.thetaphi.forbiddenapis:de.thetaphi.forbiddenapis.gradle.plugin:3.10")
    implementation("net.ltgt.errorprone:net.ltgt.errorprone.gradle.plugin:4.3.0")
    implementation("org.checkerframework:org.checkerframework.gradle.plugin:0.6.61")
    implementation("org.openrewrite.rewrite:org.openrewrite.rewrite.gradle.plugin:7.27.0")
}
