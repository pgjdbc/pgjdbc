import com.github.vlsi.gradle.crlf.CrLfSpec
import com.github.vlsi.gradle.crlf.LineEndings
import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.properties.dsl.props
import java.time.LocalDate

plugins {
    id("java")
    id("com.github.vlsi.crlf")
    id("com.github.vlsi.gradle-extensions")
    id("build-logic.repositories")
    id("build-logic.test-base")
    id("build-logic.build-params")
    id("build-logic.style")
    id("com.github.vlsi.jandex")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

sourceSets {
    main {
        resources {
            // TODO: remove when LICENSE is removed (it is used by Maven build for now)
            exclude("META-INF/LICENSE")
        }
    }
}

project.configure<com.github.vlsi.jandex.JandexExtension> {
    skipIndexFileGeneration()
}

if (!buildParameters.enableGradleMetadata) {
    tasks.configureEach<GenerateModuleMetadata> {
        enabled = false
    }
}

if (buildParameters.coverage || gradle.startParameter.taskNames.any { it.contains("jacoco") }) {
    apply(plugin = "build-logic.jacoco")
}

tasks.configureEach<JavaCompile> {
    inputs.property("java.version", System.getProperty("java.version"))
    inputs.property("java.vm.version", System.getProperty("java.vm.version"))
    options.apply {
        encoding = "UTF-8"
        compilerArgs.add("-Xlint:deprecation")
        if (JavaVersion.current().isJava9Compatible) {
            // See https://bugs.openjdk.org/browse/JDK-8032211
            // Don't issue deprecation warnings on import statements is resolved in Java 9+
            //compilerArgs.add("-Werror")
        }
    }
}

tasks.configureEach<Javadoc> {
    (options as StandardJavadocDocletOptions).apply {
        // Please refrain from using non-ASCII chars below since the options are passed as
        // javadoc.options file which is parsed with "default encoding"
        noTimestamp.value = true
        showFromProtected()
        if (buildParameters.failOnJavadocWarning) {
            // See JDK-8200363 (https://bugs.openjdk.java.net/browse/JDK-8200363)
            // for information about the -Xwerror option.
            addBooleanOption("Xwerror", true)
        }
        // There are too many missing javadocs, so failing the build on missing comments seems to be not an option
        addBooleanOption("Xdoclint:all,-missing", true)
        // javadoc: error - The code being documented uses modules but the packages
        // defined in https://docs.oracle.com/javase/9/docs/api/ are in the unnamed module
        source = "1.8"
        docEncoding = "UTF-8"
        charSet = "UTF-8"
        encoding = "UTF-8"
        docTitle = "PostgreSQL JDBC ${project.name} API version ${project.version}"
        windowTitle = "PostgreSQL JDBC ${project.name} API version ${project.version}"
        header = "<b>PostgreSQL JDBC</b>"
        val lastEditYear = providers.gradleProperty("lastEditYear")
            .getOrElse(LocalDate.now().year.toString())
        bottom =
            "Copyright &copy; 1997-$lastEditYear PostgreSQL Global Development Group. All Rights Reserved."
        if (JavaVersion.current() >= JavaVersion.VERSION_17) {
            addBooleanOption("html5", true)
        } else if (JavaVersion.current() >= JavaVersion.VERSION_1_9) {
            addBooleanOption("html5", true)
            links("https://docs.oracle.com/javase/9/docs/api/")
        } else {
            links("https://docs.oracle.com/javase/8/docs/api/")
        }
    }
}

// Add default license/notice when missing (e.g. see :src:config that overrides LICENSE)

afterEvaluate {
    tasks.configureEach<Jar> {
        CrLfSpec(LineEndings.LF).run {
            into("META-INF") {
                filteringCharset = "UTF-8"
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                from("$rootDir/LICENSE")
                from("$rootDir/NOTICE")
            }
        }
    }
}

tasks.configureEach<Jar> {
    manifest {
        attributes["Bundle-License"] = "BSD-2-Clause"
        attributes["Implementation-Title"] = "PostgreSQL JDBC Driver"
        attributes["Implementation-Version"] = project.version
        val jdbcSpec = props.string("jdbc.specification.version")
        if (jdbcSpec.isNotBlank()) {
            attributes["Specification-Vendor"] = "Oracle Corporation"
            attributes["Specification-Version"] = jdbcSpec
            attributes["Specification-Title"] = "JDBC"
        }
        attributes["Implementation-Vendor"] = "PostgreSQL Global Development Group"
        attributes["Implementation-Vendor-Id"] = "org.postgresql"
    }
}
