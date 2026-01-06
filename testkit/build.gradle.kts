plugins {
    id("build-logic.java-library")
    id("build-logic.without-type-annotations")
}

// Override compiler options to remove -Werror flag to test deprecated PGProperty values
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs = options.compilerArgs.filter { it != "-Werror" }
}

dependencies {
    api(platform("org.junit:junit-bom:5.14.1"))
    api("org.junit.jupiter:junit-jupiter-api")

    // We want testkit to be compatible with both regular and shadowed variants,
    // so we use compileOnly.
    compileOnly(projects.postgresql)
    implementation("org.checkerframework:checker-qual:3.52.0")
}
