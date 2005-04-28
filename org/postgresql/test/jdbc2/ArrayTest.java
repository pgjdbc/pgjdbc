/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc2/ArrayTest.java,v 1.8 2005/01/11 08:25:48 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import java.sql.*;
import java.math.BigDecimal;
import java.util.Map;

import junit.framework.TestCase;

public class ArrayTest extends TestCase
{
    private Connection conn;

    public ArrayTest(String name)
    {
        super(name);
    }

    protected void setUp() throws SQLException
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
        stmt.executeUpdate("INSERT INTO arrtest VALUES ('{1,2,3}','{3.1,1.4}', '{abc,f''a,\"fa\\\\\"b\",def}')");

        ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
        assertTrue(rs.next());

        Array arr = rs.getArray(1);
        assertEquals(Types.INTEGER, arr.getBaseType());
        int intarr[] = (int[])arr.getArray();
        assertEquals(3, intarr.length);
        assertEquals(1, intarr[0]);
        assertEquals(2, intarr[1]);
        assertEquals(3, intarr[2]);

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
        stmt.executeUpdate("INSERT INTO arrtest VALUES ('{1,2,3}','{3.1,1.4}', '{abc,f''a,\"fa\\\\\"b\",def}')");

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
        pstmt.close();

        Statement select = conn.createStatement();
        ResultSet rs = select.executeQuery("SELECT intarr FROM arrtest");
        assertTrue(rs.next());

        Array result = rs.getArray(1);
        assertEquals(Types.INTEGER, result.getBaseType());
        assertEquals("int4", result.getBaseTypeName());

        int intarr[] = (int[])result.getArray();
        assertEquals(3, intarr.length);
        assertEquals(1, intarr[0]);
        assertEquals(2, intarr[1]);
        assertEquals(3, intarr[2]);
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
        int intarr[] = (int[])result.getArray();
        assertEquals(4, intarr.length);
        for (int i = 0; i < intarr.length; i++)
        {
            assertEquals(i, intarr[i]);
        }
    }
}

