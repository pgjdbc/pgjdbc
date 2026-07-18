# pgjdbc-compat-test

A differential backward-compatibility oracle. It drives the public JDBC read and write surface through the current driver and through a released baseline (`42.7.13`), loaded side by side in the same JVM, and fails on any observable difference that is not recorded in `KnownDifferences`. When behaviour changes on purpose, you record the change with a justification; anything else is a compatibility regression the build catches before release.

## Why this is a separate module

The oracle runs both driver versions at once, so it cannot live as a test under `pgjdbc/src/test`. Three constraints force a module of its own:

- **Both drivers own the `org.postgresql.*` package.** To hold the current driver and the baseline in one JVM, the baseline jar is kept off every compile and runtime classpath and loaded through an isolated `URLClassLoader` whose parent is the platform loader (see `LegacyDriverLoader`). `java.sql.*` then resolves from the JDK and is shared, while each driver's `org.postgresql.*` resolves only from its own jar. On the ordinary test classpath, where `org.postgresql.*` is the module under test, a second copy would clash.
- **It tests the shipped, shaded driver.** The current driver is pulled in as its bundled (`SHADOWED`) artifact — the shape users actually get. A test inside `pgjdbc` runs against the raw project classes, and depending on the project's own shaded output would be a build cycle.
- **The core is reusable.** The probe machinery (`DifferentialProbe`, `Accessor`, `Binder`, `OutcomeComparator`, `KnownDifferences`) lives in `src/main` and stays at the Java 8 target so other modules — `pgjdbc-jqf-test`, for example — can depend on it.

## How it works

`BackwardCompatMatrixTest` opens four connections — current and baseline, each in text and binary transfer mode — and walks a matrix of types and accessors. For each cell it reads or writes a value through both drivers, compares the observable outcomes (returned value or thrown `SQLException`, grouped by SQLState), and collects any difference. `KnownDifferences` is the allow-list: a difference listed there with a reason is expected; an unlisted one fails the test.

The baseline never connects through `DriverManager`. Both versions register for `jdbc:postgresql:` on class init, so the test calls `Driver.connect` on the explicit baseline instance to avoid a non-deterministic pick.

## Running

The oracle needs a database (the same connection settings as the rest of the suite) and the baseline jar path in the `pgjdbc.compat.legacyJar` system property, which the build supplies from the version catalog. It aborts — rather than fails — when either is missing, so it stays inert where it cannot run.

```sh
./gradlew :pgjdbc-compat-test:test
```

To point the oracle at a different baseline — a locally built merge-base jar, say — override the resolved artifact:

```sh
./gradlew :pgjdbc-compat-test:test -Dpgjdbc.compat.legacyJar=/path/to/postgresql-<version>.jar
```

## When the test fails

A failure lists each disagreeing cell as `label -> difference`. For each one, decide whether the change is intended:

- **Intended** (for example, matching PostgreSQL over a legacy driver quirk): add the cell to `KnownDifferences` with a justification.
- **Unintended:** it is a backward-compatibility regression — fix the current driver.

To move the baseline forward after a release, bump `pgjdbc-compat-baseline` in `gradle/libs.versions.toml`.
