/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc4;

import java.sql.*;
import java.util.UUID;

import junit.framework.TestCase;
import org.postgresql.test.TestUtil;
import org.postgresql.geometric.PGbox;

public class ArrayTest extends TestCase {

    private Connection _conn;

    public ArrayTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();
        TestUtil.createTable(_conn, "arrtest", "intarr int[], decarr decimal(2,1)[], strarr text[], uuidarr uuid[]");
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

    public void testCreateArrayWithNonStandardDelimiter() throws SQLException {
        PGbox in[] = new PGbox[2];
        in[0] = new PGbox(1, 2, 3, 4);
        in[1] = new PGbox(5, 6, 7, 8);

        PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::box[]");
        pstmt.setArray(1, _conn.createArrayOf("box", in));
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        Array arr = rs.getArray(1);
        ResultSet arrRs = arr.getResultSet();
        assertTrue(arrRs.next());
        assertEquals(in[0], arrRs.getObject(2));
        assertTrue(arrRs.next());
        assertEquals(in[1], arrRs.getObject(2));
        assertFalse(arrRs.next());
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

    public void testUUIDArray() throws SQLException {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        UUID uuid3 = UUID.randomUUID();

        // insert a uuid array, and check
        PreparedStatement pstmt1 = _conn.prepareStatement("INSERT INTO arrtest(uuidarr) VALUES (?)");
        pstmt1.setArray(1, _conn.createArrayOf("uuid", new UUID[]{ uuid1, uuid2, uuid3 }));
        pstmt1.executeUpdate();

        PreparedStatement pstmt2 = _conn.prepareStatement("SELECT uuidarr FROM arrtest WHERE uuidarr @> ?");
        pstmt2.setObject(1, _conn.createArrayOf("uuid", new UUID[]{ uuid1 }), Types.OTHER);
        ResultSet rs = pstmt2.executeQuery();
        assertTrue(rs.next());
        Array arr = rs.getArray(1);
        UUID out[] = (UUID [])arr.getArray();

        assertEquals(3, out.length);
        assertEquals(uuid1, out[0]);
        assertEquals(uuid2, out[1]);
        assertEquals(uuid3, out[2]);

        // concatenate a uuid, and check
        UUID uuid4 = UUID.randomUUID();
        PreparedStatement pstmt3 = _conn.prepareStatement("UPDATE arrtest SET uuidarr = uuidarr || ? WHERE uuidarr @> ?");
        pstmt3.setObject(1, uuid4, Types.OTHER);
        pstmt3.setArray(2, _conn.createArrayOf("uuid", new UUID[]{ uuid1 }));
        pstmt3.executeUpdate();

        //--
        pstmt2.setObject(1, _conn.createArrayOf("uuid", new UUID[]{ uuid4 }), Types.OTHER);
        rs = pstmt2.executeQuery();
        assertTrue(rs.next());
        arr = rs.getArray(1);
        out = (UUID [])arr.getArray();

        assertEquals(4, out.length);
        assertEquals(uuid1, out[0]);
        assertEquals(uuid2, out[1]);
        assertEquals(uuid3, out[2]);
        assertEquals(uuid4, out[3]);
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
