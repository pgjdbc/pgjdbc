/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2.optional;

import java.sql.*;
import org.postgresql.test.TestUtil;
import org.postgresql.jdbc2.optional.PoolingDataSource;
import org.postgresql.ds.common.BaseDataSource;

/**
 * Minimal tests for pooling DataSource.  Needs many more.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class PoolingDataSourceTest extends BaseDataSourceTest
{
    private final static String DS_NAME = "JDBC 2 SE Test DataSource";

    /**
     * Constructor required by JUnit
     */
    public PoolingDataSourceTest(String name)
    {
        super(name);
    }

    protected void tearDown() throws Exception
    {
        if (bds instanceof PoolingDataSource)
        {
            ((PoolingDataSource) bds).close();
        }
        super.tearDown();
    }

    /**
     * Creates and configures a new SimpleDataSource.
     */
    protected void initializeDataSource()
    {
        if (bds == null)
        {
            bds = new PoolingDataSource();
            setupDataSource(bds);
            ((PoolingDataSource) bds).setDataSourceName(DS_NAME);
            ((PoolingDataSource) bds).setInitialConnections(2);
            ((PoolingDataSource) bds).setMaxConnections(10);
        }
    }

    /**
     * In this case, we *do* want it to be pooled.
     */
    public void testNotPooledConnection() throws SQLException
    {
        con = getDataSourceConnection();
        String name = con.toString();
        con.close();
        con = getDataSourceConnection();
        String name2 = con.toString();
        con.close();
        assertTrue("Pooled DS doesn't appear to be pooling connections!", name.equals(name2));
    }

    /**
     * In this case, the desired behavior is dereferencing.
     */
    protected void compareJndiDataSource(BaseDataSource oldbds, BaseDataSource bds)
    {
        assertTrue("DataSource was serialized or recreated, should have been dereferenced", bds == oldbds);
    }

    /**
     * Check that 2 DS instances can't use the same name.
     */
    public void testCantReuseName()
    {
        initializeDataSource();
        PoolingDataSource pds = new PoolingDataSource();
        try
        {
            pds.setDataSourceName(DS_NAME);
            fail("Should have denied 2nd DataSource with same name");
        }
        catch (IllegalArgumentException e)
        {
        }
    }

    /**
     * Closing a Connection twice is not an error.
     */
    public void testDoubleConnectionClose() throws SQLException
    {
        con = getDataSourceConnection();
        con.close();
        con.close();
    }

    /**
     * Closing a Statement twice is not an error.
     */
    public void testDoubleStatementClose() throws SQLException
    {
        con = getDataSourceConnection();
        Statement stmt = con.createStatement();
        stmt.close();
        stmt.close();
        con.close();
    }

    public void testConnectionObjectMethods() throws SQLException
    {
        con = getDataSourceConnection();

        Connection conRef = con;
        assertEquals(con, conRef);

        int hc1 = con.hashCode();
        con.close();
        int hc2 = con.hashCode();

        assertEquals(con, conRef);
        assertEquals(hc1, hc2);
    }

    public void testStatementObjectMethods() throws SQLException
    {
        con = getDataSourceConnection();

        Statement stmt = con.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT 1");
        Statement stmtRef = stmt;

        assertEquals(stmt, stmtRef);
        // Currently we aren't proxying ResultSet, so this doesn't
        // work, see Bug #1010542.
        // assertEquals(stmt, rs.getStatement());

        int hc1 = stmt.hashCode();
        stmt.close();
        int hc2 = stmt.hashCode();

        assertEquals(stmt, stmtRef);
        assertEquals(hc1, hc2);
    }

}
