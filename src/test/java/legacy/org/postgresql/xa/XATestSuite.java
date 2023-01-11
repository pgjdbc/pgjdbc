/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2011, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.xa;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;

import junit.framework.TestSuite;

import legacy.org.postgresql.TestUtil;

public class XATestSuite extends TestSuite {
    public static TestSuite suite() throws Exception {
        Class.forName("legacy.org.postgresql.Driver");
        TestSuite suite = new TestSuite();
        Connection connection = TestUtil.openDB();

        try
        {
            if (TestUtil.haveMinimumServerVersion(connection, "8.1"))
            {
                Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery("SHOW max_prepared_transactions");
                rs.next();
                int mpt = rs.getInt(1);
                if (mpt > 0) {
                    suite.addTestSuite(XADataSourceTest.class);
                } else {
                    System.out.println("Skipping XA tests because max_prepared_transactions = 0.");
                }
                rs.close();
                stmt.close();
            }
        }
        finally
        {
            connection.close();
        }
        return suite;
    }
}
