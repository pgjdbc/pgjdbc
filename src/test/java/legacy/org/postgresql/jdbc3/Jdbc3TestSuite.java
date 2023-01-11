/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc3;

import legacy.org.postgresql.TestUtil;

import junit.framework.TestSuite;

/*
 * Executes all known tests for JDBC3
 */
public class Jdbc3TestSuite extends TestSuite
{

    /*
     * The main entry point for JUnit
     */
    public static TestSuite suite() throws Exception
    {
        Class.forName("legacy.org.postgresql.Driver");
        TestSuite suite = new TestSuite();
        try
        {
            java.sql.Connection con = TestUtil.openDB();
        
            if ( TestUtil.haveMinimumServerVersion( con, "8.1") && TestUtil.isProtocolVersion(con, 3))
            {
                suite.addTestSuite(Jdbc3CallableStatementTest.class);
            }
            if ( TestUtil.haveMinimumServerVersion( con, "8.2") )
            {
                suite.addTestSuite(GeneratedKeysTest.class);
            }
            con.close();
        }
        catch (Exception ex )
        {
            ex.printStackTrace();
        }
        
        suite.addTestSuite(Jdbc3SavepointTest.class);
        suite.addTestSuite(TypesTest.class);
        suite.addTestSuite(ResultSetTest.class);
        suite.addTestSuite(ParameterMetaDataTest.class);
        suite.addTestSuite(Jdbc3BlobTest.class);
        suite.addTestSuite(DatabaseMetaDataTest.class);
        return suite;
    }
}
