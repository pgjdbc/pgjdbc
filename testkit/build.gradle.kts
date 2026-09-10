plugins {
    id("build-logic.java-library")
    id("build-logic.without-type-annotations")
}

dependencies {
    api(platform("org.junit:junit-bom:5.14.4"))
    api("org.junit.jupiter:junit-jupiter-api")
    api("com.tngtech.archunit:archunit:1.5.0")

    // We want testkit to be compatible with both regular and shadowed variants,
    // so we use compileOnly.
    compileOnly(projects.postgresql)
    implementation("org.checkerframework:checker-qual:3.55.1")
}
