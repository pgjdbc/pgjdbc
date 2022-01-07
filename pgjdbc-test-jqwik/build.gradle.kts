import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

dependencies {
    testImplementation(project(":postgresql"))
    // It is shaded
    testImplementation("com.ongres.scram:client")
    testImplementation(project(":pgjdbc-testkit"))

    testImplementation("net.jqwik:jqwik-kotlin")
    testImplementation("org.assertj:assertj-core:3.22.0")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        includeEngines("jqwik")
    }
    include("**/*Properties.class")
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        javaParameters = true // Required to get correct parameter names in reporting
        freeCompilerArgs += listOf(
            "-Xjsr305=strict", // Required for strict interpretation of
            "-Xemit-jvm-type-annotations" // Required for annotations on type variables
        )
    }
}
