import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    id("com.github.autostyle")
}

autostyle {
    kotlinGradle {
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
    // TODO: the task fails on Windows as follows:
    // Cannot fingerprint input file property 'sourceFiles': Could not stat file D:\a\pgjdbc\pgjdbc\benchmarks\**\*.md
    if (!Os.isFamily(Os.FAMILY_WINDOWS)) {
        format("markdown") {
            target("**/*.md")
            endWithNewline()
        }
    }
}

plugins.withId("java") {
    autostyle {
        java {
            // targetExclude("**/test/java/*.java")
            // TODO: implement license check (with copyright year)
            // licenseHeaderFile(licenseHeaderFile)
            importOrder(
                "static ",
                "org.postgresql.",
                "",
                "java.",
                "javax."
            )
            removeUnusedImports()
            trimTrailingWhitespace()
            indentWithSpaces(4)
            endWithNewline()
        }
    }
}
