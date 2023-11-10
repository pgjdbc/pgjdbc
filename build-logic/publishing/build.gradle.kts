plugins {
    id("build-logic.kotlin-dsl-gradle-plugin")
}

dependencies {
    implementation(project(":basics"))
    implementation(project(":jvm"))
    implementation(project(":build-parameters"))
    implementation("com.github.vlsi.gradle-extensions:com.github.vlsi.gradle-extensions.gradle.plugin:1.88")
    implementation("com.github.vlsi.stage-vote-release:com.github.vlsi.stage-vote-release.gradle.plugin:1.88")
}
