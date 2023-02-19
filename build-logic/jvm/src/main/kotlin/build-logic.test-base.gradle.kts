import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.properties.dsl.props
import org.gradle.api.tasks.testing.Test

plugins {
    id("build-logic.build-params")
}

tasks.configureEach<Test> {
    inputs.file("../build.properties")
    if (file("../build.local.properties").exists()) {
        inputs.file("../build.local.properties")
    }
    inputs.file("../ssltest.properties")
    if (file("../ssltest.local.properties").exists()) {
        inputs.file("../ssltest.local.properties")
    }
    testLogging {
        showStandardStreams = true
    }
    exclude("**/*Suite*")
    jvmArgs("-Xmx1536m")
    jvmArgs("-Djdk.net.URLClassPath.disableClassPathURLCheck=true")
    props.string("testExtraJvmArgs").trim().takeIf { it.isNotBlank() }?.let {
        jvmArgs(it.split(" ::: "))
    }
    // Pass the property to tests
    fun passProperty(name: String, default: String? = null) {
        val value = System.getProperty(name) ?: default
        value?.let { systemProperty(name, it) }
    }
    passProperty("preferQueryMode")
    passProperty("java.awt.headless")
    passProperty("user.language", "TR")
    passProperty("user.country", "tr")
    val props = System.getProperties()
    @Suppress("UNCHECKED_CAST")
    for (e in props.propertyNames() as `java.util`.Enumeration<String>) {
        if (e.startsWith("pgjdbc.") || e.startsWith("java")) {
            passProperty(e)
        }
    }
    for (p in listOf("server", "port", "database", "username", "password",
        "privilegedUser", "privilegedPassword",
        "simpleProtocolOnly", "enable_ssl_tests")) {
        passProperty(p)
    }
}
