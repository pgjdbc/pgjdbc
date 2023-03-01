plugins {
    id("build-logic.kotlin-dsl-gradle-plugin")
}

dependencies {
    implementation(project(":basics"))
    implementation(project(":jvm"))
    implementation(project(":build-parameters"))
    implementation("com.gradle.plugin-publish:com.gradle.plugin-publish.gradle.plugin:1.1.0")
    implementation("com.github.vlsi.gradle-extensions:com.github.vlsi.gradle-extensions.gradle.plugin:1.86")
    implementation("com.github.vlsi.stage-vote-release:com.github.vlsi.stage-vote-release.gradle.plugin:1.86")
}
