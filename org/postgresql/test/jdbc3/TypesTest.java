/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc3/TypesTest.java,v 1.5 2004/11/09 08:56:33 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc3;

import java.sql.*;
import junit.framework.TestCase;
import org.postgresql.test.TestUtil;

public class TypesTest extends TestCase {

    private Connection _conn;

    public TypesTest(String name) {
        super(name);
    }

    protected void setUp() throws SQLException {
        _conn = TestUtil.openDB();
        Statement stmt = _conn.createStatement();
        stmt.execute("CREATE OR REPLACE FUNCTION return_bool(boolean) RETURNS boolean AS 'BEGIN RETURN $1; END;' LANGUAGE 'plpgsql'");
        stmt.close();
    }

    protected void tearDown() throws SQLException {
        Statement stmt = _conn.createStatement();
        stmt.execute("DROP FUNCTION return_bool(boolean)");
        stmt.close();
        TestUtil.closeDB(_conn);
    }

    public void testPreparedBoolean() throws SQLException {
        PreparedStatement pstmt = _conn.prepareStatement("SELECT ?,?,?,?");
        pstmt.setNull(1, Types.BOOLEAN);
        pstmt.setObject(2, null, Types.BOOLEAN);
        pstmt.setBoolean(3, true);
        pstmt.setObject(4, Boolean.FALSE);
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        assertTrue(!rs.getBoolean(1));
        assertTrue(rs.wasNull());
        assertNull(rs.getObject(2));
        assertTrue(rs.getBoolean(3));
        // Only the V3 protocol return will be strongly typed.
        // The V2 path will return a String because it doesn't know
        // any better.
        if (TestUtil.haveMinimumServerVersion(_conn, "7.4"))
        {
            assertTrue(!((Boolean)rs.getObject(4)).booleanValue());
        }
    }

    public void testCallableBoolean() throws SQLException {
        CallableStatement cs = _conn.prepareCall("{? = call return_bool(?)}");
        cs.registerOutParameter(1, Types.BOOLEAN);
        cs.setBoolean(2, true);
        cs.execute();
        assertEquals(true, cs.getBoolean(1));
        cs.close();
    }

}
