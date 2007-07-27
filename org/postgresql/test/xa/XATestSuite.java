package org.postgresql.test.xa;

import java.sql.Connection;

import junit.framework.TestSuite;

import org.postgresql.test.TestUtil;

public class XATestSuite extends TestSuite {
    public static TestSuite suite() throws Exception {
        Class.forName("org.postgresql.Driver");
        TestSuite suite = new TestSuite();
        Connection connection = TestUtil.openDB();

        try
        {
            if (TestUtil.haveMinimumServerVersion(connection, "8.1"))
            {
                suite.addTestSuite(XADataSourceTest.class);
            }
        }
        finally
        {
            connection.close();
        }
        return suite;
    }
}
