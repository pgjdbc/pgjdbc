/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc2/Jdbc2TestSuite.java,v 1.17 2004/11/07 22:16:53 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import junit.framework.TestSuite;

/*
 * Executes all known tests for JDBC2 and includes some utility methods.
 */
public class Jdbc2TestSuite extends TestSuite
{

    /*
     * The main entry point for JUnit
     */
    public static TestSuite suite()
    {
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
        suite.addTestSuite(EncodingTest.class);

        // Connectivity/Protocols

        // ResultSet
        suite.addTestSuite(ResultSetTest.class);
        suite.addTestSuite(ResultSetMetaDataTest.class);
        suite.addTestSuite(ArrayTest.class);

        // Time, Date, Timestamp
        suite.addTestSuite(DateTest.class);
        suite.addTestSuite(TimeTest.class);
        suite.addTestSuite(TimestampTest.class);

        // PreparedStatement
        suite.addTestSuite(PreparedStatementTest.class);
        suite.addTestSuite(StatementTest.class);

        // ServerSide Prepared Statements
        suite.addTestSuite(ServerPreparedStmtTest.class);

        // BatchExecute
        suite.addTestSuite(BatchExecuteTest.class);


        // Other misc tests, based on previous problems users have had or specific
        // features some applications require.
        suite.addTestSuite(JBuilderTest.class);
        suite.addTestSuite(MiscTest.class);
        suite.addTestSuite(NotifyTest.class);
        suite.addTestSuite(DatabaseEncodingTest.class);

        // Fastpath/LargeObject
        suite.addTestSuite(BlobTest.class);
        suite.addTestSuite(OID74Test.class);

        suite.addTestSuite(UpdateableResultTest.class );

        suite.addTestSuite(CallableStmtTest.class );
        suite.addTestSuite(CursorFetchTest.class);
        suite.addTestSuite(ServerCursorTest.class);

        suite.addTestSuite(GeometricTest.class);

        // That's all folks
        return suite;
    }
}
