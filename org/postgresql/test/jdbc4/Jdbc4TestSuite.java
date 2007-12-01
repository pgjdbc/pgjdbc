/*-------------------------------------------------------------------------
*
* Copyright (c) 2007, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc4/Jdbc4TestSuite.java,v 1.3 2007/10/07 19:40:02 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc4;

import junit.framework.TestSuite;

/*
 * Executes all known tests for JDBC4
 */
public class Jdbc4TestSuite extends TestSuite
{

    /*
     * The main entry point for JUnit
     */
    public static TestSuite suite() throws Exception
    {
        Class.forName("org.postgresql.Driver");
        TestSuite suite = new TestSuite();
        
        suite.addTestSuite(LOBTest.class);
        suite.addTestSuite(DatabaseMetaDataTest.class);
        suite.addTestSuite(ArrayTest.class);
        return suite;
    }
}

