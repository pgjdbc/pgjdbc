/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.buildtools

import groovy.io.FileType
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;


import javax.inject.Inject

class ReleaseNotesTask extends DefaultTask {

    @Internal
    final File repositoryDirectory
    final String githubUser
    final String githubToken

    @Inject
    public ReleaseNotesTask()  {
        repositoryDirectory = getProject().projectDir
        "git config github.user".execute().with {
            githubUser = it.inputStream.newReader().readLine()
        }
        "git config github.token".execute().with {
            githubToken = it.inputStream.newReader().readLine()
        }
    }

    @TaskAction
    def releaseNotes() {
        try {

            def docsDirectory = new File("${repositoryDirectory.getAbsolutePath()}/docs")

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
            def releaseNote =
"""---
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
            updateChangeLog(repositoryDirectory, currentVersion, previousVersion.substring(3))

            return
        } catch( Exception e ) {
            print e
        }
    }

    def updateChangeLog( dir, currentVersion, previousVersion ) {
        Date today = Calendar.getInstance().time

        File tempFile = File.createTempFile("CHANGELOG", "MD")
        File changeLog= new File("${dir.getAbsolutePath()}/CHANGELOG.md")

        changeLog.eachLine {
            if ( it.endsWith("[Unreleased]") ) {
                tempFile << """## [Unreleased]
### Changed

### Added

### Fixed

"""
                tempFile << "[$currentVersion] (${new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss Z').format(today)})\n"
            } else if ( it.contains("$previousVersion...HEAD") ) {
                tempFile << "[$previousVersion]: https://github.com/pgjdbc/pgjdbc/compare/REL$previousVersion...REL$currentVersion\n"
                tempFile << "[Unreleased]: https://github.com/pgjdbc/pgjdbc/compare/REL$currentVersion...HEAD\n"
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
        def commit

        final BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(
                new AuthScope("api.github,com", 80),
                new UsernamePasswordCredentials(githubUser, githubToken));
        final CloseableHttpClient httpclient = HttpClients.custom()
                .setDefaultCredentialsProvider(credsProvider)
                .build()
        final HttpGet httpget = new HttpGet("https://api.github.com/repos/pgjdbc/pgjdbc/commits/$sha");

        final CloseableHttpResponse response = httpclient.execute(httpget)
        println "Status Code: ${response.getStatusLine().statusCode}"

        if ( response.getStatusLine().statusCode == 200 ) {
            commit = new JsonSlurper().parse(new StringReader(EntityUtils.toString(response.getEntity())))

            def author = commit.commit.author
            if (!contributors.containsKey(author.name)) {
                if (author.html_url) {
                    contributors.put(author.name, author.html_url)
                } else {
                    contributors.put(author.name, author.email)
                }
            }
        } else {
            println("Error getting commit information for ${httpget.getRequestLine()}")
        }
    }

    def getContributors(File repoPath, previousRelease) {
        def authorCommits = ""
        def jsonSlurper = new JsonSlurper()
        File contributorFile = new File("$repoPath.path/contributors.json")
        def contributors = jsonSlurper.parse(contributorFile, "UTF-8")

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
                    def m = subject =~ pattern
                    if (m){
                        subject = subject.replaceFirst(pattern, "[PR ${m[0][1]}](https://github.com/pgjdbc/pgjdbc/pull/${m[0][1]})")
                        authorCommits += subject + '\n'
                        //$matcher.group(1)"
                    }

                } else {
                    authorCommits += it + '\n'
                }

            }
        }

        int numContributors = contributors.size()
        contributorFile.write("{\n", "UTF-8")
        contributors.eachWithIndex { key, value, index ->
            // don't write out the comma for the last line
            contributorFile.append("   \"$key\" : \"${value?value:"N/A"}\"${(index+1) < numContributors?',':''}\n", "UTF-8")
        }
        contributorFile.append("}\n", "UTF-8")
        return authorCommits
    }

}

