import org.openrewrite.gradle.RewriteDryRunTask
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

plugins {
    id("org.openrewrite.rewrite")
}

dependencies {
    rewrite(platform("org.openrewrite.recipe:rewrite-recipe-bom:latest.integration"))
    rewrite("org.openrewrite.recipe:rewrite-static-analysis")
    rewrite("org.openrewrite.recipe:rewrite-testing-frameworks")
}

rewrite {
    configFile = project.rootProject.file("config/openrewrite/rewrite.yml")
    // See RewriteDryRunTask workaround below
    failOnDryRunResults = false

    activeStyle("io.github.pgjdbc.style.Style")

    // See config/openrewrite/rewrite.yml
    activeRecipe("io.github.pgjdbc.staticanalysis.CodeCleanup")
    // See https://github.com/openrewrite/rewrite-static-analysis/blob/8c803a9c50b480841a4af031f60bac5ee443eb4e/src/main/resources/META-INF/rewrite/common-static-analysis.yml#L21
    activeRecipe("io.github.pgjdbc.staticanalysis.CommonStaticAnalysis")
    plugins.withId("build-logic.test-junit5") {
        // See https://github.com/openrewrite/rewrite-testing-frameworks/blob/47ccd370247f1171fa9df005da8a9a3342d19f3f/src/main/resources/META-INF/rewrite/junit5.yml#L18C7-L18C62
        activeRecipe("org.openrewrite.java.testing.junit5.JUnit5BestPractices")
        // See https://github.com/openrewrite/rewrite-testing-frameworks/blob/47ccd370247f1171fa9df005da8a9a3342d19f3f/src/main/resources/META-INF/rewrite/junit5.yml#L255C7-L255C60
        activeRecipe("org.openrewrite.java.testing.junit5.CleanupAssertions")
    }

    // This is auto-generated code, so there's no need to clean it up
    exclusion("pgjdbc/src/main/java/org/postgresql/translation/messages_*.java")

    // It is unclear how to opt-out from rewriting certain files, so we just exclude them
    // https://github.com/openrewrite/rewrite-testing-frameworks/issues/422
    // JUnit4 does not support JUnit5's assumeTrue, so we keep TestUtil with JUnit4 for now
    exclusion("pgjdbc/src/testFixtures/java/org/postgresql/test/TestUtil.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/BaseTest4.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/core/OidValuesCorrectnessTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/jdbc/BitFieldTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/jdbc/DeepBatchedInsertStatementTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/jdbc/NoColumnMetadataIssue1613Test.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/jdbc/PgSQLXMLTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/core/NativeQueryBindLengthTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/core/QueryExecutorTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/extensions/HStoreTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/ArrayTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/AutoRollbackTestSuite.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/BatchExecuteTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/BatchFailureTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/BatchedInsertReWriteEnabledTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/CallableStmtTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/ClientEncodingTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/ConcurrentStatementFetch.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/CursorFetchTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/CustomTypeWithBinaryTransferTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/DateStyleTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/DateTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/EnumTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/GeometricTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/NumericTransferTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/NumericTransferTest2.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/OuterJoinSyntaxTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/PGObjectGetTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/PGObjectSetTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/PGTimeTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/ParameterStatusTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/PreparedStatementTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/QuotationTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/RefCursorFetchTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/RefCursorTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/ReplaceProcessingTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/ResultSetMetaDataTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/ResultSetRefreshTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/ResultSetTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/ServerCursorTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/ServerErrorTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/ServerPreparedStmtTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/StringTypeUnspecifiedArrayTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/TimestampTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/TimezoneCachingTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/TypeCacheDLLStressTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/UpdateableResultTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/UpsertTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/EscapeSyntaxCallModeBaseTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/EscapeSyntaxCallModeCallIfNoReturnTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/EscapeSyntaxCallModeCallTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/EscapeSyntaxCallModeSelectTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/GeneratedKeysTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/Jdbc3CallableStatementTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/ParameterMetaDataTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/ProcedureTransactionTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/SendRecvBufferSizeTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/StringTypeParameterTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/TestReturning.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc3/TypesTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc4/ArrayTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc4/BinaryStreamTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc4/BinaryTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc4/CharacterStreamTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc4/ClientInfoTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc4/IsValidTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc4/JsonbTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc4/UUIDTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc4/XmlTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc4/jdbc41/AbortTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc42/GetObject310InfinityTests.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc42/GetObject310Test.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc42/Jdbc42CallableStatementTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc42/LargeCountJdbc42Test.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc42/PreparedStatement64KBindsTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc42/PreparedStatementTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc42/SetObject310InfinityTests.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc42/SetObject310Test.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc42/SimpleJdbc42Test.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/util/ByteStreamWriterTest.java")

    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/optional/BaseDataSourceTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/optional/ConnectionPoolTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/optional/PoolingDataSourceTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/optional/SimpleDataSourceTest.java")
    exclusion("pgjdbc/src/test/java/org/postgresql/test/jdbc2/optional/SimpleDataSourceWithSetURLTest.java")
}

// See https://github.com/openrewrite/rewrite-gradle-plugin/issues/255
tasks.withType<RewriteDryRunTask>().configureEach {
    doFirst {
        reportPath.deleteIfExists()
    }
    doLast {
        if (reportPath.exists()) {
            throw GradleException(
                "The following files have format violations. " +
                        "Execute ./gradlew ${path.replace("Dry", "")} to apply the changes:\n" +
                        reportPath.readText()
            )
        }
    }
}
