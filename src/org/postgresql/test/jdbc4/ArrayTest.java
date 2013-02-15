/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc4;

import java.sql.*;
import junit.framework.TestCase;
import org.postgresql.test.TestUtil;

public class ArrayTest extends TestCase {

    private Connection _conn;

    public ArrayTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();
        TestUtil.createTable(_conn, "arrtest", "intarr int[], decarr decimal(2,1)[], strarr text[]");
    }

    protected void tearDown() throws SQLException {
        TestUtil.dropTable(_conn, "arrtest");
        TestUtil.closeDB(_conn);
    }

    public void testCreateArrayOfInt() throws SQLException {
        PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::int[]");
        Integer in[] = new Integer[3];
        in[0] = 0;
        in[1] = -1;
        in[2] = 2;
        pstmt.setArray(1, _conn.createArrayOf("int4", in));

        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        Array arr = rs.getArray(1);
        Integer out[] = (Integer [])arr.getArray();

        assertEquals(3, out.length);
        assertEquals(0, out[0].intValue());
        assertEquals(-1, out[1].intValue());
        assertEquals(2, out[2].intValue());
    }

    public void testCreateArrayOfMultiString() throws SQLException {
        PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::text[]");
        String in[][] = new String[2][2];
        in[0][0] = "a";
        in[0][1] = "";
        in[1][0] = "\\";
        in[1][1] = "\"\\'z";
        pstmt.setArray(1, _conn.createArrayOf("text", in));

        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        Array arr = rs.getArray(1);
        String out[][] = (String [][])arr.getArray();

        assertEquals(2, out.length);
        assertEquals(2, out[0].length);
        assertEquals("a", out[0][0]);
        assertEquals("", out[0][1]);
        assertEquals("\\", out[1][0]);
        assertEquals("\"\\'z", out[1][1]);
    }

    public void testCreateArrayOfNull() throws SQLException {
        if (!TestUtil.haveMinimumServerVersion(_conn, "8.2"))
            return;

        String sql = "SELECT ?";
        // We must provide the type information for V2 protocol
        if (TestUtil.isProtocolVersion(_conn, 2)) {
            sql = "SELECT ?::int8[]";
        }

        PreparedStatement pstmt = _conn.prepareStatement(sql);
        String in[] = new String[2];
        in[0] = null;
        in[1] = null;
        pstmt.setArray(1, _conn.createArrayOf("int8", in));

        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        Array arr = rs.getArray(1);
        Long out[] = (Long [])arr.getArray();

        assertEquals(2, out.length);
        assertNull(out[0]);
        assertNull(out[1]);
    }

    public void testCreateEmptyArrayOfIntViaAlias() throws SQLException {
        PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::int[]");
        Integer in[] = new Integer[0];
        pstmt.setArray(1, _conn.createArrayOf("integer", in));

        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        Array arr = rs.getArray(1);
        Integer out[] = (Integer [])arr.getArray();

        assertEquals(0, out.length);
        
        ResultSet arrRs = arr.getResultSet();
        assertFalse(arrRs.next());
    }

    public void testCreateArrayWithoutServer() throws SQLException {
        String in[][] = new String[2][2];
        in[0][0] = "a";
        in[0][1] = "";
        in[1][0] = "\\";
        in[1][1] = "\"\\'z";

        Array arr = _conn.createArrayOf("varchar", in);
        String out[][] = (String [][])arr.getArray();

        assertEquals(2, out.length);
        assertEquals(2, out[0].length);
        assertEquals("a", out[0][0]);
        assertEquals("", out[0][1]);
        assertEquals("\\", out[1][0]);
        assertEquals("\"\\'z", out[1][1]);
    }

    public void testCreatePrimitiveArray() throws SQLException {
        double in[][] = new double[2][2];
        in[0][0] = 3.5;
        in[0][1] = -4.5;
        in[1][0] = 10.0/3;
        in[1][1] = 77;

        Array arr = _conn.createArrayOf("float8", in);
        Double out[][] = (Double [][])arr.getArray();

        assertEquals(2, out.length);
        assertEquals(2, out[0].length);
        assertEquals(3.5, out[0][0], 0.00001);
        assertEquals(-4.5, out[0][1], 0.00001);
        assertEquals(10.0/3, out[1][0], 0.00001);
        assertEquals(77, out[1][1], 0.00001);
    }

    public void testSetObjectFromJavaArray() throws SQLException {
        String[] strArray = new String[]{"a","b","c"};

        PreparedStatement pstmt = _conn.prepareStatement("INSERT INTO arrtest(strarr) VALUES (?)");

        // Incorrect, but commonly attempted by many ORMs:
        try {
            pstmt.setObject(1, strArray, Types.ARRAY);
            pstmt.executeUpdate();
            fail("setObject() with a Java array parameter and Types.ARRAY shouldn't succeed");
        } catch (org.postgresql.util.PSQLException ex) {
            // Expected failure.
        }

        // Also incorrect, but commonly attempted by many ORMs:
        try {
            pstmt.setObject(1, strArray);
            pstmt.executeUpdate();
            fail("setObject() with a Java array parameter and no Types argument shouldn't succeed");
        } catch (org.postgresql.util.PSQLException ex) {
            // Expected failure.
        }

        // Correct way, though the use of "text" as a type is non-portable.
        // Only supported for JDK 1.6 and JDBC4
        Array sqlArray = _conn.createArrayOf("text", strArray);
        pstmt.setArray(1, sqlArray);
        pstmt.executeUpdate();

        pstmt.close();
    }
}
