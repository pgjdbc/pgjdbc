plugins {
    id("build-logic.kotlin-dsl-gradle-plugin")
}

dependencies {
    implementation(project(":build-parameters"))
    implementation(project(":verification"))
    implementation("com.github.vlsi.crlf:com.github.vlsi.crlf.gradle.plugin:1.88")
    implementation("com.github.vlsi.gradle-extensions:com.github.vlsi.gradle-extensions.gradle.plugin:1.88")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin")
    implementation("com.github.autostyle:com.github.autostyle.gradle.plugin:3.2")
    implementation("com.github.vlsi.jandex:com.github.vlsi.jandex.gradle.plugin:1.88")
}
