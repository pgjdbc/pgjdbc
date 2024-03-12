import com.github.vlsi.gradle.dsl.configureEach
import org.gradle.api.publish.internal.PublicationInternal
import com.github.vlsi.gradle.publishing.dsl.simplifyXml
import java.util.Locale

plugins {
    id("java-library")
    id("maven-publish")
    id("build-logic.publish-to-tmp-maven-repo")
    id("com.github.vlsi.gradle-extensions")
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

