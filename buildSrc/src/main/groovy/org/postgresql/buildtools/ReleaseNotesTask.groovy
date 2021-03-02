/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.buildtools

import groovy.io.FileType
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import javax.inject.Inject

class ReleaseNotesTask implements Plugin <Project> {

    @Internal
    ObjectFactory objects

    @Internal
    repositoryDirectory = objects.fileProperty()

    @Inject
    ReleaseNotesTask(ObjectFactory objects) {
        this.objects = objects
    }

    @TaskAction
    releaseNotes() {
        try {
            def docsDirectory = new File('../pgjdbc.git/docs')

            // get current version
            def gradleProperties = new Properties()
            gradleProperties.load(new FileInputStream("${repositoryDirectory.getAbsolutePath()}/gradle.properties"))
            def currentVersion = gradleProperties?.get('pgjdbc.version')
            println "Current Version: ${currentVersion}"


            if (checkCurrentVersionExists(repositoryDirectory, currentVersion)) {
                println "Changelog already updated"
                return
            }


            Date today = Calendar.getInstance().time
            def dateString = "${new java.text.SimpleDateFormat('yyyy-MM-dd').format(today)}"
            def releaseFileName = "$dateString-$currentVersion-release.md"

            // does the release file already exist ?
            if (docsDirectory.eachFile(FileType.FILES) { it == releaseFileName }) {
                println "Release file exists"
                return
            }

            // get the previous version
            def  describeCommands = ["git", "describe", "--match", "REL*", "--abbrev=0"]
            def previousVersion

            def gitProcess = describeCommands.execute(null,  repositoryDirectory)
            gitProcess.waitFor()

            if (gitProcess.exitValue() == 0 ) {
                previousVersion = gitProcess.in.newReader().readLine()
            }


            // create the new post
            def releaseNote = """---
    title:  PostgreSQL JDBC Driver ${currentVersion} Released
    date:   ${new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss Z').format(today)}
    categories:
    - new_release
    version: ${currentVersion}
    ---
    **Notable changes**

    ${notableChanges(repositoryDirectory, previousVersion)}
    <!--more-->

    **Commits by author**

    ${getContributors(repositoryDirectory, previousVersion)}
    """
            println releaseNote
            // write release notes
            File releaseFile = new File("${repositoryDirectory.getAbsolutePath()}/docs/_posts/$releaseFileName")
            releaseFile << releaseNote

            // update the changelog
            updateChangeLog(repositoryDirectory, currentVersion)

        return
    } catch( Exception e ) {
        print e
    }
}

def updateChangeLog( dir, currentVersion ) {
    File tempFile = File.createTempFile("CHANGELOG", "MD")
    File changeLog= new File("${dir.getAbsolutePath()}/CHANGELOG.md")

    changeLog.eachLine {
        if ( it.contains("[Unreleased]") ) {
            tempFile << """## [Unreleased]
### Changed

### Added

### Fixed

"""
            tempFile << "[$currentVersion]\n"
        } else {
            tempFile << it + '\n'
        }
    }
    tempFile.renameTo(changeLog.getAbsolutePath())
}
def checkCurrentVersionExists (dir, currentVersion ) {
    FileInputStream fis = new FileInputStream("${dir.getAbsolutePath()}/CHANGELOG.md")
    fis.eachLine {
        if ( it.contains("[${currentVersion.substring(3)}]") ) {
            return true
        }
    }
    return false
}

def notableChanges( dir, previousVersion ){
    def currentChanges =""
    FileInputStream fis = new FileInputStream("${dir.getAbsolutePath()}/CHANGELOG.md")
    int state = 0

    fis.eachLine {

        if (state == 0 && it.contains('[Unreleased]')) {
            state = 1
        }
        else if (state == 1 ) {

            if (!it.contains("[${previousVersion.substring(3)}]")) {
                currentChanges += it +'\n'
            } else {
                state = 2
            }
        }
    }
    return currentChanges
}

/**
 * We are going to change the contributors map here.
 * @param contributors
 * @param sha
 * @return
 */
def getContributorForSha(Map contributors, sha){

    def url = new URL("https://api.github.com/repos/pgjdbc/pgjdbc/commits/$sha")
    def jsonSlurper = new JsonSlurper()
    def commit = jsonSlurper.parse(url)
    def author = commit.commit.author
    if (!contributors.containsKey(author.name)) {
        if ( author.html_url) {
            contributors.put(author.name, author.html_url)
        } else {
            contributors.put(author.name, author.email)
        }
    }
}

def getContributors(File repoPath, previousRelease) {
    def authorCommits = ""
    def jsonSlurper = new JsonSlurper()
    File contributorFile = new File("$repoPath.path/contributors.json")
    def contributors = jsonSlurper.parse(contributorFile)

    String[] commands = ["git", "shortlog", "--format=%s@@@%H@@@%h@@@",
                         "--grep=maven-release-plugin|update versions in readme.md",
                         "--extended-regexp", "--invert-grep", "--no-merges", "$previousRelease..HEAD"]

    def gitProcess = commands.execute(null, repoPath)
    gitProcess.waitFor()

    if (gitProcess.exitValue() != 0) {
        gitProcess.err.eachLine {
            println it
        }
    } else {
        gitProcess.in.eachLine {
            if (it.contains('@@@')) {
                String[] components = it.split('@@@')
                String subject = components[0]
                String sha = components[1]
                String shortSha = components[2]
                getContributorForSha(contributors, shortSha)
                def pattern = /\(?#(\d+)\)?/
                if (subject =~ pattern) {
                    subject = subject.replaceFirst(pattern, "[PR ${1}](https://github.com/pgjdbc/pgjdbc/pull/${1})")
                    //$matcher.group(1)"
                } else {
                    subject = ""
                }

                authorCommits += subject + '\n'
            } else {
                authorCommits += it + '\n'
            }

        }
    }

    //TODO: This doesn't write the braces before and after
    contributorFile.write(new JsonBuilder(contributors).toPrettyString())
    return authorCommits
    }

    @Override
    void apply(Project project) {
        project.register("releaseNotes") {
            doFirst {
                releaseNotes()
            }
        }

    }
}


