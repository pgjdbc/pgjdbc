/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.core.ParserTest;
import org.postgresql.core.ReturningParserTest;
import org.postgresql.core.v3.V3ParameterListTests;
import org.postgresql.jdbc.DeepBatchedInsertStatementTest;
import org.postgresql.jdbc.PrimitiveArraySupportTest;
import org.postgresql.test.core.JavaVersionTest;
import org.postgresql.test.core.NativeQueryBindLengthTest;
import org.postgresql.test.util.ExpressionPropertiesTest;
import org.postgresql.test.util.LruCacheTest;
import org.postgresql.test.util.ServerVersionParseTest;
import org.postgresql.test.util.ServerVersionTest;
import org.postgresql.util.ReaderInputStreamTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/*
 * Executes all known tests for JDBC2 and includes some utility methods.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        ANTTest.class,
        JavaVersionTest.class,

        DriverTest.class,
        ConnectionTest.class,
        DateStyleTest.class,
        DatabaseMetaDataTest.class,
        DatabaseMetaDataPropertiesTest.class,
        SearchPathLookupTest.class,
        EncodingTest.class,
        ExpressionPropertiesTest.class,
        ColumnSanitiserDisabledTest.class,
        ColumnSanitiserEnabledTest.class,
        LruCacheTest.class,
        ReaderInputStreamTest.class,
        ServerVersionParseTest.class,
        ServerVersionTest.class,

        DriverTest.class,
        ConnectionTest.class,
        DatabaseMetaDataTest.class,
        DatabaseMetaDataPropertiesTest.class,
        SearchPathLookupTest.class,
        EncodingTest.class,
        ExpressionPropertiesTest.class,
        ColumnSanitiserDisabledTest.class,
        ColumnSanitiserEnabledTest.class,
        LruCacheTest.class,
        ReaderInputStreamTest.class,
        ServerVersionParseTest.class,
        ServerVersionTest.class,

        TypeCacheDLLStressTest.class,

        ResultSetTest.class,
        ResultSetMetaDataTest.class,
        StringTypeUnspecifiedArrayTest.class,
        ArrayTest.class,
        PrimitiveArraySupportTest.class,
        RefCursorTest.class,

        DateTest.class,
        TimeTest.class,
        TimestampTest.class,
        TimezoneTest.class,
        PGTimeTest.class,
        PGTimestampTest.class,
        TimezoneCachingTest.class,
        ParserTest.class,
        ReturningParserTest.class,

        PreparedStatementTest.class,
        StatementTest.class,
        QuotationTest.class,

        ServerPreparedStmtTest.class,

        BatchExecuteTest.class,
        BatchFailureTest.class,

        BatchedInsertReWriteEnabledTest.class,
        NativeQueryBindLengthTest.class,
        DeepBatchedInsertStatementTest.class,
        JBuilderTest.class,
        MiscTest.class,
        NotifyTest.class,
        DatabaseEncodingTest.class,
        ClientEncodingTest.class,

        BlobTest.class,
        BlobTransactionTest.class,

        UpdateableResultTest.class,

        CallableStmtTest.class,
        CursorFetchTest.class,
        ConcurrentStatementFetch.class,
        ServerCursorTest.class,

        IntervalTest.class,
        GeometricTest.class,

        LoginTimeoutTest.class,
        TestACL.class,

        ConnectTimeoutTest.class,

        PGPropertyTest.class,

        V3ParameterListTests.class,

        CopyTest.class,
        CopyLargeFileTest.class,
        ServerErrorTest.class,
        UpsertTest.class
})
public class Jdbc2TestSuite {
}
