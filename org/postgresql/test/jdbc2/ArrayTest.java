/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import java.sql.*;
import java.math.BigDecimal;

import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGpoint;

import junit.framework.TestCase;

public class ArrayTest extends TestCase
{
    private Connection conn;

    public ArrayTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        conn = TestUtil.openDB();
        TestUtil.createTable(conn, "arrtest", "intarr int[], decarr decimal(2,1)[], strarr text[]");
    }

    protected void tearDown() throws SQLException
    {
        TestUtil.dropTable(conn, "arrtest");
        TestUtil.closeDB(conn);
    }

    public void testSetNull() throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");
        pstmt.setNull(1, Types.ARRAY);
        pstmt.setNull(2, Types.ARRAY);
        pstmt.setNull(3, Types.ARRAY);
        pstmt.executeUpdate();

        pstmt.setObject(1, null, Types.ARRAY);
        pstmt.setObject(2, null);
        pstmt.setObject(3, null);
        pstmt.executeUpdate();

        pstmt.setArray(1, null);
        pstmt.setArray(2, null);
        pstmt.setArray(3, null);
        pstmt.executeUpdate();

        pstmt.close();
    }


    public void testRetrieveArrays() throws SQLException {
        Statement stmt = conn.createStatement();

        // you need a lot of backslashes to get a double quote in.
        stmt.executeUpdate("INSERT INTO arrtest VALUES ('{1,2,3}','{3.1,1.4}', '"
                + TestUtil.escapeString(conn, "{abc,f'a,\"fa\\\"b\",def}") + "')");

        ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
        assertTrue(rs.next());

        Array arr = rs.getArray(1);
        assertEquals(Types.INTEGER, arr.getBaseType());
        Integer intarr[] = (Integer[])arr.getArray();
        assertEquals(3, intarr.length);
        assertEquals(1, intarr[0].intValue());
        assertEquals(2, intarr[1].intValue());
        assertEquals(3, intarr[2].intValue());

        arr = rs.getArray(2);
        assertEquals(Types.NUMERIC, arr.getBaseType());
        BigDecimal decarr[] = (BigDecimal[])arr.getArray();
        assertEquals(2, decarr.length);
        assertEquals(new BigDecimal("3.1"), decarr[0]);
        assertEquals(new BigDecimal("1.4"), decarr[1]);

        arr = rs.getArray(3);
        assertEquals(Types.VARCHAR, arr.getBaseType());
        String strarr[] = (String[])arr.getArray(2, 2);
        assertEquals(2, strarr.length);
        assertEquals("f'a", strarr[0]);
        assertEquals("fa\"b", strarr[1]);

        rs.close();
        stmt.close();
    }

    public void testRetrieveResultSets() throws SQLException {
        Statement stmt = conn.createStatement();

        // you need a lot of backslashes to get a double quote in.
        stmt.executeUpdate("INSERT INTO arrtest VALUES ('{1,2,3}','{3.1,1.4}', '" +
                TestUtil.escapeString(conn, "{abc,f'a,\"fa\\\"b\",def}") + "')");

        ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
        assertTrue(rs.next());

        Array arr = rs.getArray(1);
        assertEquals(Types.INTEGER, arr.getBaseType());
        ResultSet arrrs = arr.getResultSet();
        assertTrue(arrrs.next());
        assertEquals(1, arrrs.getInt(1));
        assertEquals(1, arrrs.getInt(2));
        assertTrue(arrrs.next());
        assertEquals(2, arrrs.getInt(1));
        assertEquals(2, arrrs.getInt(2));
        assertTrue(arrrs.next());
        assertEquals(3, arrrs.getInt(1));
        assertEquals(3, arrrs.getInt(2));
        assertTrue(!arrrs.next());
        assertTrue(arrrs.previous());
        assertEquals(3, arrrs.getInt(2));
        arrrs.first();
        assertEquals(1, arrrs.getInt(2));
        arrrs.close();

        arr = rs.getArray(2);
        assertEquals(Types.NUMERIC, arr.getBaseType());
        arrrs = arr.getResultSet();
        assertTrue(arrrs.next());
        assertEquals(new BigDecimal("3.1"), arrrs.getBigDecimal(2));
        assertTrue(arrrs.next());
        assertEquals(new BigDecimal("1.4"), arrrs.getBigDecimal(2));
        arrrs.close();

        arr = rs.getArray(3);
        assertEquals(Types.VARCHAR, arr.getBaseType());
        arrrs = arr.getResultSet(2, 2);
        assertTrue(arrrs.next());
        assertEquals(2, arrrs.getInt(1));
        assertEquals("f'a", arrrs.getString(2));
        assertTrue(arrrs.next());
        assertEquals(3, arrrs.getInt(1));
        assertEquals("fa\"b", arrrs.getString(2));
        assertTrue(!arrrs.next());
        arrrs.close();

        rs.close();
        stmt.close();
    }

    public void testSetArray() throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet arrRS = stmt.executeQuery("SELECT '{1,2,3}'::int4[]");
        assertTrue(arrRS.next());
        Array arr = arrRS.getArray(1);
        arrRS.close();
        stmt.close();

        PreparedStatement pstmt = conn.prepareStatement("INSERT INTO arrtest(intarr) VALUES (?)");
        pstmt.setArray(1, arr);
        pstmt.executeUpdate();

        pstmt.setObject(1, arr, Types.ARRAY);
        pstmt.executeUpdate();

        pstmt.setObject(1, arr);
        pstmt.executeUpdate();

        pstmt.close();

        Statement select = conn.createStatement();
        ResultSet rs = select.executeQuery("SELECT intarr FROM arrtest");
        int resultCount = 0;
        while(rs.next()) {
            resultCount++;
            Array result = rs.getArray(1);
            assertEquals(Types.INTEGER, result.getBaseType());
            assertEquals("int4", result.getBaseTypeName());

            Integer intarr[] = (Integer[])result.getArray();
            assertEquals(3, intarr.length);
            assertEquals(1, intarr[0].intValue());
            assertEquals(2, intarr[1].intValue());
            assertEquals(3, intarr[2].intValue());
        }
        assertEquals(3, resultCount);
    }

    /**
     * Starting with 8.0 non-standard (beginning index isn't 1) bounds
     * the dimensions are returned in the data.  The following should
     * return "[0:3]={0,1,2,3,4}" when queried.  Older versions simply
     * do not return the bounds.
     */
    public void testNonStandardBounds() throws SQLException {
        Statement stmt = conn.createStatement();
        stmt.executeUpdate("INSERT INTO arrtest (intarr) VALUES ('{1,2,3}')");
        stmt.executeUpdate("UPDATE arrtest SET intarr[0] = 0");
        ResultSet rs = stmt.executeQuery("SELECT intarr FROM arrtest");
        assertTrue(rs.next());
        Array result = rs.getArray(1);
        Integer intarr[] = (Integer[])result.getArray();
        assertEquals(4, intarr.length);
        for (int i = 0; i < intarr.length; i++)
        {
            assertEquals(i, intarr[i].intValue());
        }
    }

    public void testMultiDimensionalArray() throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT '{{1,2},{3,4}}'::int[]");
        assertTrue(rs.next());
        Array arr = rs.getArray(1);
        Object oa[] = (Object [])arr.getArray();
        assertEquals(2, oa.length);
        Integer i0[] = (Integer []) oa[0];
        assertEquals(2, i0.length);
        assertEquals(1, i0[0].intValue());
        assertEquals(2, i0[1].intValue());
        Integer i1[] = (Integer []) oa[1];
        assertEquals(2, i1.length);
        assertEquals(3, i1[0].intValue());
        assertEquals(4, i1[1].intValue());
        rs.close();
        stmt.close();
    }

    public void testNullValues() throws SQLException {
        if (!TestUtil.haveMinimumServerVersion(conn, "8.2"))
            return;

        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT ARRAY[1,NULL,3]");
        assertTrue(rs.next());
        Array arr = rs.getArray(1);
        Integer i[] = (Integer[])arr.getArray();
        assertEquals(3, i.length);
        assertEquals(1, i[0].intValue());
        assertNull(i[1]);
        assertEquals(3, i[2].intValue());
    }

    public void testUnknownArrayType() throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT relacl FROM pg_class WHERE relacl IS NOT NULL LIMIT 1");
        ResultSetMetaData rsmd = rs.getMetaData();
        assertEquals(Types.ARRAY, rsmd.getColumnType(1));

        assertTrue(rs.next());
        Array arr = rs.getArray(1);
        assertEquals("aclitem", arr.getBaseTypeName());

        ResultSet arrRS = arr.getResultSet();
        ResultSetMetaData arrRSMD = arrRS.getMetaData();
        assertEquals("aclitem", arrRSMD.getColumnTypeName(2));
    }

    public void testRecursiveResultSets() throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT '{{1,2},{3,4}}'::int[]");
        assertTrue(rs.next());
        Array arr = rs.getArray(1);

        ResultSet arrRS = arr.getResultSet();
        ResultSetMetaData arrRSMD = arrRS.getMetaData();
        assertEquals(Types.ARRAY, arrRSMD.getColumnType(2));
        assertEquals("_int4", arrRSMD.getColumnTypeName(2));

        assertTrue(arrRS.next());
        assertEquals(1, arrRS.getInt(1));
        Array a1 = arrRS.getArray(2);
        ResultSet a1RS = a1.getResultSet();
        ResultSetMetaData a1RSMD = a1RS.getMetaData();
        assertEquals(Types.INTEGER, a1RSMD.getColumnType(2));
        assertEquals("int4", a1RSMD.getColumnTypeName(2));

        assertTrue(a1RS.next());
        assertEquals(1, a1RS.getInt(2));
        assertTrue(a1RS.next());
        assertEquals(2, a1RS.getInt(2));
        assertTrue(!a1RS.next());
        a1RS.close();

        assertTrue(arrRS.next());
        assertEquals(2, arrRS.getInt(1));
        Array a2 = arrRS.getArray(2);
        ResultSet a2RS = a2.getResultSet();

        assertTrue(a2RS.next());
        assertEquals(3, a2RS.getInt(2));
        assertTrue(a2RS.next());
        assertEquals(4, a2RS.getInt(2));
        assertTrue(!a2RS.next());
        a2RS.close();

        arrRS.close();
        rs.close();
        stmt.close();
    }

    public void testNullString() throws SQLException
    {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT '{a,NULL}'::text[]");
        assertTrue(rs.next());
        Array arr = rs.getArray(1);

        String s[] = (String [])arr.getArray();
        assertEquals(2, s.length);
        assertEquals("a", s[0]);
        if (TestUtil.haveMinimumServerVersion(conn, "8.2")) {
            assertNull(s[1]);
        } else {
            assertEquals("NULL", s[1]);
        }
    }

    public void testEscaping() throws SQLException
    {
        Statement stmt = conn.createStatement();
        String sql = "SELECT ";
        if (TestUtil.haveMinimumServerVersion(conn, "8.1")) {
            sql += 'E';
        }
        // Uggg.  Three levels of escaping: Java, string literal, array.
        sql += "'{{c\\\\\"d, ''}, {\"\\\\\\\\\",\"''\"}}'::text[]";

        ResultSet rs = stmt.executeQuery(sql);
        assertTrue(rs.next());

        Array arr = rs.getArray(1);
        String s[][] = (String[][])arr.getArray();
        assertEquals("c\"d", s[0][0]);
        assertEquals("'", s[0][1]);
        assertEquals("\\", s[1][0]);
        assertEquals("'", s[1][1]);

        ResultSet arrRS = arr.getResultSet();

        assertTrue(arrRS.next());
        Array a1 = arrRS.getArray(2);
        ResultSet rs1 = a1.getResultSet();
        assertTrue(rs1.next());
        assertEquals("c\"d", rs1.getString(2));
        assertTrue(rs1.next());
        assertEquals("'", rs1.getString(2));
        assertTrue(!rs1.next());

        assertTrue(arrRS.next());
        Array a2 = arrRS.getArray(2);
        ResultSet rs2 = a2.getResultSet();
        assertTrue(rs2.next());
        assertEquals("\\", rs2.getString(2));
        assertTrue(rs2.next());
        assertEquals("'", rs2.getString(2));
        assertTrue(!rs2.next());
    }

    public void testWriteMultiDimensional() throws SQLException
    {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT '{{1,2},{3,4}}'::int[]");
        assertTrue(rs.next());
        Array arr = rs.getArray(1);
        rs.close();
        stmt.close();

        String sql = "SELECT ?";
        if (TestUtil.isProtocolVersion(conn, 2)) {
            sql = "SELECT ?::int[]";
        }
        PreparedStatement pstmt = conn.prepareStatement(sql);
        pstmt.setArray(1, arr);
        rs = pstmt.executeQuery();
        assertTrue(rs.next());
        arr = rs.getArray(1);

        Integer i[][] = (Integer[][])arr.getArray();
        assertEquals(1, i[0][0].intValue());
        assertEquals(2, i[0][1].intValue());
        assertEquals(3, i[1][0].intValue());
        assertEquals(4, i[1][1].intValue());
    }

    /*
     * The box data type uses a semicolon as the array element
     * delimiter instead of a comma which pretty much everything
     * else uses.
     */
    public void testNonStandardDelimiter() throws SQLException
    {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT '{(3,4),(1,2);(7,8),(5,6)}'::box[]");
        assertTrue(rs.next());
        Array arr = rs.getArray(1);

        ResultSet arrRS = arr.getResultSet();

        assertTrue(arrRS.next());
        PGbox box1 = (PGbox)arrRS.getObject(2);
        PGpoint p1 = box1.point[0];
        assertEquals(3, p1.x, 0.001);
        assertEquals(4, p1.y, 0.001);

        assertTrue(arrRS.next());
        PGbox box2 = (PGbox)arrRS.getObject(2);
        PGpoint p2 = box2.point[1];
        assertEquals(5, p2.x, 0.001);
        assertEquals(6, p2.y, 0.001);

        assertTrue(!arrRS.next());
    }

}

