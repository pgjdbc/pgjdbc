/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc3;

import java.sql.*;
import junit.framework.TestCase;
import org.postgresql.test.TestUtil;

public class ResultSetTest extends TestCase {

    private Connection _conn;

    public ResultSetTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();
        Statement stmt = _conn.createStatement();
        stmt.execute("CREATE TEMP TABLE hold(a int)");
        stmt.execute("INSERT INTO hold VALUES (1)");
        stmt.execute("INSERT INTO hold VALUES (2)");
        stmt.close();
    }

    protected void tearDown() throws SQLException {
        Statement stmt = _conn.createStatement();
        stmt.execute("DROP TABLE hold");
        stmt.close();
        TestUtil.closeDB(_conn);
    }

    public void testHoldableResultSet() throws SQLException {
        Statement stmt = _conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);

        _conn.setAutoCommit(false);
        stmt.setFetchSize(1);

        ResultSet rs = stmt.executeQuery("SELECT a FROM hold ORDER BY a");

        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));

        _conn.commit();

        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        assertTrue(!rs.next());

        rs.close();
        stmt.close();
    }

}
