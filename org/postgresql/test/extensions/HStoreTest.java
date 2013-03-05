/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.extensions;

import java.sql.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;
import org.postgresql.test.TestUtil;

// SELECT 'hstore'::regtype::oid
// SELECT 'hstore[]'::regtype::oid

public class HStoreTest extends TestCase {

    private Connection _conn;

    public HStoreTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();
    }

    protected void tearDown() throws SQLException {
        TestUtil.closeDB(_conn);
    }

    public void testHStoreSelect() throws SQLException {
        PreparedStatement pstmt = _conn.prepareStatement("SELECT 'a=>1,b=>2'::hstore");
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        String str = rs.getString(1);
        if (!("\"a\"=>\"1\", \"b\"=>\"2\"".equals(str) || "\"b\"=>\"2\", \"a\"=>\"1\"".equals(str))) {
            fail("Expected " + "\"a\"=>\"1\", \"b\"=>\"2\"" + " but got " + str);
        }
        Map correct = new HashMap();
        correct.put("a", "1");
        correct.put("b", "2");
        assertEquals(correct, rs.getObject(1));
    }

    public void testHStoreSelectNullValue() throws SQLException {
        PreparedStatement pstmt = _conn.prepareStatement("SELECT 'a=>NULL'::hstore");
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        assertEquals("\"a\"=>NULL", rs.getString(1));
        Map correct = Collections.singletonMap("a", null);
        assertEquals(correct, rs.getObject(1));
    }

    public void testHStoreSend() throws SQLException {
        Map correct = Collections.singletonMap("a", new Integer(1));
        PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::text");
        pstmt.setObject(1, correct);
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        assertEquals("\"a\"=>\"1\"", rs.getString(1));
    }

    public void testHStoreSendEscaped() throws SQLException {
        Map correct = Collections.singletonMap("a", "t'e\ns\"t");
        PreparedStatement pstmt = _conn.prepareStatement("SELECT ?");
        pstmt.setObject(1, correct);
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        assertEquals(correct, rs.getObject(1));
        assertEquals("\"a\"=>\"t'e\ns\\\"t\"", rs.getString(1));
    }

}
