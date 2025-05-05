import com.github.vlsi.gradle.dsl.configureEach
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

plugins {
    id("java")
    id("build-logic.repositories")
}

if (!project.hasProperty("skipErrorprone")) {
    apply(plugin = "net.ltgt.errorprone")

    dependencies {
        "errorprone"("com.google.errorprone:error_prone_core:2.38.0")
        "annotationProcessor"("com.google.guava:guava-beta-checker:1.0")
    }

    tasks.configureEach<JavaCompile> {
        if ("Test" in name) {
            // Ignore warnings in test code
            options.errorprone.isEnabled.set(false)
        } else {
            options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000", "-Xmaxwarns", "10000"))
            options.errorprone {
                disableWarningsInGeneratedCode.set(true)
                errorproneArgs.add("-XepExcludedPaths:.*/translation/messages_.*.java")
                error(
                    "PackageLocation",
                    "UnusedVariable",
                )
                enable(
                    "MethodCanBeStatic",
                )
                disable(
                    "EqualsGetClass",
                    "InlineMeSuggester",
                    "MissingSummary",
                    "OperatorPrecedence",
                    "StringConcatToTextBlock",
                    "StringSplitter",
                    "UnnecessaryParentheses",
                )
            }
        }
    }
}
