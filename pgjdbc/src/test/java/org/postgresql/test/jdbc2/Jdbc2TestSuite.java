/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import org.postgresql.core.ParserTest;
import org.postgresql.core.v3.V3ParameterListTests;
import org.postgresql.jdbc.DeepBatchedInsertStatementTest;
import org.postgresql.test.CursorFetchBinaryTest;
import org.postgresql.test.TestUtil;
import org.postgresql.test.core.NativeQueryBindLengthTest;
import org.postgresql.test.util.ServerVersionParseTest;
import org.postgresql.test.util.ServerVersionTest;

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
    suite.addTestSuite(ANTTest.class);

    // Basic Driver internals
    suite.addTestSuite(DriverTest.class);
    suite.addTestSuite(ConnectionTest.class);
    suite.addTestSuite(DatabaseMetaDataTest.class);
    suite.addTestSuite(DatabaseMetaDataPropertiesTest.class);
    suite.addTestSuite(SearchPathLookupTest.class);
    suite.addTestSuite(EncodingTest.class);
    suite.addTestSuite(ColumnSanitiserDisabledTest.class);
    suite.addTestSuite(ColumnSanitiserEnabledTest.class);
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
    suite.addTestSuite(DateTest.class);
    suite.addTestSuite(TimeTest.class);
    suite.addTestSuite(TimestampTest.class);
    suite.addTestSuite(TimezoneTest.class);
    suite.addTest(new JUnit4TestAdapter(PGTimeTest.class));
    suite.addTestSuite(PGTimestampTest.class);
    suite.addTest(new JUnit4TestAdapter(TimezoneCachingTest.class));
    suite.addTestSuite(ParserTest.class);

    // PreparedStatement
    suite.addTest(new JUnit4TestAdapter(PreparedStatementTest.class));
    suite.addTestSuite(StatementTest.class);
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
    suite.addTestSuite(JBuilderTest.class);
    suite.addTestSuite(MiscTest.class);
    suite.addTestSuite(NotifyTest.class);
    suite.addTestSuite(DatabaseEncodingTest.class);

    // Fastpath/LargeObject
    suite.addTestSuite(BlobTest.class);
    suite.addTestSuite(OID74Test.class);
    suite.addTestSuite(BlobTransactionTest.class);

    suite.addTest(new JUnit4TestAdapter(UpdateableResultTest.class));

    suite.addTest(new JUnit4TestAdapter(CallableStmtTest.class));
    suite.addTestSuite(CursorFetchTest.class);
    suite.addTestSuite(CursorFetchBinaryTest.class);
    suite.addTest(new JUnit4TestAdapter(ServerCursorTest.class));

    suite.addTestSuite(IntervalTest.class);
    suite.addTestSuite(GeometricTest.class);

    suite.addTestSuite(LoginTimeoutTest.class);
    suite.addTestSuite(TestACL.class);

    suite.addTestSuite(ConnectTimeoutTest.class);

    suite.addTestSuite(PGPropertyTest.class);

    suite.addTestSuite(V3ParameterListTests.class);

    Connection conn = TestUtil.openDB();
    if (TestUtil.isProtocolVersion(conn, 3)) {
      suite.addTestSuite(CopyTest.class);
      suite.addTestSuite(CopyLargeFileTest.class);
    }
    if (TestUtil.haveMinimumServerVersion(conn, "9.3")) {
      suite.addTestSuite(ServerErrorTest.class);
    }

    if (TestUtil.haveMinimumServerVersion(conn, "9.5")) {
      suite.addTestSuite(UpsertTest.class);
      suite.addTestSuite(UpsertBinaryTest.class);
    }

    conn.close();

    // That's all folks
    return suite;
  }
}
