tasks.withType<buildlogic.JavaCommentPreprocessorTask>().configureEach {
    variables.apply {
        val jdbcSpec = (project.findProperty("jdbc.specification.version") as? String) ?: ""
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
