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
 * Pulls repository URLs from system properties and passes them to pax-exam test container.
 */
public class DefaultPgjdbcOsgiOptions {
  public static ModifiableCompositeOption defaultPgjdbcOsgiOptions() {
    return composite(
        // This declares "remote" repositories where the container would fetch artifacts from.
        // It is pgjdbc built in the current build + central for other dependencies
        systemProperty("org.ops4j.pax.url.mvn.repositories")
            .value(System.getProperty("pgjdbc.org.ops4j.pax.url.mvn.repositories")),
        // This is a repository where osgi container would cache resolved maven artifacts
        systemProperty("org.ops4j.pax.url.mvn.localRepository")
            .value(System.getProperty("pgjdbc.org.ops4j.pax.url.mvn.localRepository")),
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
