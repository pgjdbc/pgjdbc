plugins {
    id("build-logic.build-params")
}

repositories {
    if (buildParameters.enableMavenLocal) {
        mavenLocal()
    }
    mavenCentral {
        mavenContent {
            releasesOnly()
        }
    }
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
        mavenContent {
            snapshotsOnly()
        }
    }
}
