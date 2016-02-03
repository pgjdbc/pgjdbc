/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import org.postgresql.test.CursorFetchBinaryTest;
import org.postgresql.test.TestUtil;

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
    suite.addTestSuite(VersionTest.class);

    // Connectivity/Protocols

    // ResultSet
    suite.addTestSuite(ResultSetTest.class);
    suite.addTestSuite(ResultSetMetaDataTest.class);
    suite.addTestSuite(ArrayTest.class);
    suite.addTestSuite(RefCursorTest.class);

    // Time, Date, Timestamp, PGTime, PGTimestamp
    suite.addTestSuite(DateTest.class);
    suite.addTestSuite(TimeTest.class);
    suite.addTestSuite(TimestampTest.class);
    suite.addTestSuite(TimezoneTest.class);
    suite.addTestSuite(PGTimeTest.class);
    suite.addTestSuite(PGTimestampTest.class);

    // PreparedStatement
    suite.addTestSuite(PreparedStatementTest.class);
    suite.addTestSuite(PreparedStatementBinaryTest.class);
    suite.addTestSuite(StatementTest.class);

    // ServerSide Prepared Statements
    suite.addTestSuite(ServerPreparedStmtTest.class);

    // BatchExecute
    suite.addTestSuite(BatchExecuteTest.class);
    suite.addTestSuite(BatchExecuteBinaryTest.class);
    suite.addTest(new JUnit4TestAdapter(BatchFailureTest.class));


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

    suite.addTestSuite(UpdateableResultTest.class);

    suite.addTestSuite(CallableStmtTest.class);
    suite.addTestSuite(CursorFetchTest.class);
    suite.addTestSuite(CursorFetchBinaryTest.class);
    suite.addTestSuite(ServerCursorTest.class);

    suite.addTestSuite(IntervalTest.class);
    suite.addTestSuite(GeometricTest.class);

    suite.addTestSuite(LoginTimeoutTest.class);
    suite.addTestSuite(TestACL.class);

    suite.addTestSuite(ConnectTimeoutTest.class);

    suite.addTestSuite(PGPropertyTest.class);

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
