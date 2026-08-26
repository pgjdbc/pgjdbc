# AGENTS.md

After modifying Java code, before reporting the change as done (handing control back to the user), run `./gradlew --quiet classes style` and fix any reported issues.

If the change touched nullability annotations (`@Nullable`, `@MonotonicNonNull`, `@RequiresNonNull`, `Nullness.castNonNull`, generic null-arg parameters, etc.),
add `-PenableCheckerframework` to verify null-safety. The Checker pass adds ~2 min to the build, so don't enable it for changes that don't affect nullability.

## Build

The build is Gradle, driven through `./gradlew`. The driver sources live in `pgjdbc/`, but that subproject is named `:postgresql`, after the published Maven artifact. Address it by that name when compiling or testing the driver:

    ./gradlew --quiet :postgresql:compileJava

Every other subproject (`testkit`, `benchmarks`, `pgjdbc-osgi-test`, ...) is named after its directory.

Java source level is 1.8.

When editing Java code under `**/src/main/java/`, apply the `java-nullability-use` skill.

## Tests

When writing or editing a pgjdbc test that opens a connection or creates PostgreSQL schema (tables, types, functions, views), apply the `pgjdbc-testkit-use` skill.

Integration tests require a running PostgreSQL instance. Start one with: `docker/postgres-server/docker-compose.yml`
