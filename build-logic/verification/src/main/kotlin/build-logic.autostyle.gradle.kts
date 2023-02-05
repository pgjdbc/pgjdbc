plugins {
    id("com.github.autostyle")
}

autostyle {
    kotlinGradle {
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("markdown") {
        target("**/*.md")
        endWithNewline()
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
