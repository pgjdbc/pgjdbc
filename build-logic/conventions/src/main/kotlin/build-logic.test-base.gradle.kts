plugins {
    id("java-library")
    id("build-logic.build-params")
}

tasks.withType<Test>().configureEach {
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
    if (buildParameters.testJdkVersion >= 21) {
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }
    val testExtraJvmArgs = (project.findProperty("testExtraJvmArgs") as? String) ?: ""
    testExtraJvmArgs.trim().takeIf { it.isNotBlank() }?.let {
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
    val sysProps = System.getProperties()
    @Suppress("UNCHECKED_CAST")
    for (e in sysProps.propertyNames() as `java.util`.Enumeration<String>) {
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
