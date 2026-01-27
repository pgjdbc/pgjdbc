# pgjdbc-junit4-test

This module contains JUnit 4 tests that cannot be migrated to JUnit 5 due to dependency constraints.
Specifically, the classloaderleak test library is only available for JUnit 4, and there is no JUnit 5 compatible
version.

The main pgjdbc codebase uses JUnit 5 for testing, but these specific tests need to remain on JUnit 4 to maintain the
required test coverage for classloader leak scenarios.
