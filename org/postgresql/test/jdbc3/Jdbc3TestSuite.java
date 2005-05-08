/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc3/Jdbc3TestSuite.java,v 1.13 2005/03/28 08:52:35 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc3;

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
        suite.addTestSuite(Jdbc3SavepointTest.class);
        suite.addTestSuite(TypesTest.class);
        suite.addTestSuite(ResultSetTest.class);
        suite.addTestSuite(ParameterMetaDataTest.class);
        suite.addTestSuite(Jdbc3BlobTest.class);
        return suite;
    }
}
