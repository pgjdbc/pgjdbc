/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc3/Jdbc3TestSuite.java,v 1.15 2005/07/04 18:50:30 davec Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc3;

import org.postgresql.test.TestUtil;

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
        Class.forName("org.postgresql.Driver");
        TestSuite suite = new TestSuite();
        try
        {
            java.sql.Connection con = TestUtil.openDB();
        
            if ( TestUtil.haveMinimumServerVersion( con, "8.1") && TestUtil.isProtocolVersion(con, 3))
            {
                suite.addTestSuite(Jdbc3CallableStatementTest.class);
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
        return suite;
    }
}
