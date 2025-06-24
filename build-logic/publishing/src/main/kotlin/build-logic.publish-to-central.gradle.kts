import buildparameters.signing.pgp.Enabled
import buildparameters.signing.pgp.Implementation
import com.github.vlsi.gradle.dsl.configureEach
import org.gradle.api.publish.internal.PublicationInternal
import com.github.vlsi.gradle.publishing.dsl.simplifyXml
import org.gradle.kotlin.dsl.support.serviceOf
import java.util.Locale

plugins {
    id("java-library")
    id("maven-publish")
    id("signing")
    id("build-logic.build-params")
    id("build-logic.publish-to-tmp-maven-repo")
    id("com.github.vlsi.gradle-extensions")
    id("com.gradleup.nmcp")
}

if (!buildParameters.release) {
    publishing {
        repositories {
            maven {
                name = "centralSnapshots"
                url = uri("https://central.sonatype.com/repository/maven-snapshots")
                credentials(PasswordCredentials::class)
            }
        }
    }
} else {
    if (buildParameters.signing.pgp.enabled == Enabled.AUTO) {
        signing {
            sign(publishing.publications)
            if (buildParameters.signing.pgp.implementation == Implementation.GPG_CLI) {
                useGpgCmd()
            } else {
                val pgpPrivateKey = System.getenv("SIGNING_PGP_PRIVATE_KEY")
                val pgpPassphrase = System.getenv("SIGNING_PGP_PASSPHRASE")
                val problems = project.serviceOf<Problems>()
                if (pgpPrivateKey.isNullOrBlank() || pgpPassphrase.isNullOrBlank()) {
                    throw problems.reporter.throwing(
                        IllegalArgumentException("PGP private key (SIGNING_PGP_PRIVATE_KEY) and passphrase (SIGNING_PGP_PASSPHRASE) must be set for signing the release artifacts"),
                        ProblemId.create(
                            "gpg_credentials_not_set",
                            "PGP private key (SIGNING_PGP_PRIVATE_KEY) and passphrase (SIGNING_PGP_PASSPHRASE) must be set for signing the release artifacts",
                            ProblemGroup.create("release_params", "Release parameters")
                        )
                    ) {
                        contextualLabel("Using in-memory PGP keys from the environment variables")
                        solution("Ensure SIGNING_PGP_PRIVATE_KEY and SIGNING_PGP_PASSPHRASE environment variables are set or use -Psigning.pgp.implementation=GPG_CLI to sign with gpg command line utility")
                        solution("Disable signing with -Psigning.pgp.enabled=OFF")
                    }
                }
                useInMemoryPgpKeys(
                    pgpPrivateKey,
                    pgpPassphrase
                )
            }
        }
    }
}

publishing {
    publications {
        // <editor-fold defaultstate="collapsed" desc="Override published artifacts (e.g. shaded instead of regular)">
        val extraMavenPublications by configurations.creating {
            isVisible = false
            isCanBeResolved = false
            isCanBeConsumed = false
        }
        afterEvaluate {
            named<MavenPublication>(project.name) {
                extraMavenPublications.outgoing.artifacts.apply {
                    val keys = mapTo(mutableSetOf()) {
                        it.classifier.orEmpty() to it.extension
                    }
                    artifacts.removeIf {
                        keys.contains(it.classifier.orEmpty() to it.extension)
                    }
                    forEach { artifact(it) }
                }
            }
        }
        // </editor-fold>
    }
    publications.configureEach<MavenPublication> {
        // Use the resolved versions in pom.xml
        // Gradle might have different resolution rules, so we set the versions
        // that were used in Gradle build/test.
        versionMapping {
            usage(Usage.JAVA_RUNTIME) {
                fromResolutionResult()
            }
            usage(Usage.JAVA_API) {
                fromResolutionOf("runtimeClasspath")
            }
        }
        pom {
            simplifyXml()
            val capitalizedName = project.name
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            name.set(
                (project.findProperty("artifact.name") as? String) ?: "pgdjbc $capitalizedName"
            )
            description.set(project.description ?: "PostgreSQL JDBC Driver $capitalizedName")
            inceptionYear.set("1997")
            url.set("https://jdbc.postgresql.org")
            licenses {
                license {
                    name.set("BSD-2-Clause")
                    url.set("https://jdbc.postgresql.org/about/license.html")
                    comments.set("BSD-2-Clause, copyright PostgreSQL Global Development Group")
                    distribution.set("repo")
                }
            }
            organization {
                name.set("PostgreSQL Global Development Group")
                url.set("https://jdbc.postgresql.org/")
            }
            developers {
                developer {
                    id.set("davecramer")
                    name.set("Dave Cramer")
                }
                developer {
                    id.set("jurka")
                    name.set("Kris Jurka")
                }
                developer {
                    id.set("oliver")
                    name.set("Oliver Jowett")
                }
                developer {
                    id.set("ringerc")
                    name.set("Craig Ringer")
                }
                developer {
                    id.set("vlsi")
                    name.set("Vladimir Sitnikov")
                }
                developer {
                    id.set("bokken")
                    name.set("Brett Okken")
                }
            }
            issueManagement {
                system.set("GitHub issues")
                url.set("https://github.com/pgjdbc/pgjdbc/issues")
            }
            mailingLists {
                mailingList {
                    name.set("PostgreSQL JDBC development list")
                    subscribe.set("https://lists.postgresql.org/")
                    unsubscribe.set("https://lists.postgresql.org/unsubscribe/")
                    post.set("pgsql-jdbc@postgresql.org")
                    archive.set("https://www.postgresql.org/list/pgsql-jdbc/")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/pgjdbc/pgjdbc.git")
                developerConnection.set("scm:git:https://github.com/pgjdbc/pgjdbc.git")
                url.set("https://github.com/pgjdbc/pgjdbc")
                tag.set("HEAD")
            }
        }
    }
}

val createReleaseBundle by tasks.registering(Sync::class) {
    description = "This task should be used by github actions to create release artifacts along with a slsa attestation"
    val releaseDir = layout.buildDirectory.dir("release")
    outputs.dir(releaseDir)

    into(releaseDir)
    rename("pom-default.xml", "${project.name}-${project.version}.pom")
    rename("module.json", "${project.name}-${project.version}.module")
}

publishing {
    publications.configureEach {
        (this as PublicationInternal<*>).allPublishableArtifacts {
            val publicationArtifact = this
            createReleaseBundle.configure {
                dependsOn(publicationArtifact)
                from(publicationArtifact.file)
            }
        }
    }
}

