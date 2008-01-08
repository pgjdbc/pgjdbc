/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc4/LOBTest.java,v 1.1 2007/02/19 17:21:13 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc4;

import java.sql.*;
import junit.framework.TestCase;
import org.postgresql.test.TestUtil;

public class LOBTest extends TestCase {

    private Connection _conn;

    public LOBTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();
        Statement stmt = _conn.createStatement();
        stmt.execute("CREATE TEMP TABLE lotest(lo oid)");
        stmt.execute("INSERT INTO lotest VALUES(lo_creat(-1))");
        stmt.close();
    }

    protected void tearDown() throws SQLException {
        Statement stmt = _conn.createStatement();
        stmt.execute("DROP TABLE lotest");
        stmt.close();
        TestUtil.closeDB(_conn);
    }

    public void testFree() throws SQLException {
        _conn.setAutoCommit(false);
        Statement stmt = _conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT lo FROM lotest");
        assertTrue(rs.next());

        Blob blob = rs.getBlob(1);
        blob.free();
        try {
            blob.length();
            fail("Should have thrown an Exception because it was freed.");
        } catch (SQLException sqle) {
        }
    }

}
