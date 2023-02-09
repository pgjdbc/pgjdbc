plugins {
    id("build-logic.build-params")
}

repositories {
    if (buildParameters.enableMavenLocal) {
        mavenLocal()
    }
    mavenCentral()
}
