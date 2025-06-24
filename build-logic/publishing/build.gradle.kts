plugins {
    id("build-logic.kotlin-dsl-gradle-plugin")
}

dependencies {
    implementation(project(":basics"))
    implementation(project(":jvm"))
    implementation(project(":build-parameters"))
    implementation("com.github.vlsi.gradle-extensions:com.github.vlsi.gradle-extensions.gradle.plugin:2.0.0")
    implementation("com.github.vlsi.stage-vote-release:com.github.vlsi.stage-vote-release.gradle.plugin:2.0.0") {
        exclude("xerces")
    }
}
