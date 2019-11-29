/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

import com.github.vlsi.gradle.gettext.GettextTask
import com.github.vlsi.gradle.gettext.MsgAttribTask
import com.github.vlsi.gradle.gettext.MsgFmtTask
import com.github.vlsi.gradle.gettext.MsgMergeTask
import com.github.vlsi.gradle.properties.dsl.props

plugins {
    id("com.github.vlsi.gradle-extensions")
    id("com.github.vlsi.gettext")
}

dependencies {
    implementation("com.github.waffle:waffle-jna")
    implementation("com.ongres.scram:client")
    implementation("org.osgi:org.osgi.core")
    implementation("org.osgi:org.osgi.enterprise")
    testImplementation("se.jiderhamn:classloader-leak-test-framework")
}

val skipReplicationTests by props()
val enableGettext by props()

if (skipReplicationTests) {
    tasks.withType<Test>().configureEach {
        exclude("org/postgresql/replication/**")
        exclude("org/postgresql/test/jdbc2/CopyBothResponseTest*")
    }
}

tasks.withType<Checkstyle>().configureEach {
    exclude("**/messages_*")
}

val update_pot_with_new_messages by tasks.registering(GettextTask::class) {
    sourceFiles.from(sourceSets.main.get().allJava)
    keywords.add("GT.tr")
}

val remove_obsolete_translations by tasks.registering(MsgAttribTask::class) {
    args.add("--no-obsolete") // remove obsolete messages
    // TODO: move *.po to resources?
    poFiles.from(files(sourceSets.main.get().allSource).filter { it.path.endsWith(".po") })
}

val add_new_messages_to_po by tasks.registering(MsgMergeTask::class) {
    poFiles.from(remove_obsolete_translations)
    potFile.set(update_pot_with_new_messages.map { it.outputPot.get() })
}

val generate_java_resources by tasks.registering(MsgFmtTask::class) {
    poFiles.from(add_new_messages_to_po)
    targetBundle.set("org.postgresql.translation.messages")
}

val generateGettextSources by tasks.registering {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Updates .po, .pot, and .java files in src/main/java/org/postgresql/translation"
    dependsOn(add_new_messages_to_po)
    dependsOn(generate_java_resources)
    doLast {
        copy {
            into("src/main/java")
            from(generate_java_resources)
            into("org/postgresql/translation") {
                from(update_pot_with_new_messages)
                from(add_new_messages_to_po)
            }
        }
    }
}

tasks.compileJava {
    if (enableGettext) {
        dependsOn(generateGettextSources)
    } else {
        mustRunAfter(generateGettextSources)
    }
}
