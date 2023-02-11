plugins {
    id("build-logic.kotlin-dsl-gradle-plugin")
}

dependencies {
    implementation(project(":build-parameters"))
    implementation(project(":verification"))
    implementation("com.github.vlsi.crlf:com.github.vlsi.crlf.gradle.plugin:1.86")
    implementation("com.github.vlsi.gradle-extensions:com.github.vlsi.gradle-extensions.gradle.plugin:1.86")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin")
    implementation("com.igormaznitsa:jcp:7.0.5")
    implementation("org.jetbrains.dokka:org.jetbrains.dokka.gradle.plugin:$embeddedKotlinVersion")
    implementation("com.github.autostyle:com.github.autostyle.gradle.plugin:3.2")
    implementation("net.ltgt.errorprone:net.ltgt.errorprone.gradle.plugin:3.0.1")
    implementation("com.github.vlsi.jandex:com.github.vlsi.jandex.gradle.plugin:1.86")
    implementation("de.thetaphi.forbiddenapis:de.thetaphi.forbiddenapis.gradle.plugin:3.3")
}
