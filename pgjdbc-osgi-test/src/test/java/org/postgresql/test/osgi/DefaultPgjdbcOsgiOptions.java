/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.osgi;

import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

import org.ops4j.pax.exam.options.ModifiableCompositeOption;

/**
 * Bundles every test installs into the pax-exam test container. The container resolves them from the
 * offline Maven repository that build.gradle.kts lays out and configures through the
 * {@code org.ops4j.pax.url.mvn.*} system properties.
 */
public class DefaultPgjdbcOsgiOptions {
  public static ModifiableCompositeOption defaultPgjdbcOsgiOptions() {
    return composite(
        // asm is used by org.apache.aries.spifly
        mavenBundle("org.ow2.asm", "asm").versionAsInProject(),
        mavenBundle("org.ow2.asm", "asm-analysis").versionAsInProject(),
        mavenBundle("org.ow2.asm", "asm-commons").versionAsInProject(),
        mavenBundle("org.ow2.asm", "asm-tree").versionAsInProject(),
        mavenBundle("org.ow2.asm", "asm-util").versionAsInProject(),
        // spifly requires for osgi.extender=osgi.serviceloader.registrar
        mavenBundle("org.apache.aries.spifly", "org.apache.aries.spifly.dynamic.bundle").versionAsInProject(),
        mavenBundle("org.postgresql", "postgresql").versionAsInProject(),
        systemProperty("logback.configurationFile")
            .value(System.getProperty("logback.configurationFile")),
        mavenBundle("org.slf4j", "slf4j-api").versionAsInProject(),
        mavenBundle("ch.qos.logback", "logback-core").versionAsInProject(),
        mavenBundle("ch.qos.logback", "logback-classic").versionAsInProject(),
        junitBundles()
    );
  }
}
