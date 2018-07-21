/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGpoint;
import org.postgresql.jdbc.PgArray;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class ArrayTest extends BaseTest4 {
  private Connection conn;

  public ArrayTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    conn = con;
    TestUtil.createTable(conn, "arrtest", "intarr int[], decarr decimal(2,1)[], strarr text[]");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(conn, "arrtest");
    super.tearDown();
  }

  @Test
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

  @Test
  public void testSetPrimitiveObjects() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");
    pstmt.setObject(1, new int[]{1,2,3}, Types.ARRAY);
    pstmt.setObject(2, new double[]{3.1d, 1.4d}, Types.ARRAY);
    pstmt.setObject(3, new String[]{"abc", "f'a", "fa\"b"}, Types.ARRAY);
    pstmt.executeUpdate();
    pstmt.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
    Assert.assertTrue(rs.next());

    Array arr = rs.getArray(1);
    Assert.assertEquals(Types.INTEGER, arr.getBaseType());
    Integer[] intarr = (Integer[]) arr.getArray();
    assertEquals(3, intarr.length);
    assertEquals(1, intarr[0].intValue());
    assertEquals(2, intarr[1].intValue());
    assertEquals(3, intarr[2].intValue());

    arr = rs.getArray(2);
    assertEquals(Types.NUMERIC, arr.getBaseType());
    BigDecimal[] decarr = (BigDecimal[]) arr.getArray();
    assertEquals(2, decarr.length);
    assertEquals(new BigDecimal("3.1"), decarr[0]);
    assertEquals(new BigDecimal("1.4"), decarr[1]);

    arr = rs.getArray(3);
    assertEquals(Types.VARCHAR, arr.getBaseType());
    String[] strarr = (String[]) arr.getArray(2, 2);
    assertEquals(2, strarr.length);
    assertEquals("f'a", strarr[0]);
    assertEquals("fa\"b", strarr[1]);

    rs.close();
  }

  @Test
  public void testSetPrimitiveArraysObjects() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");

    final PGConnection arraySupport = conn.unwrap(PGConnection.class);

    pstmt.setArray(1, arraySupport.createArrayOf("int4", new int[] { 1, 2, 3 }));
    pstmt.setObject(2, arraySupport.createArrayOf("float8", new double[] { 3.1d, 1.4d }));
    pstmt.setObject(3, arraySupport.createArrayOf("varchar", new String[] { "abc", "f'a", "fa\"b" }));

    pstmt.executeUpdate();
    pstmt.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
    Assert.assertTrue(rs.next());

    Array arr = rs.getArray(1);
    Assert.assertEquals(Types.INTEGER, arr.getBaseType());
    Integer[] intarr = (Integer[]) arr.getArray();
    Assert.assertEquals(3, intarr.length);
    Assert.assertEquals(1, intarr[0].intValue());
    Assert.assertEquals(2, intarr[1].intValue());
    Assert.assertEquals(3, intarr[2].intValue());

    arr = rs.getArray(2);
    Assert.assertEquals(Types.NUMERIC, arr.getBaseType());
    BigDecimal[] decarr = (BigDecimal[]) arr.getArray();
    Assert.assertEquals(2, decarr.length);
    Assert.assertEquals(new BigDecimal("3.1"), decarr[0]);
    Assert.assertEquals(new BigDecimal("1.4"), decarr[1]);

    arr = rs.getArray(3);
    Assert.assertEquals(Types.VARCHAR, arr.getBaseType());
    String[] strarr = (String[]) arr.getArray(2, 2);
    Assert.assertEquals(2, strarr.length);
    Assert.assertEquals("f'a", strarr[0]);
    Assert.assertEquals("fa\"b", strarr[1]);

    try {
      arraySupport.createArrayOf("int4", Integer.valueOf(1));
      fail("not an array");
    } catch (PSQLException e) {

    }

    rs.close();
  }

  @Test
  public void testSetNullArrays() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");

    final PGConnection arraySupport = conn.unwrap(PGConnection.class);

    pstmt.setArray(1, arraySupport.createArrayOf("int4", null));
    pstmt.setObject(2, conn.createArrayOf("float8", null));
    pstmt.setObject(3, arraySupport.createArrayOf("varchar", null));

    pstmt.executeUpdate();
    pstmt.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
    Assert.assertTrue(rs.next());

    Array arr = rs.getArray(1);
    Assert.assertNull(arr);

    arr = rs.getArray(2);
    Assert.assertNull(arr);

    arr = rs.getArray(3);
    Assert.assertNull(arr);

    rs.close();
  }

  @Test
  public void testRetrieveArrays() throws SQLException {
    Statement stmt = conn.createStatement();

    // you need a lot of backslashes to get a double quote in.
    stmt.executeUpdate("INSERT INTO arrtest VALUES ('{1,2,3}','{3.1,1.4}', '"
        + TestUtil.escapeString(conn, "{abc,f'a,\"fa\\\"b\",def}") + "')");

    ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
    Assert.assertTrue(rs.next());

    Array arr = rs.getArray(1);
    Assert.assertEquals(Types.INTEGER, arr.getBaseType());
    Integer[] intarr = (Integer[]) arr.getArray();
    Assert.assertEquals(3, intarr.length);
    Assert.assertEquals(1, intarr[0].intValue());
    Assert.assertEquals(2, intarr[1].intValue());
    Assert.assertEquals(3, intarr[2].intValue());

    arr = rs.getArray(2);
    Assert.assertEquals(Types.NUMERIC, arr.getBaseType());
    BigDecimal[] decarr = (BigDecimal[]) arr.getArray();
    Assert.assertEquals(2, decarr.length);
    Assert.assertEquals(new BigDecimal("3.1"), decarr[0]);
    Assert.assertEquals(new BigDecimal("1.4"), decarr[1]);

    arr = rs.getArray(3);
    Assert.assertEquals(Types.VARCHAR, arr.getBaseType());
    String[] strarr = (String[]) arr.getArray(2, 2);
    Assert.assertEquals(2, strarr.length);
    Assert.assertEquals("f'a", strarr[0]);
    Assert.assertEquals("fa\"b", strarr[1]);

    rs.close();
    stmt.close();
  }

  @Test
  public void testRetrieveResultSets() throws SQLException {
    Statement stmt = conn.createStatement();

    // you need a lot of backslashes to get a double quote in.
    stmt.executeUpdate("INSERT INTO arrtest VALUES ('{1,2,3}','{3.1,1.4}', '"
        + TestUtil.escapeString(conn, "{abc,f'a,\"fa\\\"b\",def}") + "')");

    ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
    Assert.assertTrue(rs.next());

    Array arr = rs.getArray(1);
    Assert.assertEquals(Types.INTEGER, arr.getBaseType());
    ResultSet arrrs = arr.getResultSet();
    Assert.assertTrue(arrrs.next());
    Assert.assertEquals(1, arrrs.getInt(1));
    Assert.assertEquals(1, arrrs.getInt(2));
    Assert.assertTrue(arrrs.next());
    Assert.assertEquals(2, arrrs.getInt(1));
    Assert.assertEquals(2, arrrs.getInt(2));
    Assert.assertTrue(arrrs.next());
    Assert.assertEquals(3, arrrs.getInt(1));
    Assert.assertEquals(3, arrrs.getInt(2));
    Assert.assertTrue(!arrrs.next());
    Assert.assertTrue(arrrs.previous());
    Assert.assertEquals(3, arrrs.getInt(2));
    arrrs.first();
    Assert.assertEquals(1, arrrs.getInt(2));
    arrrs.close();

    arr = rs.getArray(2);
    Assert.assertEquals(Types.NUMERIC, arr.getBaseType());
    arrrs = arr.getResultSet();
    Assert.assertTrue(arrrs.next());
    Assert.assertEquals(new BigDecimal("3.1"), arrrs.getBigDecimal(2));
    Assert.assertTrue(arrrs.next());
    Assert.assertEquals(new BigDecimal("1.4"), arrrs.getBigDecimal(2));
    arrrs.close();

    arr = rs.getArray(3);
    Assert.assertEquals(Types.VARCHAR, arr.getBaseType());
    arrrs = arr.getResultSet(2, 2);
    Assert.assertTrue(arrrs.next());
    Assert.assertEquals(2, arrrs.getInt(1));
    Assert.assertEquals("f'a", arrrs.getString(2));
    Assert.assertTrue(arrrs.next());
    Assert.assertEquals(3, arrrs.getInt(1));
    Assert.assertEquals("fa\"b", arrrs.getString(2));
    Assert.assertTrue(!arrrs.next());
    arrrs.close();

    rs.close();
    stmt.close();
  }

  @Test
  public void testSetArray() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet arrRS = stmt.executeQuery("SELECT '{1,2,3}'::int4[]");
    Assert.assertTrue(arrRS.next());
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
    while (rs.next()) {
      resultCount++;
      Array result = rs.getArray(1);
      Assert.assertEquals(Types.INTEGER, result.getBaseType());
      Assert.assertEquals("int4", result.getBaseTypeName());

      Integer[] intarr = (Integer[]) result.getArray();
      Assert.assertEquals(3, intarr.length);
      Assert.assertEquals(1, intarr[0].intValue());
      Assert.assertEquals(2, intarr[1].intValue());
      Assert.assertEquals(3, intarr[2].intValue());
    }
    Assert.assertEquals(3, resultCount);
  }


  /**
   * Starting with 8.0 non-standard (beginning index isn't 1) bounds the dimensions are returned in
   * the data. The following should return "[0:3]={0,1,2,3,4}" when queried. Older versions simply
   * do not return the bounds.
   */
  @Test
  public void testNonStandardBounds() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("INSERT INTO arrtest (intarr) VALUES ('{1,2,3}')");
    stmt.executeUpdate("UPDATE arrtest SET intarr[0] = 0");
    ResultSet rs = stmt.executeQuery("SELECT intarr FROM arrtest");
    Assert.assertTrue(rs.next());
    Array result = rs.getArray(1);
    Integer[] intarr = (Integer[]) result.getArray();
    Assert.assertEquals(4, intarr.length);
    for (int i = 0; i < intarr.length; i++) {
      Assert.assertEquals(i, intarr[i].intValue());
    }
  }

  @Test
  public void testMultiDimensionalArray() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT '{{1,2},{3,4}}'::int[]");
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Object[] oa = (Object[]) arr.getArray();
    Assert.assertEquals(2, oa.length);
    Integer[] i0 = (Integer[]) oa[0];
    Assert.assertEquals(2, i0.length);
    Assert.assertEquals(1, i0[0].intValue());
    Assert.assertEquals(2, i0[1].intValue());
    Integer[] i1 = (Integer[]) oa[1];
    Assert.assertEquals(2, i1.length);
    Assert.assertEquals(3, i1[0].intValue());
    Assert.assertEquals(4, i1[1].intValue());
    rs.close();
    stmt.close();
  }

  @Test
  public void testNullValues() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT ARRAY[1,NULL,3]");
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Integer[] i = (Integer[]) arr.getArray();
    Assert.assertEquals(3, i.length);
    Assert.assertEquals(1, i[0].intValue());
    Assert.assertNull(i[1]);
    Assert.assertEquals(3, i[2].intValue());
  }

  @Test
  public void testNullFieldString() throws SQLException {
    Array arr = new PgArray((BaseConnection) conn, 1, (String) null);
    Assert.assertNull(arr.toString());
  }

  @Test
  public void testUnknownArrayType() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs =
        stmt.executeQuery("SELECT relacl FROM pg_class WHERE relacl IS NOT NULL LIMIT 1");
    ResultSetMetaData rsmd = rs.getMetaData();
    Assert.assertEquals(Types.ARRAY, rsmd.getColumnType(1));

    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Assert.assertEquals("aclitem", arr.getBaseTypeName());

    ResultSet arrRS = arr.getResultSet();
    ResultSetMetaData arrRSMD = arrRS.getMetaData();
    Assert.assertEquals("aclitem", arrRSMD.getColumnTypeName(2));
  }

  @Test
  public void testRecursiveResultSets() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT '{{1,2},{3,4}}'::int[]");
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);

    ResultSet arrRS = arr.getResultSet();
    ResultSetMetaData arrRSMD = arrRS.getMetaData();
    Assert.assertEquals(Types.ARRAY, arrRSMD.getColumnType(2));
    Assert.assertEquals("_int4", arrRSMD.getColumnTypeName(2));

    Assert.assertTrue(arrRS.next());
    Assert.assertEquals(1, arrRS.getInt(1));
    Array a1 = arrRS.getArray(2);
    ResultSet a1RS = a1.getResultSet();
    ResultSetMetaData a1RSMD = a1RS.getMetaData();
    Assert.assertEquals(Types.INTEGER, a1RSMD.getColumnType(2));
    Assert.assertEquals("int4", a1RSMD.getColumnTypeName(2));

    Assert.assertTrue(a1RS.next());
    Assert.assertEquals(1, a1RS.getInt(2));
    Assert.assertTrue(a1RS.next());
    Assert.assertEquals(2, a1RS.getInt(2));
    Assert.assertTrue(!a1RS.next());
    a1RS.close();

    Assert.assertTrue(arrRS.next());
    Assert.assertEquals(2, arrRS.getInt(1));
    Array a2 = arrRS.getArray(2);
    ResultSet a2RS = a2.getResultSet();

    Assert.assertTrue(a2RS.next());
    Assert.assertEquals(3, a2RS.getInt(2));
    Assert.assertTrue(a2RS.next());
    Assert.assertEquals(4, a2RS.getInt(2));
    Assert.assertTrue(!a2RS.next());
    a2RS.close();

    arrRS.close();
    rs.close();
    stmt.close();
  }

  @Test
  public void testNullString() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT '{a,NULL}'::text[]");
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);

    String[] s = (String[]) arr.getArray();
    Assert.assertEquals(2, s.length);
    Assert.assertEquals("a", s[0]);
    Assert.assertNull(s[1]);
  }

  @Test
  public void testEscaping() throws SQLException {
    Statement stmt = conn.createStatement();
    String sql = "SELECT ";
    sql += 'E';
    // Uggg. Three levels of escaping: Java, string literal, array.
    sql += "'{{c\\\\\"d, ''}, {\"\\\\\\\\\",\"''\"}}'::text[]";

    ResultSet rs = stmt.executeQuery(sql);
    Assert.assertTrue(rs.next());

    Array arr = rs.getArray(1);
    String[][] s = (String[][]) arr.getArray();
    Assert.assertEquals("c\"d", s[0][0]);
    Assert.assertEquals("'", s[0][1]);
    Assert.assertEquals("\\", s[1][0]);
    Assert.assertEquals("'", s[1][1]);

    ResultSet arrRS = arr.getResultSet();

    Assert.assertTrue(arrRS.next());
    Array a1 = arrRS.getArray(2);
    ResultSet rs1 = a1.getResultSet();
    Assert.assertTrue(rs1.next());
    Assert.assertEquals("c\"d", rs1.getString(2));
    Assert.assertTrue(rs1.next());
    Assert.assertEquals("'", rs1.getString(2));
    Assert.assertTrue(!rs1.next());

    Assert.assertTrue(arrRS.next());
    Array a2 = arrRS.getArray(2);
    ResultSet rs2 = a2.getResultSet();
    Assert.assertTrue(rs2.next());
    Assert.assertEquals("\\", rs2.getString(2));
    Assert.assertTrue(rs2.next());
    Assert.assertEquals("'", rs2.getString(2));
    Assert.assertTrue(!rs2.next());
  }

  @Test
  public void testWriteMultiDimensional() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT '{{1,2},{3,4}}'::int[]");
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    rs.close();
    stmt.close();

    String sql = "SELECT ?";
    if (preferQueryMode == PreferQueryMode.SIMPLE) {
      sql = "SELECT ?::int[]";
    }
    PreparedStatement pstmt = conn.prepareStatement(sql);
    pstmt.setArray(1, arr);
    rs = pstmt.executeQuery();
    Assert.assertTrue(rs.next());
    arr = rs.getArray(1);

    Integer[][] i = (Integer[][]) arr.getArray();
    Assert.assertEquals(1, i[0][0].intValue());
    Assert.assertEquals(2, i[0][1].intValue());
    Assert.assertEquals(3, i[1][0].intValue());
    Assert.assertEquals(4, i[1][1].intValue());
  }

  /*
   * The box data type uses a semicolon as the array element delimiter instead of a comma which
   * pretty much everything else uses.
   */
  @Test
  public void testNonStandardDelimiter() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT '{(3,4),(1,2);(7,8),(5,6)}'::box[]");
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);

    ResultSet arrRS = arr.getResultSet();

    Assert.assertTrue(arrRS.next());
    PGbox box1 = (PGbox) arrRS.getObject(2);
    PGpoint p1 = box1.point[0];
    Assert.assertEquals(3, p1.x, 0.001);
    Assert.assertEquals(4, p1.y, 0.001);

    Assert.assertTrue(arrRS.next());
    PGbox box2 = (PGbox) arrRS.getObject(2);
    PGpoint p2 = box2.point[1];
    Assert.assertEquals(5, p2.x, 0.001);
    Assert.assertEquals(6, p2.y, 0.001);

    Assert.assertTrue(!arrRS.next());
  }

  @Test
  public void testEmptyArray() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{}'::int[]");
    ResultSet rs = pstmt.executeQuery();

    while (rs.next()) {
      Array array = rs.getArray(1);
      if (!rs.wasNull()) {
        ResultSet ars = array.getResultSet();
        Assert.assertEquals("get columntype should return Types.INTEGER", java.sql.Types.INTEGER,
            ars.getMetaData().getColumnType(1));
      }
    }
  }

}

