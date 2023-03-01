# Build logic for pgjdbc

This is a subset of extra plugins for factoring out
the common patterns from the common build logic.

The recommended approach is to use build composition, so every build script
should list all its prerequisites in the top-most `plugins { ... }` block.

The use of `allprojects` and `subprojects` is an anti-pattern as it makes it hard to identify
the configuration for a given project.

Let us consider an example (see `/pgjdbc/build.gradle.kts`):

```kotlin
plugins {
    id("build-logic.java-published-library")
    id("build-logic.test-junit5")
}

...
```

It means that we deal with a Java library that will be published to Central,
and which uses JUnit 5 for testing.

If you want to see what the logic does, you could open `build-logic.java-published-library.gradle.kts`
and `buildlogic.test-junit5.gradle.kts`.
