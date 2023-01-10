/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import legacy.org.postgresql.TestUtil;
import junit.framework.TestCase;
import java.sql.*;
import java.io.*;

/*
 * Some simple tests based on problems reported by users. Hopefully these will
 * help prevent previous problems from re-occuring ;-)
 *
 */
public class MiscTest extends TestCase
{

    public MiscTest(String name)
    {
        super(name);
    }

    /*
     * Some versions of the driver would return rs as a null?
     *
     * Sasha <ber0806@iperbole.bologna.it> was having this problem.
     *
     * Added Feb 13 2001
     */
    public void testDatabaseSelectNullBug() throws Exception
    {
        Connection con = TestUtil.openDB();

        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery("select datname from pg_database");
        assertNotNull(rs);

        while (rs.next())
        {
            rs.getString(1);
        }

        rs.close();
        st.close();

        TestUtil.closeDB(con);
    }

    /**
     * Ensure the cancel call does not return before it has completed.
     * Previously it did which cancelled future queries.
     */
    public void testSingleThreadCancel() throws Exception
    {
        Connection con = TestUtil.openDB();
        Statement stmt = con.createStatement();
        for (int i=0; i<100; i++) {
            ResultSet rs = stmt.executeQuery("SELECT 1");
            rs.close();
            stmt.cancel();
        }
        TestUtil.closeDB(con);
    }

    public void testError() throws Exception
    {
        Connection con = TestUtil.openDB();
        try
        {

            // transaction mode
            con.setAutoCommit(false);
            Statement stmt = con.createStatement();
            stmt.execute("select 1/0");
            fail( "Should not execute this, as a SQLException s/b thrown" );
            con.commit();
        }
        catch ( SQLException ex )
        {
            // Verify that the SQLException is serializable.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(ex);
            oos.close();
        }

        con.commit();
        con.close();
    }

    public void testWarning() throws Exception
    {
        Connection con = TestUtil.openDB();
        Statement stmt = con.createStatement();
        stmt.execute("CREATE TEMP TABLE t(a int primary key)");
        SQLWarning warning = stmt.getWarnings();
        // We should get a warning about primary key index creation
        // it's possible we won't depending on the server's
        // client_min_messages setting.
        while (warning != null) {
            // Verify that the SQLWarning is serializable.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(warning);
            oos.close();
            warning = warning.getNextWarning();
        }

        stmt.close();
        con.close();
    }

    public void xtestLocking() throws Exception
    {
        Connection con = TestUtil.openDB();
        Connection con2 = TestUtil.openDB();

        TestUtil.createTable(con, "test_lock", "name text");
        Statement st = con.createStatement();
        Statement st2 = con2.createStatement();
        con.setAutoCommit(false);
        st.execute("lock table test_lock");
        st2.executeUpdate( "insert into test_lock ( name ) values ('hello')" );
        con.commit();
        TestUtil.dropTable(con, "test_lock");
        con.close();
        con2.close();
    }
}
