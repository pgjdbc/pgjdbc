plugins {
    id("build-logic.java-library")
}

dependencies {
    constraints {
        testImplementation("org.apache.bcel:bcel:6.11.0") {
            because("classloader-leak-test-framework uses bcel, and we want addressing CVEs")
        }
    }

    testImplementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }
    testImplementation(projects.testkit)

    testImplementation("junit:junit:4.13.2")
    testImplementation("se.jiderhamn:classloader-leak-test-framework:1.1.2")
}
