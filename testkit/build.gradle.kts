plugins {
    id("build-logic.java-library")
    id("build-logic.without-type-annotations")
}

dependencies {
    api(platform("org.junit:junit-bom:6.1.0"))
    api("org.junit.jupiter:junit-jupiter-api")

    // We want testkit to be compatible with both regular and shadowed variants,
    // so we use compileOnly.
    compileOnly(projects.postgresql)
    implementation("org.checkerframework:checker-qual:3.55.1")
}
