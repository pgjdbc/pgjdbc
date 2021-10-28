/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.core.AsciiStringInternerTest;
import org.postgresql.core.CommandCompleteParserNegativeTest;
import org.postgresql.core.CommandCompleteParserTest;
import org.postgresql.core.OidToStringTest;
import org.postgresql.core.OidValueOfTest;
import org.postgresql.core.ParserTest;
import org.postgresql.core.ReturningParserTest;
import org.postgresql.core.UTF8EncodingTest;
import org.postgresql.core.v3.V3ParameterListTests;
import org.postgresql.core.v3.adaptivefetch.AdaptiveFetchCacheTest;
import org.postgresql.jdbc.ArraysTest;
import org.postgresql.jdbc.ArraysTestSuite;
import org.postgresql.jdbc.BitFieldTest;
import org.postgresql.jdbc.DeepBatchedInsertStatementTest;
import org.postgresql.jdbc.NoColumnMetadataIssue1613Test;
import org.postgresql.jdbc.PgSQLXMLTest;
import org.postgresql.test.core.FixedLengthOutputStreamTest;
import org.postgresql.test.core.JavaVersionTest;
import org.postgresql.test.core.LogServerMessagePropertyTest;
import org.postgresql.test.core.NativeQueryBindLengthTest;
import org.postgresql.test.core.OptionsPropertyTest;
import org.postgresql.test.util.ByteBufferByteStreamWriterTest;
import org.postgresql.test.util.ByteStreamWriterTest;
import org.postgresql.test.util.ExpressionPropertiesTest;
import org.postgresql.test.util.HostSpecTest;
import org.postgresql.test.util.LruCacheTest;
import org.postgresql.test.util.PGPropertyMaxResultBufferParserTest;
import org.postgresql.test.util.ServerVersionParseTest;
import org.postgresql.test.util.ServerVersionTest;
import org.postgresql.util.BigDecimalByteConverterTest;
import org.postgresql.util.PGbyteaTest;
import org.postgresql.util.ReaderInputStreamTest;
import org.postgresql.util.UnusualBigDecimalByteConverterTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/*
 * Executes all known tests for JDBC2 and includes some utility methods.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    AdaptiveFetchCacheTest.class,
    ArrayTest.class,
    ArraysTest.class,
    ArraysTestSuite.class,
    AsciiStringInternerTest.class,
    BatchedInsertReWriteEnabledTest.class,
    BatchExecuteTest.class,
    BatchFailureTest.class,
    BigDecimalByteConverterTest.class,
    BitFieldTest.class,
    BlobTest.class,
    BlobTransactionTest.class,
    ByteBufferByteStreamWriterTest.class,
    ByteStreamWriterTest.class,
    CallableStmtTest.class,
    ClientEncodingTest.class,
    ColumnSanitiserDisabledTest.class,
    ColumnSanitiserEnabledTest.class,
    CommandCompleteParserNegativeTest.class,
    CommandCompleteParserTest.class,
    ConcurrentStatementFetch.class,
    ConnectionTest.class,
    ConnectTimeoutTest.class,
    CopyLargeFileTest.class,
    CopyTest.class,
    CursorFetchTest.class,
    DatabaseEncodingTest.class,
    DatabaseMetaDataCacheTest.class,
    DatabaseMetaDataPropertiesTest.class,
    DatabaseMetaDataTest.class,
    DateStyleTest.class,
    DateTest.class,
    DeepBatchedInsertStatementTest.class,
    DriverTest.class,
    EncodingTest.class,
    ExpressionPropertiesTest.class,
    FixedLengthOutputStreamTest.class,
    GeometricTest.class,
    GetXXXTest.class,
    HostSpecTest.class,
    IntervalTest.class,
    JavaVersionTest.class,
    JBuilderTest.class,
    LoginTimeoutTest.class,
    LogServerMessagePropertyTest.class,
    LruCacheTest.class,
    MiscTest.class,
    NativeQueryBindLengthTest.class,
    NoColumnMetadataIssue1613Test.class,
    NumericTransferTest.class,
    NumericTransferTest2.class,
    NotifyTest.class,
    OidToStringTest.class,
    OidValueOfTest.class,
    OptionsPropertyTest.class,
    OuterJoinSyntaxTest.class,
    ParameterStatusTest.class,
    ParserTest.class,
    PGbyteaTest.class,
    PGPropertyMaxResultBufferParserTest.class,
    PGPropertyTest.class,
    PGTimestampTest.class,
    PGTimeTest.class,
    PgSQLXMLTest.class,
    PreparedStatementTest.class,
    QuotationTest.class,
    ReaderInputStreamTest.class,
    RefCursorTest.class,
    ReplaceProcessingTest.class,
    ResultSetMetaDataTest.class,
    ResultSetTest.class,
    ReturningParserTest.class,
    SearchPathLookupTest.class,
    ServerCursorTest.class,
    ServerErrorTest.class,
    ServerPreparedStmtTest.class,
    ServerVersionParseTest.class,
    ServerVersionTest.class,
    StatementTest.class,
    StringTypeUnspecifiedArrayTest.class,
    TestACL.class,
    TimestampTest.class,
    TimeTest.class,
    TimezoneCachingTest.class,
    TimezoneTest.class,
    TypeCacheDLLStressTest.class,
    UnusualBigDecimalByteConverterTest.class,
    UpdateableResultTest.class,
    UpsertTest.class,
    UTF8EncodingTest.class,
    V3ParameterListTests.class
})
public class Jdbc2TestSuite {
}
