/*-------------------------------------------------------------------------
*
* Copyright (c) 2007, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL$
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc4;

import org.postgresql.test.TestUtil;

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
        return suite;
    }
}
