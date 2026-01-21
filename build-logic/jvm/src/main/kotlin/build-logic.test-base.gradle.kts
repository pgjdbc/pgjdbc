import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.properties.dsl.props
import org.gradle.api.tasks.testing.Test

plugins {
    id("java-library")
    id("build-logic.build-params")
}

tasks.configureEach<Test> {
    buildParameters.testJdk?.let {
        javaLauncher.convention(javaToolchains.launcherFor(it))
    }
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
    passProperty("java.util.logging.config.file", "/Users/vlsi/Documents/code/pgjdbc/logging.properties")
    val props = System.getProperties()
    @Suppress("UNCHECKED_CAST")
    for (e in props.propertyNames() as `java.util`.Enumeration<String>) {
        if (e.startsWith("pgjdbc.")) {
            passProperty(e)
        }
    }
    for (p in listOf("test.url.PGHOST", "test.url.PGPORT", "test.url.PGDBNAME", "user", "password",
        "privilegedUser", "privilegedPassword",
        "simpleProtocolOnly", "enable_ssl_tests",
        "autosave", "cleanupSavepoints")) {
        passProperty(p)
    }
}
