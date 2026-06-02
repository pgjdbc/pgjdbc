import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.properties.dsl.props

plugins {
    id("com.github.vlsi.gradle-extensions")
}

tasks.configureEach<buildlogic.JavaCommentPreprocessorTask> {
    variables.apply {
        val jdbcSpec = props.string("jdbc.specification.version")
        put("mvn.project.property.postgresql.jdbc.spec", "JDBC$jdbcSpec")
        put("jdbc.specification.version", jdbcSpec)
    }

    val re = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*")

    val version = project.version.toString()
    val matchResult = re.find(version) ?: throw GradleException("Unable to parse major.minor.patch version parts from project.version '$version'")
    val (major, minor, patch) = matchResult.destructured

    variables.apply {
        put("version", version)
        put("version.major", major)
        put("version.minor", minor)
        put("version.patch", patch.ifBlank { "0" })
    }
}
