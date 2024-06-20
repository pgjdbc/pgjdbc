import com.github.vlsi.gradle.dsl.configureEach

plugins {
    id("build-logic.java-library")
    `jvm-test-suite`
}

tasks.configureEach<JavaCompile> {
    options.release = 11
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter("5.10.2")
            dependencies {
                implementation(platform("org.junit:junit-bom:5.10.2"))
                implementation(project(":postgresql"))
            }
        }
    }
}
