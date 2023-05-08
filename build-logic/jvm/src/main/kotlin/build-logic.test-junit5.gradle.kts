import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.properties.dsl.props

plugins {
    id("java-library")
    id("build-logic.build-params")
    id("build-logic.test-base")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.3")
    testImplementation("uk.org.webcompere:system-stubs-jupiter:2.0.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.3")
    testImplementation("org.hamcrest:hamcrest:2.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.3")
    if ((project.findProperty("junit4") ?: "true").toString().toBoolean()) {
        // Allow projects to opt-out of junit dependency, so they can be JUnit5-only
        testImplementation("junit:junit:4.13.2")
        testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.9.3")
    }
}

tasks.configureEach<Test> {
    useJUnitPlatform {
        props.string("includeTestTags")
            .takeIf { it.isNotBlank() }
            ?.let { includeTags(it) }
    }
    // Pass the property to tests
    fun passProperty(name: String, default: String? = null) {
        val value = System.getProperty(name) ?: default
        value?.let { systemProperty(name, it) }
    }
    passProperty("junit.jupiter.execution.parallel.enabled", "true")
    // TODO: remove when upgrade to JUnit 5.9+
    // See https://github.com/junit-team/junit5/commit/347e3119d36a5c226cddd7981452f11335fad422
    passProperty("junit.jupiter.execution.parallel.config.strategy", "DYNAMIC")
    passProperty("junit.jupiter.execution.timeout.default", "5 m")
}
