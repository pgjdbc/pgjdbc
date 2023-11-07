plugins {
    id("org.openrewrite.rewrite")
}

dependencies {
    rewrite(platform("org.openrewrite.recipe:rewrite-recipe-bom:latest.integration"))
}

rewrite {
    // This is auto-generated code, so there's no need to clean it up
    exclusion("pgjdbc/src/main/java/org/postgresql/translation/messages_*.java")
}
