import org.gradle.api.tasks.Sync

// Prepares a variation of the project sources that do not depend on nullability annotations.
// It is currently used in the source distribution, so the library can be compiled in environments
// that do not ship checker-qual for one or another reason.

val withoutAnnotations = layout.buildDirectory.dir("without-annotations").get().asFile

val sourceWithoutCheckerAnnotations = configurations.consumable("sourceWithoutCheckerAnnotations") {
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
        attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named("sources-without-annotations"))
    }
}

val hiddenAnnotation = Regex(
    "@(?:Nullable|NonNull|PolyNull|MonotonicNonNull|RequiresNonNull|EnsuresNonNull|" +
            "Regex|" +
            "Pure|" +
            "KeyFor|" +
            "Positive|NonNegative|IntRange|" +
            "GuardedBy|UnderInitialization|" +
            "Holding|" +
            "DefaultQualifier)(?:\\([^)]*\\))?")
val hiddenImports = Regex("import org.checkerframework")

val removeTypeAnnotations = tasks.register<Sync>("removeTypeAnnotations") {
    destinationDir = withoutAnnotations
    inputs.property("regexpsUpdatedOn", "2020-08-25")
    from(projectDir) {
        filteringCharset = Charsets.UTF_8.name()
        filter { x: String ->
            x.replace(hiddenAnnotation, "/* $0 */")
                .replace(hiddenImports, "// $0")
        }
        include("src/**")
    }
}

sourceWithoutCheckerAnnotations.configure {
    outgoing {
        artifact(withoutAnnotations) {
            builtBy(removeTypeAnnotations)
        }
    }
}
