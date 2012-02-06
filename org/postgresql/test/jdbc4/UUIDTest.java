/*-------------------------------------------------------------------------
*
* Copyright (c) 2008-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc4;

import java.sql.*;
import java.util.UUID;

import junit.framework.TestCase;

import org.postgresql.test.TestUtil;

public class UUIDTest extends TestCase {

    private Connection _conn;

    public UUIDTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();
        Statement stmt = _conn.createStatement();
        stmt.execute("CREATE TEMP TABLE uuidtest(id uuid)");
        stmt.close();
    }

    protected void tearDown() throws SQLException {
        Statement stmt = _conn.createStatement();
        stmt.execute("DROP TABLE uuidtest");
        stmt.close();
        TestUtil.closeDB(_conn);
    }

    public void testUUID() throws SQLException {
        UUID uuid = UUID.randomUUID();
        PreparedStatement ps = _conn.prepareStatement("INSERT INTO uuidtest VALUES (?)");
        ps.setObject(1, uuid, Types.OTHER);
        ps.executeUpdate();
        ps.close();

        Statement stmt = _conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT id FROM uuidtest");
        assertTrue(rs.next());

        UUID uuid2 = (UUID)rs.getObject(1);
        assertEquals(uuid, rs.getObject(1));
        assertEquals(uuid.toString(), rs.getString(1));

        rs.close();
        stmt.close();
    }

}

