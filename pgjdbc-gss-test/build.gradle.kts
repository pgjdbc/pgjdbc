/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

plugins {
    id("build-logic.java-library")
    id("build-logic.test-junit5")
}

dependencies {
    testImplementation(projects.postgresql) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }
}

// The GSS test spawns a local Kerberos KDC and PostgreSQL server, then connects over GSSAPI.
tasks.test {
    val gssWorkDir = layout.buildDirectory.dir("gss-test")
    onlyIf("GSS test runs only when -PgssTests is passed") {
        providers.gradleProperty("gssTests").isPresent
    }
    // The Kerberos config files and credential cache are created under this directory at runtime,
    // so it lives under build/ to keep the source tree clean and to be ignored by git.
    workingDir = gssWorkDir.get().asFile
    doFirst {
        workingDir.mkdirs()
    }
    // Use the OS-native GSSAPI so the server-side Kerberos setup is honoured.
    systemProperty("sun.security.jgss.native", "true")
    systemProperty("javax.security.auth.useSubjectCredsOnly", "false")
    systemProperty(
        "java.security.auth.login.config",
        layout.projectDirectory.file("jaas.conf").asFile.absolutePath
    )
    // The native GSSAPI implementation reads the Kerberos configuration and credential cache from
    // the environment, so the paths must match the ones created by the test under tmp_check.
    environment("KRB5CCNAME", gssWorkDir.get().file("tmp_check/krb5cc").asFile.absolutePath)
    environment("KRB5_CONFIG", gssWorkDir.get().file("tmp_check/krb5.conf").asFile.absolutePath)
    environment("KRB5_KDC_PROFILE", gssWorkDir.get().file("tmp_check/kdc.conf").asFile.absolutePath)
}
