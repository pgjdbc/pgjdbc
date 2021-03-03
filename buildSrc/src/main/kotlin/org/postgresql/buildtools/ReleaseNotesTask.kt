/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.buildtools

import java.text.SimpleDateFormat
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.StringBuilder
import java.net.URL
import java.util.*
import javax.inject.Inject

typealias JsonResult = Any

@Suppress("UNCHECKED_CAST")
operator fun JsonResult.get(name: String) = (this as Map<String, JsonResult?>)[name]

@Suppress("UNCHECKED_CAST")
fun JsonResult.attr(name: String) = (this as Map<String, Any?>)[name]

@Suppress("UNCHECKED_CAST")
fun JsonResult.stringAttr(name: String) = (this as Map<String, Any?>)[name]?.toString()

abstract class ReleaseNotesTask @Inject constructor(
    objects: ObjectFactory,
    layout: ProjectLayout,
    private val execOperations: ExecOperations
) : DefaultTask() {
    init {
        // Skip up-to-date checks
        outputs.upToDateWhen { false }
    }

    @Internal
    val docsDirectory = objects.directoryProperty()
        .convention(layout.projectDirectory.dir("docs"))

    // Note: if declare the file as @Output, then gradlew clean would remove the file
    @Internal
    val changeLogFile = objects.fileProperty()
        .convention(layout.projectDirectory.file("CHANGELOG.md"))

    @OutputFile
    val tempChangeLogFile = objects.fileProperty()
        .convention(layout.buildDirectory.file("$name/CHANGELOG.md"))

    // Note: if declare the file as @Output, then gradlew clean would remove the file
    @Internal
    val contributorsFile = objects.fileProperty()
        .convention(layout.projectDirectory.file("contributors.json"))

    @OutputFile
    val tempContributorsFile = objects.fileProperty()
        .convention(layout.buildDirectory.file("$name/contributors.json"))

    @Internal
    val currentVersion = objects.property<String>()
        .convention(project.version.toString())

    @Internal
    val dryRun = objects.property<Boolean>().convention(true)

    fun git(execSpec: ExecSpec.() -> Unit): String = ByteArrayOutputStream().use { os ->
        execOperations.exec {
            executable = "git"
            standardOutput = os
            execSpec()
        }.rethrowFailure().assertNormalExitValue()
        os
    }.toString()

    @TaskAction
    fun releaseNotes() {
        val currentVersion = currentVersion.get()
        println("Current Version: $currentVersion")

        if (checkCurrentVersionExists(changeLogFile.get().asFile, currentVersion)) {
            println("Changelog already updated")
            return
        }

        val today = Calendar.getInstance().time
        val dateString = SimpleDateFormat("yyyy-MM-dd").format(today)
        val releaseFileName = "$dateString-$currentVersion-release.md"
        val releaseFile = docsDirectory.get().asFile.resolve("_posts/$releaseFileName")
        if (releaseFile.isFile) {
            println("Release file $releaseFile exists, updating is not implemented yet")
            didWork = false
            return
        }

        val previousVersion = git {
            args("describe", "--match", "REL*", "--abbrev=0")
        }.trim()

        // create the new post
        val releaseNote = """
            ---
            title:  PostgreSQL JDBC Driver $currentVersion Released
            date:   ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(today)}
            categories:
            - new_release
            version: $currentVersion
            ---
            **Notable changes**

            ${notableChanges(previousVersion)}
            <!--more-->

            **Commits by author**

            ${getContributors(previousVersion)}
        """.trimIndent()
        println(releaseNote)
        // write release notes
        releaseFile.writeText(releaseNote)
        // update the changelog
        updateChangeLog(currentVersion)
    }

    private fun checkCurrentVersionExists(changeLogFile: File, currentVersion: String) =
        changeLogFile.useLines { lines ->
            lines.any { it.contains("[${currentVersion.substring(3)}]") }
        }

    private fun updateChangeLog(currentVersion: String) {
        val changeLog = changeLogFile.get().asFile

        val output = StringBuilder()
        changeLog.forEachLine {
            if (it.contains("[Unreleased]")) {
                output.append(
                    """
                    ## [Unreleased]
                    ### Changed

                    ### Added

                    ### Fixed

                    """.trimIndent()
                )
                output.append("[$currentVersion]\n")
            } else {
                output.appendln(it)
            }
        }

        tempChangeLogFile.get().asFile.run {
            writeText(output.toString())
            if (dryRun.get()) {
                logger.info("Won't update $changeLog since dryRun=true, please check $this")
            } else {
                logger.lifecycle("Updating $changeLog")
                copyTo(changeLog)
            }
        }
    }

    fun notableChanges(previousVersion: String) = changeLogFile.get().asFile.useLines { lines ->
        lines.dropWhile { !it.contains("[Unreleased]") }
            .drop(1) // drop Unreleased
            .takeWhile { !it.contains("[${previousVersion.substring(3)}]") }
            .joinToString("\n")
    }

    fun getContributorForSha(
        contributors: MutableMap<String, String>,
        sha: String
    ) {
        val url = URL("https://api.github.com/repos/pgjdbc/pgjdbc/commits/$sha")
        val jsonSlurper = JsonSlurper()
        val commit = jsonSlurper.parse(url) as JsonResult
        val author = commit.get("commit")?.get("author")!!
        contributors.computeIfAbsent(author.stringAttr("name")!!) { name ->
            author.stringAttr("html_url") ?: author.stringAttr("email")!!.also {
                logger.info("Resolved contributor $name to $it, sha $sha")
            }
        }
    }

    fun getContributors(previousRelease: String): String {
        val contributorFile = contributorsFile.get().asFile

        @Suppress("UNCHECKED_CAST")
        val contributors = JsonSlurper().parse(contributorFile) as MutableMap<String, String>

        val gitLog = git {
            args(
                "shortlog", "--format=%s@@@%H@@@%h@@@%aN@@@",
                "--grep=maven-release-plugin|update versions in readme.md",
                "--extended-regexp", "--invert-grep", "--no-merges", "$previousRelease..HEAD"
            )
        }

        val prRegex = Regex("\\(?#(\\d+)\\)?")
        val authorCommits = gitLog.lineSequence().map {
            if (!it.contains("@@@")) {
                it
            } else {
                val (subject, sha, shortSha, authorName) = it.split("@@@")
                if (authorName !in contributors) {
                    getContributorForSha(contributors, sha)
                }
                subject.replaceFirst(
                    prRegex,
                    "[PR $1](https://github.com/pgjdbc/pgjdbc/pull/$1)"
                )
            }
        }.joinToString("\n")

        // TODO: This doesn't write the braces before and after
        tempContributorsFile.get().asFile.run {
            writeText(JsonBuilder(contributors).toPrettyString())
            if (dryRun.get()) {
                logger.info("Won't update $contributorFile since dryRun=true, please check $this")
            } else {
                logger.lifecycle("Updating $contributorFile")
                copyTo(contributorFile)
            }
        }
        return authorCommits
    }
}
