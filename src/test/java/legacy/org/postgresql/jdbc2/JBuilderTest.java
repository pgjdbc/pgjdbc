/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import legacy.org.postgresql.TestUtil;

import java.sql.*;

import junit.framework.TestCase;

/*
 * Some simple tests to check that the required components needed for JBuilder
 * stay working
 *
 */
public class JBuilderTest extends TestCase
{

    public JBuilderTest(String name)
    {
        super(name);
    }

    // Set up the fixture for this testcase: the tables for this test.
    protected void setUp() throws Exception
    {
        Connection con = TestUtil.openDB();

        TestUtil.createTable( con, "test_c",
                              "source text,cost money,imageid int4" );

        TestUtil.closeDB(con);
    }

    // Tear down the fixture for this test case.
    protected void tearDown() throws Exception
    {
        Connection con = TestUtil.openDB();
        TestUtil.dropTable(con, "test_c");
        TestUtil.closeDB(con);
    }

    /*
     * This tests that Money types work. JDBCExplorer barfs if this fails.
     */
    public void testMoney() throws Exception
    {
        Connection con = TestUtil.openDB();

        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery("select cost from test_c");
        assertNotNull(rs);

        while (rs.next())
        {
            rs.getDouble(1);
        }

        rs.close();
        st.close();

        TestUtil.closeDB(con);
    }
}
