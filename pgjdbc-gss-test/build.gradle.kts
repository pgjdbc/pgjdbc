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
    testImplementation(projects.testkit)
}

// The GSS test connects over GSSAPI to the server and the KDC that docker/postgres-server starts
// with GSS=yes and the gss profile.
tasks.test {
    onlyIf("GSS test runs only when -PgssTests is passed") {
        providers.gradleProperty("gssTests").isPresent
    }
}
