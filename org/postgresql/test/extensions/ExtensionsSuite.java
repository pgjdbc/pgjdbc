/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.extensions;

import junit.framework.TestSuite;

import java.sql.*;
import org.postgresql.test.TestUtil;

/*
 * Executes all known tests for PostgreSQL extensions supported by JDBC driver
 */
public class ExtensionsSuite extends TestSuite
{

    /*
     * The main entry point for JUnit
     */
    public static TestSuite suite() throws Exception
    {
        Class.forName("org.postgresql.Driver");
        TestSuite suite = new TestSuite();
        
        Connection connection = TestUtil.openDB();
        try
        {
            if (isHStoreEnabled(connection)) {
              suite.addTestSuite(HStoreTest.class);
            }
        }
        finally
        {
            connection.close();
        }

        return suite;
    }

    /**
     * Not all servers have hstore extensions installed.
     */
    private static boolean isHStoreEnabled(Connection conn) {
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT 'a=>1'::hstore::text");
            rs.close();
            stmt.close();
            return true;
        } catch (SQLException sqle) {
            return false;
        }
    }

}

