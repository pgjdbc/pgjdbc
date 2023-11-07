plugins {
    id("org.openrewrite.rewrite")
}

dependencies {
    rewrite(platform("org.openrewrite.recipe:rewrite-recipe-bom:latest.integration"))
}
