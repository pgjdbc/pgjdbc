/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.core.ParserTest;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.v3.V3ParameterListTests;
import org.postgresql.jdbc.DeepBatchedInsertStatementTest;
import org.postgresql.test.CursorFetchBinaryTest;
import org.postgresql.test.TestUtil;
import org.postgresql.test.core.NativeQueryBindLengthTest;
import org.postgresql.test.util.ExpressionPropertiesTest;
import org.postgresql.test.util.LruCacheTest;
import org.postgresql.test.util.ServerVersionParseTest;
import org.postgresql.test.util.ServerVersionTest;
import org.postgresql.util.ReaderInputStreamTest;

import junit.framework.JUnit4TestAdapter;
import junit.framework.TestSuite;

import java.sql.Connection;

/*
 * Executes all known tests for JDBC2 and includes some utility methods.
 */
public class Jdbc2TestSuite extends TestSuite {

  /*
   * The main entry point for JUnit
   */
  public static TestSuite suite() throws Exception {
    TestSuite suite = new TestSuite();

    //
    // Add one line per class in our test cases. These should be in order of
    // complexity.

    // ANTTest should be first as it ensures that test parameters are
    // being sent to the suite.
    //
    suite.addTest(new JUnit4TestAdapter(ANTTest.class));

    // Basic Driver internals
    suite.addTest(new JUnit4TestAdapter(DriverTest.class));
    suite.addTest(new JUnit4TestAdapter(ConnectionTest.class));
    suite.addTest(new JUnit4TestAdapter(DatabaseMetaDataTest.class));
    suite.addTest(new JUnit4TestAdapter(DatabaseMetaDataPropertiesTest.class));
    suite.addTest(new JUnit4TestAdapter(SearchPathLookupTest.class));
    suite.addTest(new JUnit4TestAdapter(EncodingTest.class));
    suite.addTest(new JUnit4TestAdapter(ExpressionPropertiesTest.class));
    suite.addTest(new JUnit4TestAdapter(ColumnSanitiserDisabledTest.class));
    suite.addTest(new JUnit4TestAdapter(ColumnSanitiserEnabledTest.class));
    suite.addTest(new JUnit4TestAdapter(LruCacheTest.class));
    suite.addTest(new JUnit4TestAdapter(ReaderInputStreamTest.class));
    suite.addTest(new JUnit4TestAdapter(ServerVersionParseTest.class));
    suite.addTest(new JUnit4TestAdapter(ServerVersionTest.class));

    // Connectivity/Protocols

    suite.addTest(new JUnit4TestAdapter(TypeCacheDLLStressTest.class));

    // ResultSet
    suite.addTest(new JUnit4TestAdapter(ResultSetTest.class));
    suite.addTest(new JUnit4TestAdapter(ResultSetMetaDataTest.class));
    suite.addTest(new JUnit4TestAdapter(ArrayTest.class));
    suite.addTest(new JUnit4TestAdapter(RefCursorTest.class));

    // Time, Date, Timestamp, PGTime, PGTimestamp
    suite.addTest(new JUnit4TestAdapter(DateTest.class));
    suite.addTest(new JUnit4TestAdapter(TimeTest.class));
    suite.addTest(new JUnit4TestAdapter(TimestampTest.class));
    suite.addTest(new JUnit4TestAdapter(TimezoneTest.class));
    suite.addTest(new JUnit4TestAdapter(PGTimeTest.class));
    suite.addTest(new JUnit4TestAdapter(PGTimestampTest.class));
    suite.addTest(new JUnit4TestAdapter(TimezoneCachingTest.class));
    suite.addTest(new JUnit4TestAdapter(ParserTest.class));

    // PreparedStatement
    suite.addTest(new JUnit4TestAdapter(PreparedStatementTest.class));
    suite.addTest(new JUnit4TestAdapter(StatementTest.class));
    suite.addTest(new JUnit4TestAdapter(QuotationTest.class));

    // ServerSide Prepared Statements
    suite.addTest(new JUnit4TestAdapter(ServerPreparedStmtTest.class));

    // BatchExecute
    suite.addTest(new JUnit4TestAdapter(BatchExecuteTest.class));
    suite.addTest(new JUnit4TestAdapter(BatchFailureTest.class));

    suite.addTest(new JUnit4TestAdapter(BatchedInsertReWriteEnabledTest.class));
    suite.addTest(new JUnit4TestAdapter(NativeQueryBindLengthTest.class));
    suite.addTestSuite(DeepBatchedInsertStatementTest.class);

    // Other misc tests, based on previous problems users have had or specific
    // features some applications require.
    suite.addTest(new JUnit4TestAdapter(JBuilderTest.class));
    suite.addTest(new JUnit4TestAdapter(MiscTest.class));
    suite.addTest(new JUnit4TestAdapter(NotifyTest.class));
    suite.addTest(new JUnit4TestAdapter(DatabaseEncodingTest.class));

    // Fastpath/LargeObject
    suite.addTest(new JUnit4TestAdapter(BlobTest.class));
    suite.addTest(new JUnit4TestAdapter(BlobTransactionTest.class));

    suite.addTest(new JUnit4TestAdapter(UpdateableResultTest.class));

    suite.addTest(new JUnit4TestAdapter(CallableStmtTest.class));
    suite.addTestSuite(CursorFetchTest.class);
    suite.addTestSuite(CursorFetchBinaryTest.class);
    suite.addTest(new JUnit4TestAdapter(ServerCursorTest.class));

    suite.addTest(new JUnit4TestAdapter(IntervalTest.class));
    suite.addTest(new JUnit4TestAdapter(GeometricTest.class));

    suite.addTest(new JUnit4TestAdapter(LoginTimeoutTest.class));
    suite.addTest(new JUnit4TestAdapter(TestACL.class));

    suite.addTest(new JUnit4TestAdapter(ConnectTimeoutTest.class));

    suite.addTest(new JUnit4TestAdapter(PGPropertyTest.class));

    suite.addTestSuite(V3ParameterListTests.class);

    Connection conn = TestUtil.openDB();
    if (TestUtil.isProtocolVersion(conn, 3)) {
      suite.addTest(new JUnit4TestAdapter(CopyTest.class));
      suite.addTest(new JUnit4TestAdapter(CopyLargeFileTest.class));
    }

    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_3)) {
      suite.addTest(new JUnit4TestAdapter(ServerErrorTest.class));
    }

    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_5)) {
      suite.addTestSuite(UpsertTest.class);
      suite.addTestSuite(UpsertBinaryTest.class);
    }

    conn.close();

    // That's all folks
    return suite;
  }
}
