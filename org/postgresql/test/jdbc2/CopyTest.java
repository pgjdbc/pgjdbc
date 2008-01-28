/*-------------------------------------------------------------------------
*
* Copyright (c) 2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL$
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

/**
 * Even though the driver doesn't support the copy protocol, if a user
 * issues a copy command, the driver shouldn't destroy the whole connection.
 */

public class CopyTest extends TestCase
{
    private Connection conn;
    
    public CopyTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        conn = TestUtil.openDB();
        TestUtil.createTempTable(conn, "copytest", "a int");
    }

    protected void tearDown() throws Exception
    {
        TestUtil.dropTable(conn, "copytest");
        TestUtil.closeDB(conn);
    }

    public void testCopyIn() throws SQLException
    {
        if (!TestUtil.isProtocolVersion(conn, 3))
            return;

        Statement stmt = conn.createStatement();
        try {
            stmt.execute("COPY copytest FROM STDIN");
            fail("Should have failed because copy doesn't work.");
        } catch (SQLException sqle) { }
        stmt.close();

        ensureConnectionWorks();
    }

    public void testCopyOut() throws SQLException
    {
        if (!TestUtil.isProtocolVersion(conn, 3))
            return;

        Statement stmt = conn.createStatement();
        if (TestUtil.haveMinimumServerVersion(conn, "8.0")) {
            stmt.execute("INSERT INTO copytest SELECT generate_series(1, 1000)");
        } else {
            stmt.execute("INSERT INTO copytest VALUES (1)");
        }

        try {
            stmt.execute("COPY copytest TO STDOUT");
            fail("Should have failed because copy doesn't work.");
        } catch (SQLException sqle) { }
        stmt.close();

        ensureConnectionWorks();
    }

    private void ensureConnectionWorks() throws SQLException
    {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT 1");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        rs.close();
        stmt.close();
    }

}
