/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;

import junit.framework.TestSuite;
import legacy.org.postgresql.TestUtil;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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
        Class.forName("legacy.org.postgresql.Driver");
        TestSuite suite = new TestSuite();
        
        suite.addTestSuite(LOBTest.class);
        suite.addTestSuite(DatabaseMetaDataTest.class);
        suite.addTestSuite(ArrayTest.class);
        suite.addTestSuite(ConnectionTest.class);

        Connection connection = TestUtil.openDB();
        try
        {
            if (TestUtil.haveMinimumServerVersion(connection, "8.3"))
            {
                suite.addTestSuite(UUIDTest.class);
                if (isXmlEnabled(connection)) {
                    suite.addTestSuite(XmlTest.class);
                }
            }
        }
        finally
        {
            connection.close();
        }

        return suite;
    }

    /**
     * Not all servers will have been complied --with-libxml.
     */
    private static boolean isXmlEnabled(Connection conn) {
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT '<a>b</a>'::xml");
            rs.close();
            stmt.close();
            return true;
        } catch (SQLException sqle) {
            return false;
        }
    }

}

