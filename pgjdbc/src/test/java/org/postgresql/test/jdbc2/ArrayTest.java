/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGpoint;
import org.postgresql.jdbc.PgArray;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;

@ParameterizedClass
@MethodSource("data")
public class ArrayTest extends BaseTest4 {
  private Connection conn;

  public ArrayTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
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
    final String stringWithNonAsciiWhiteSpace = "a\u2001b";
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");
    pstmt.setObject(1, new int[]{1, 2, 3}, Types.ARRAY);
    pstmt.setObject(2, new double[]{3.1d, 1.4d}, Types.ARRAY);
    pstmt.setObject(3, new String[]{stringWithNonAsciiWhiteSpace, "f'a", " \tfa\"b  "}, Types.ARRAY);
    pstmt.executeUpdate();
    pstmt.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
    assertTrue(rs.next());

    Array arr = rs.getArray(1);
    assertEquals(Types.INTEGER, arr.getBaseType());
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
    assertEquals(" \tfa\"b  ", strarr[1]);

    strarr = (String[]) arr.getArray();
    assertEquals(stringWithNonAsciiWhiteSpace, strarr[0]);

    rs.close();
  }

  @Test
  public void testIndexAccess() throws SQLException {
    final int[][][] origIntArray = new int[2][2][2];
    final double[][][] origDblArray = new double[2][2][2];
    final String[][][] origStringArray = new String[2][2][2];
    final Object[][][] origIntObjArray = new Object[2][2][2];
    final Object[][][] origDblObjArray = new Object[2][2][2];
    final Object[][][] origStringObjArray = new Object[2][2][2];
    int i = 0;
    for (int x = 0; x < 2; x++) {
      for (int y = 0; y < 2; y++) {
        for (int z = 0; z < 2; z++) {
          origIntArray[x][y][z] = i;
          origDblArray[x][y][z] = i / 10;
          origStringArray[x][y][z] = Integer.toString(i);
          origIntObjArray[x][y][z] = i;
          origDblObjArray[x][y][z] = i / 10;
          origStringObjArray[x][y][z] = Integer.toString(i);
          i++;
        }
      }
    }
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");
    pstmt.setObject(1, origIntArray[0][0], Types.ARRAY);
    pstmt.setObject(2, origDblArray[0][0], Types.ARRAY);
    pstmt.setObject(3, origStringArray[0][0], Types.ARRAY);
    pstmt.executeUpdate();
    pstmt.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT intarr[1], decarr[1], strarr[1] FROM arrtest");
    assertTrue(rs.next());

    assertEquals(origIntArray[0][0][0], rs.getInt(1));
    assertEquals(origDblArray[0][0][0], rs.getDouble(2), 0.001);
    assertEquals(origStringArray[0][0][0], rs.getString(3));
    rs.close();
    stmt.close();

    pstmt = conn.prepareStatement("delete from arrtest");
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");
    pstmt.setObject(1, conn.createArrayOf("int4", origIntObjArray[0][0]), Types.ARRAY);
    pstmt.setObject(2, conn.createArrayOf("float8", origDblObjArray[0][0]), Types.ARRAY);
    pstmt.setObject(3, conn.createArrayOf("varchar", origStringObjArray[0][0]), Types.ARRAY);
    pstmt.executeUpdate();
    pstmt.close();

    stmt = conn.createStatement();
    rs = stmt.executeQuery("SELECT intarr[1], decarr[1], strarr[1] FROM arrtest");
    assertTrue(rs.next());

    assertEquals(origIntArray[0][0][0], rs.getInt(1));
    assertEquals(origDblArray[0][0][0], rs.getDouble(2), 0.001);
    assertEquals(origStringArray[0][0][0], rs.getString(3));
    rs.close();
    stmt.close();

    pstmt = conn.prepareStatement("delete from arrtest");
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");
    pstmt.setObject(1, conn.createArrayOf("int4", origIntArray[0]), Types.ARRAY);
    pstmt.setObject(2, conn.createArrayOf("float8", origDblArray[0]), Types.ARRAY);
    pstmt.setObject(3, conn.createArrayOf("varchar", origStringArray[0]), Types.ARRAY);
    pstmt.executeUpdate();
    pstmt.close();

    stmt = conn.createStatement();
    rs = stmt.executeQuery("SELECT intarr[1][1], decarr[1][1], strarr[1][1], intarr[2][1], decarr[2][1], strarr[2][1] FROM arrtest");
    assertTrue(rs.next());

    assertEquals(origIntArray[0][0][0], rs.getInt(1));
    assertEquals(origDblArray[0][0][0], rs.getDouble(2), 0.001);
    assertEquals(origStringArray[0][0][0], rs.getString(3));
    assertEquals(origIntArray[0][1][0], rs.getInt(4));
    assertEquals(origDblArray[0][1][0], rs.getDouble(5), 0.001);
    assertEquals(origStringArray[0][1][0], rs.getString(6));
    rs.close();
    stmt.close();

    pstmt = conn.prepareStatement("delete from arrtest");
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");
    pstmt.setObject(1, conn.createArrayOf("int4", origIntObjArray[0]), Types.ARRAY);
    pstmt.setObject(2, conn.createArrayOf("float8", origDblObjArray[0]), Types.ARRAY);
    pstmt.setObject(3, conn.createArrayOf("varchar", origStringObjArray[0]), Types.ARRAY);
    pstmt.executeUpdate();
    pstmt.close();

    stmt = conn.createStatement();
    rs = stmt.executeQuery("SELECT intarr[1][1], decarr[1][1], strarr[1][1], intarr[2][1], decarr[2][1], strarr[2][1] FROM arrtest");
    assertTrue(rs.next());

    assertEquals(origIntArray[0][0][0], rs.getInt(1));
    assertEquals(origDblArray[0][0][0], rs.getDouble(2), 0.001);
    assertEquals(origStringArray[0][0][0], rs.getString(3));
    assertEquals(origIntArray[0][1][0], rs.getInt(4));
    assertEquals(origDblArray[0][1][0], rs.getDouble(5), 0.001);
    assertEquals(origStringArray[0][1][0], rs.getString(6));
    rs.close();
    stmt.close();

    pstmt = conn.prepareStatement("delete from arrtest");
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");

    pstmt.setObject(1, conn.createArrayOf("int4", origIntArray), Types.ARRAY);
    pstmt.setObject(2, conn.createArrayOf("float8", origDblArray), Types.ARRAY);
    pstmt.setObject(3, conn.createArrayOf("varchar", origStringArray), Types.ARRAY);
    pstmt.executeUpdate();
    pstmt.close();

    stmt = conn.createStatement();
    rs = stmt.executeQuery("SELECT intarr[1][1][1], decarr[1][1][1], strarr[1][1][1], intarr[2][1][1], decarr[2][1][1], strarr[2][1][1] FROM arrtest");
    assertTrue(rs.next());

    assertEquals(origIntArray[0][0][0], rs.getInt(1));
    assertEquals(origDblArray[0][0][0], rs.getDouble(2), 0.001);
    assertEquals(origStringArray[0][0][0], rs.getString(3));
    assertEquals(origIntArray[1][0][0], rs.getInt(4));
    assertEquals(origDblArray[1][0][0], rs.getDouble(5), 0.001);
    assertEquals(origStringArray[1][0][0], rs.getString(6));
    rs.close();
    stmt.close();

    pstmt = conn.prepareStatement("delete from arrtest");
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");

    pstmt.setObject(1, conn.createArrayOf("int4", origIntObjArray), Types.ARRAY);
    pstmt.setObject(2, conn.createArrayOf("float8", origDblObjArray), Types.ARRAY);
    pstmt.setObject(3, conn.createArrayOf("varchar", origStringObjArray), Types.ARRAY);
    pstmt.executeUpdate();
    pstmt.close();

    stmt = conn.createStatement();
    rs = stmt.executeQuery("SELECT intarr[1][1][1], decarr[1][1][1], strarr[1][1][1], intarr[2][1][1], decarr[2][1][1], strarr[2][1][1] FROM arrtest");
    assertTrue(rs.next());

    assertEquals(origIntArray[0][0][0], rs.getInt(1));
    assertEquals(origDblArray[0][0][0], rs.getDouble(2), 0.001);
    assertEquals(origStringArray[0][0][0], rs.getString(3));
    assertEquals(origIntArray[1][0][0], rs.getInt(4));
    assertEquals(origDblArray[1][0][0], rs.getDouble(5), 0.001);
    assertEquals(origStringArray[1][0][0], rs.getString(6));
    rs.close();
    stmt.close();
  }

  @Test
  public void testSetPrimitiveArraysObjects() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO arrtest VALUES (?,?,?)");

    final PGConnection arraySupport = conn.unwrap(PGConnection.class);

    pstmt.setArray(1, arraySupport.createArrayOf("int4", new int[]{1, 2, 3}));
    pstmt.setObject(2, arraySupport.createArrayOf("float8", new double[]{3.1d, 1.4d}));
    pstmt.setObject(3, arraySupport.createArrayOf("varchar", new String[]{"abc", "f'a", "fa\"b"}));

    pstmt.executeUpdate();
    pstmt.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
    assertTrue(rs.next());

    Array arr = rs.getArray(1);
    assertEquals(Types.INTEGER, arr.getBaseType());
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

    try {
      arraySupport.createArrayOf("int4", 1);
      fail("not an array");
    } catch (PSQLException e) {

    }

    rs.close();
  }

  @Test
  public void testSetArraysWithAnsiTypeNames() throws SQLException {
    try {
      TestUtil.createTable(
          conn,
          "ansiarraytest",
          "floats double precision[], "
              + "reals real[], "
              + "varchars character varying(8)[], "
              + "times time without time zone[], "
              + "timestamps timestamp without time zone[], "
              + "timestampstz timestamp with time zone[]");

      PreparedStatement pstmt =
          conn.prepareStatement("INSERT INTO ansiarraytest VALUES (?,?,?,?,?,?)");

      final PGConnection arraySupport = conn.unwrap(PGConnection.class);

      pstmt.setArray(1, arraySupport.createArrayOf("double precision", new Object[]{1d, 4d}));
      pstmt.setArray(2, arraySupport.createArrayOf("real", new Object[]{0f, 3f}));
      pstmt.setObject(
          3, arraySupport.createArrayOf("character varying", new String[]{"abc", "f'a", "fa\"b"}));
      pstmt.setObject(
          4,
          arraySupport.createArrayOf(
              "time without time zone",
              new Object[]{Time.valueOf("12:34:56"), Time.valueOf("03:30:25")}));
      pstmt.setObject(
          5,
          arraySupport.createArrayOf(
              "timestamp without time zone",
              new Object[]{"2023-09-05 16:21:50", "2012-01-01 13:02:03"}));
      pstmt.setObject(
          6,
          arraySupport.createArrayOf(
              "timestamp with time zone",
              new Object[]{"1996-01-23 12:00:00-08", "1997-08-16 16:51:00-04"}));

      pstmt.executeUpdate();
      pstmt.close();

      Statement stmt = conn.createStatement();
      ResultSet rs =
          stmt.executeQuery(
              "SELECT floats, reals, varchars, times, timestamps, timestampstz FROM ansiarraytest");
      assertTrue(rs.next());

      Array arr = rs.getArray(1);
      assertEquals(Types.DOUBLE, arr.getBaseType());
      Double[] doubles = (Double[]) arr.getArray();
      assertEquals(2, doubles.length);
      assertEquals(1d, doubles[0], 0);
      assertEquals(4d, doubles[1], 0);

      arr = rs.getArray(2);
      assertEquals(Types.REAL, arr.getBaseType());
      Float[] floats = (Float[]) arr.getArray();
      assertEquals(2, floats.length);
      assertEquals(0f, floats[0], 0);
      assertEquals(3f, floats[1], 0);

      arr = rs.getArray(3);
      assertEquals(Types.VARCHAR, arr.getBaseType());
      String[] strings = (String[]) arr.getArray();
      assertEquals(3, strings.length);
      assertEquals("abc", strings[0]);
      assertEquals("f'a", strings[1]);
      assertEquals("fa\"b", strings[2]);

      arr = rs.getArray(4);
      assertEquals(Types.TIME, arr.getBaseType());
      Time[] times = (Time[]) arr.getArray();
      assertEquals(2, times.length);
      assertEquals(Time.valueOf("12:34:56"), times[0]);
      assertEquals(Time.valueOf("03:30:25"), times[1]);

      arr = rs.getArray(5);
      assertEquals(Types.TIMESTAMP, arr.getBaseType());
      Timestamp[] tzarr = (Timestamp[]) arr.getArray();
      assertEquals(2, times.length);
      assertEquals(Timestamp.valueOf("2023-09-05 16:21:50"), tzarr[0]);
      assertEquals(Timestamp.valueOf("2012-01-01 13:02:03"), tzarr[1]);

      arr = rs.getArray(6);
      assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, arr.getBaseType());
      tzarr = (Timestamp[]) arr.getArray();
      assertEquals(2, times.length);
      assertEquals(822427200000L, tzarr[0].getTime());
      assertEquals(871764660000L, tzarr[1].getTime());

      rs.close();
    } finally {
      TestUtil.dropTable(conn, "ansiarraytest");
    }
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
    assertTrue(rs.next());

    Array arr = rs.getArray(1);
    assertNull(arr);

    arr = rs.getArray(2);
    assertNull(arr);

    arr = rs.getArray(3);
    assertNull(arr);

    rs.close();
  }

  @Test
  public void testRetrieveArrays() throws SQLException {
    Statement stmt = conn.createStatement();

    // you need a lot of backslashes to get a double quote in.
    stmt.executeUpdate("INSERT INTO arrtest VALUES ('{1,2,3}','{3.1,1.4}', '"
        + TestUtil.escapeString(conn, "{abc,f'a,\"fa\\\"b\",def, un  quot\u000B \u2001 \r}") + "')");

    ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
    assertTrue(rs.next());

    Array arr = rs.getArray(1);
    assertEquals(Types.INTEGER, arr.getBaseType());
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

    strarr = (String[]) arr.getArray();
    assertEquals(5, strarr.length);
    assertEquals("un  quot\u000B \u2001", strarr[4]);

    rs.close();
    stmt.close();
  }

  @Test
  public void testRetrieveResultSets() throws SQLException {
    Statement stmt = conn.createStatement();

    final String stringWithNonAsciiWhiteSpace = "a\u2001b";
    // you need a lot of backslashes to get a double quote in.
    stmt.executeUpdate("INSERT INTO arrtest VALUES ('{1,2,3}','{3.1,1.4}', '"
        + TestUtil.escapeString(conn, "{\"a\u2001b\",f'a,\"fa\\\"b\",def}") + "')");

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
    assertFalse(arrrs.next());
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
    assertFalse(arrrs.next());
    arrrs.close();

    arrrs = arr.getResultSet(1, 1);
    assertTrue(arrrs.next());
    assertEquals(1, arrrs.getInt(1));
    assertEquals(stringWithNonAsciiWhiteSpace, arrrs.getString(2));
    assertFalse(arrrs.next());
    arrrs.close();

    rs.close();
    stmt.close();
  }

  @Test
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
    while (rs.next()) {
      resultCount++;
      Array result = rs.getArray(1);
      assertEquals(Types.INTEGER, result.getBaseType());
      assertEquals("int4", result.getBaseTypeName());

      Integer[] intarr = (Integer[]) result.getArray();
      assertEquals(3, intarr.length);
      assertEquals(1, intarr[0].intValue());
      assertEquals(2, intarr[1].intValue());
      assertEquals(3, intarr[2].intValue());
    }
    assertEquals(3, resultCount);
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
    assertTrue(rs.next());
    Array result = rs.getArray(1);
    Integer[] intarr = (Integer[]) result.getArray();
    assertEquals(4, intarr.length);
    for (int i = 0; i < intarr.length; i++) {
      assertEquals(i, intarr[i].intValue());
    }
  }

  @Test
  public void testMultiDimensionalArray() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT '{{1,2},{3,4}}'::int[]");
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Object[] oa = (Object[]) arr.getArray();
    assertEquals(2, oa.length);
    Integer[] i0 = (Integer[]) oa[0];
    assertEquals(2, i0.length);
    assertEquals(1, i0[0].intValue());
    assertEquals(2, i0[1].intValue());
    Integer[] i1 = (Integer[]) oa[1];
    assertEquals(2, i1.length);
    assertEquals(3, i1[0].intValue());
    assertEquals(4, i1[1].intValue());
    rs.close();
    stmt.close();
  }

  @Test
  public void testNullValues() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT ARRAY[1,NULL,3]");
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Integer[] i = (Integer[]) arr.getArray();
    assertEquals(3, i.length);
    assertEquals(1, i[0].intValue());
    assertNull(i[1]);
    assertEquals(3, i[2].intValue());
  }

  @Test
  public void testNullFieldString() throws SQLException {
    Array arr = new PgArray((BaseConnection) conn, 1, (String) null);
    assertNull(arr.toString());
  }

  @Test
  public void testDirectFieldString() throws SQLException {
    Array arr = new PgArray((BaseConnection) conn, Oid.VARCHAR_ARRAY,
        "{\" lead\t\",  unquot\u000B \u2001 \r, \" \fnew \n \"\t, \f\" \" }");
    final String[] array = (String[]) arr.getArray();
    assertEquals(4, array.length);
    assertEquals(" lead\t", array[0]);
    assertEquals(" \fnew \n ", array[2]);
    assertEquals(" ", array[3]);

    // PostgreSQL drops leading and trailing whitespace, so does the driver
    assertEquals("unquot\u2001", array[1]);
  }

  @Test
  public void testStringEscaping() throws SQLException {

    final String stringArray = "{f'a,\"fa\\\"b\",def, un  quot\u000B \u2001 \r, someString }";

    final Statement stmt = conn.createStatement();
    try {

      stmt.executeUpdate("INSERT INTO arrtest VALUES (NULL, NULL, '" + TestUtil.escapeString(conn, stringArray) + "')");

      final ResultSet rs = stmt.executeQuery("SELECT strarr FROM arrtest");
      assertTrue(rs.next());

      Array arr = rs.getArray(1);
      assertEquals(Types.VARCHAR, arr.getBaseType());
      String[] strarr = (String[]) arr.getArray();
      assertEquals(5, strarr.length);
      assertEquals("f'a", strarr[0]);
      assertEquals("fa\"b", strarr[1]);
      assertEquals("def", strarr[2]);
      assertEquals("un  quot\u000B \u2001", strarr[3]);
      assertEquals("someString", strarr[4]);

      rs.close();
    } finally {
      stmt.close();
    }

    final Array directArray = new PgArray((BaseConnection) conn, Oid.VARCHAR_ARRAY, stringArray);
    final String[] actual = (String[]) directArray.getArray();
    assertEquals(5, actual.length);
    assertEquals("f'a", actual[0]);
    assertEquals("fa\"b", actual[1]);
    assertEquals("def", actual[2]);
    assertEquals("someString", actual[4]);

    // the driver strips out ascii white spaces from an unescaped string, even in
    // the middle of the value. while this does not exactly match the behavior of
    // the backend, it will always quote values where ascii white spaces are
    // present, making this difference not worth the complexity involved addressing.
    assertEquals("unquot\u2001", actual[3]);
  }

  @Test
  public void testUnknownArrayType() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs =
        stmt.executeQuery("SELECT relacl FROM pg_class WHERE relacl IS NOT NULL LIMIT 1");
    ResultSetMetaData rsmd = rs.getMetaData();
    assertEquals(Types.ARRAY, rsmd.getColumnType(1));

    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    assertEquals("aclitem", arr.getBaseTypeName());

    ResultSet arrRS = arr.getResultSet();
    ResultSetMetaData arrRSMD = arrRS.getMetaData();
    assertEquals("aclitem", arrRSMD.getColumnTypeName(2));
  }

  @Test
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
    assertFalse(a1RS.next());
    a1RS.close();

    assertTrue(arrRS.next());
    assertEquals(2, arrRS.getInt(1));
    Array a2 = arrRS.getArray(2);
    ResultSet a2RS = a2.getResultSet();

    assertTrue(a2RS.next());
    assertEquals(3, a2RS.getInt(2));
    assertTrue(a2RS.next());
    assertEquals(4, a2RS.getInt(2));
    assertFalse(a2RS.next());
    a2RS.close();

    arrRS.close();
    rs.close();
    stmt.close();
  }

  @Test
  public void testNullString() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT '{a,NULL}'::text[]");
    assertTrue(rs.next());
    Array arr = rs.getArray(1);

    String[] s = (String[]) arr.getArray();
    assertEquals(2, s.length);
    assertEquals("a", s[0]);
    assertNull(s[1]);
  }

  @Test
  public void testEscaping() throws SQLException {
    Statement stmt = conn.createStatement();
    String sql = "SELECT ";
    sql += 'E';
    // Uggg. Three levels of escaping: Java, string literal, array.
    sql += "'{{c\\\\\"d, ''}, {\"\\\\\\\\\",\"''\"}}'::text[]";

    ResultSet rs = stmt.executeQuery(sql);
    assertTrue(rs.next());

    Array arr = rs.getArray(1);
    String[][] s = (String[][]) arr.getArray();
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
    assertFalse(rs1.next());

    assertTrue(arrRS.next());
    Array a2 = arrRS.getArray(2);
    ResultSet rs2 = a2.getResultSet();
    assertTrue(rs2.next());
    assertEquals("\\", rs2.getString(2));
    assertTrue(rs2.next());
    assertEquals("'", rs2.getString(2));
    assertFalse(rs2.next());
  }

  @Test
  public void testWriteMultiDimensional() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT '{{1,2},{3,4}}'::int[]");
    assertTrue(rs.next());
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
    assertTrue(rs.next());
    arr = rs.getArray(1);

    Integer[][] i = (Integer[][]) arr.getArray();
    assertEquals(1, i[0][0].intValue());
    assertEquals(2, i[0][1].intValue());
    assertEquals(3, i[1][0].intValue());
    assertEquals(4, i[1][1].intValue());
  }

  /*
   * The box data type uses a semicolon as the array element delimiter instead of a comma which
   * pretty much everything else uses.
   */
  @Test
  public void testNonStandardDelimiter() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT '{(3,4),(1,2);(7,8),(5,6)}'::box[]");
    assertTrue(rs.next());
    Array arr = rs.getArray(1);

    ResultSet arrRS = arr.getResultSet();

    assertTrue(arrRS.next());
    PGbox box1 = (PGbox) arrRS.getObject(2);
    PGpoint p1 = box1.point[0];
    assertEquals(3, p1.x, 0.001);
    assertEquals(4, p1.y, 0.001);

    assertTrue(arrRS.next());
    PGbox box2 = (PGbox) arrRS.getObject(2);
    PGpoint p2 = box2.point[1];
    assertEquals(5, p2.x, 0.001);
    assertEquals(6, p2.y, 0.001);

    assertFalse(arrRS.next());
  }

  @Test
  public void testEmptyArray() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{}'::int[]");
    ResultSet rs = pstmt.executeQuery();

    while (rs.next()) {
      Array array = rs.getArray(1);
      if (!rs.wasNull()) {
        ResultSet ars = array.getResultSet();
        assertEquals(
            Types.INTEGER,
            ars.getMetaData().getColumnType(1),
            "get columntype should return Types.INTEGER");
      }
    }
  }

}
