/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;

import legacy.org.postgresql.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

public class DatabaseMetaDataTest extends TestCase
{

    private Connection _conn;

    public DatabaseMetaDataTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        _conn = TestUtil.openDB();
        TestUtil.dropSequence(_conn, "sercoltest_a_seq");
        TestUtil.createTable(_conn, "sercoltest", "a serial, b int");
    }

    protected void tearDown() throws Exception
    {
        TestUtil.dropSequence(_conn, "sercoltest_a_seq");
        TestUtil.dropTable(_conn, "sercoltest");
        TestUtil.closeDB( _conn );
    }

    public void testGetClientInfoProperties() throws Exception
    {
        DatabaseMetaData dbmd = _conn.getMetaData();

        ResultSet rs = dbmd.getClientInfoProperties();
        if (!TestUtil.haveMinimumServerVersion(_conn, "9.0")) {
            assertTrue( !rs.next() );
            return;
        }

        assertTrue(rs.next());
        assertEquals("ApplicationName", rs.getString("NAME"));
    }

    public void testGetColumnsForAutoIncrement() throws Exception
    {
        DatabaseMetaData dbmd = _conn.getMetaData();

        ResultSet rs = dbmd.getColumns("%","%","sercoltest", "%");
        assertTrue( rs.next() );
        assertEquals("a", rs.getString("COLUMN_NAME"));
        assertEquals("YES", rs.getString("IS_AUTOINCREMENT"));

        assertTrue( rs.next() );
        assertEquals("b", rs.getString("COLUMN_NAME"));
        assertEquals("NO", rs.getString("IS_AUTOINCREMENT"));

        assertTrue( !rs.next() );
    }

    public void testGetSchemas() throws SQLException
    {
        DatabaseMetaData dbmd = _conn.getMetaData();

        ResultSet rs = dbmd.getSchemas("", "publ%");

        if (!TestUtil.haveMinimumServerVersion(_conn, "7.3")) {
            assertTrue(!rs.next());
            return;
        }

        assertTrue(rs.next());
        assertEquals("public", rs.getString("TABLE_SCHEM"));
        assertNull(rs.getString("TABLE_CATALOG"));
        assertTrue(!rs.next());
    }
}
