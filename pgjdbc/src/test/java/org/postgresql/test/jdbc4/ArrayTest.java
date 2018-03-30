/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import org.postgresql.core.ServerVersion;
import org.postgresql.geometric.PGbox;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PGobject;
import org.postgresql.util.PGtokenizer;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;

@RunWith(Parameterized.class)
public class ArrayTest extends BaseTest4 {

  private Connection _conn;

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
    _conn = con;

    TestUtil.createTable(_conn, "arrtest",
        "intarr int[], decarr decimal(2,1)[], strarr text[]"
        + (TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_3) ? ", uuidarr uuid[]" : "")
        + ", floatarr float8[]"
        + ", intarr2 int4[][]");
    TestUtil.createTable(_conn, "arrcompprnttest", "id serial, name character(10)");
    TestUtil.createTable(_conn, "arrcompchldttest",
        "id serial, name character(10), description character varying, parent integer");
    TestUtil.createTable(_conn, "\"CorrectCasing\"", "id serial");
    TestUtil.createTable(_conn, "\"Evil.Table\"", "id serial");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(_conn, "arrtest");
    TestUtil.dropTable(_conn, "arrcompprnttest");
    TestUtil.dropTable(_conn, "arrcompchldttest");
    TestUtil.dropTable(_conn, "\"CorrectCasing\"");
    super.tearDown();
  }

  @Test
  public void testCreateArrayOfBool() throws SQLException {
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::bool[]");
    pstmt.setArray(1, _conn.unwrap(PgConnection.class).createArrayOf("boolean", new boolean[] { true, true, false }));

    ResultSet rs = pstmt.executeQuery();
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Boolean[] out = (Boolean[]) arr.getArray();

    Assert.assertEquals(3, out.length);
    Assert.assertEquals(Boolean.TRUE, out[0]);
    Assert.assertEquals(Boolean.TRUE, out[1]);
    Assert.assertEquals(Boolean.FALSE, out[2]);
  }

  @Test
  public void testCreateArrayOfInt() throws SQLException {
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::int[]");
    Integer[] in = new Integer[3];
    in[0] = 0;
    in[1] = -1;
    in[2] = 2;
    pstmt.setArray(1, _conn.createArrayOf("int4", in));

    ResultSet rs = pstmt.executeQuery();
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Integer[] out = (Integer[]) arr.getArray();

    Assert.assertEquals(3, out.length);
    Assert.assertEquals(0, out[0].intValue());
    Assert.assertEquals(-1, out[1].intValue());
    Assert.assertEquals(2, out[2].intValue());
  }

  @Test
  public void testCreateArrayOfSmallInt() throws SQLException {
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::smallint[]");
    Short[] in = new Short[3];
    in[0] = 0;
    in[1] = -1;
    in[2] = 2;
    pstmt.setArray(1, _conn.createArrayOf("int2", in));

    ResultSet rs = pstmt.executeQuery();
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Short[] out = (Short[]) arr.getArray();

    Assert.assertEquals(3, out.length);
    Assert.assertEquals(0, out[0].shortValue());
    Assert.assertEquals(-1, out[1].shortValue());
    Assert.assertEquals(2, out[2].shortValue());
  }

  @Test
  public void testCreateArrayOfMultiString() throws SQLException {
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::text[]");
    String[][] in = new String[2][2];
    in[0][0] = "a";
    in[0][1] = "";
    in[1][0] = "\\";
    in[1][1] = "\"\\'z";
    pstmt.setArray(1, _conn.createArrayOf("text", in));

    ResultSet rs = pstmt.executeQuery();
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    String[][] out = (String[][]) arr.getArray();

    Assert.assertEquals(2, out.length);
    Assert.assertEquals(2, out[0].length);
    Assert.assertEquals("a", out[0][0]);
    Assert.assertEquals("", out[0][1]);
    Assert.assertEquals("\\", out[1][0]);
    Assert.assertEquals("\"\\'z", out[1][1]);
  }

  @Test
  public void testCreateArrayOfMultiJson() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v9_2)) {
      return;
    }
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::json[]");
    PGobject p1 = new PGobject();
    p1.setType("json");
    p1.setValue("{\"x\": 10}");

    PGobject p2 = new PGobject();
    p2.setType("json");
    p2.setValue("{\"x\": 20}");
    PGobject[] in = new PGobject[] { p1, p2 };
    pstmt.setArray(1, _conn.createArrayOf("json", in));

    ResultSet rs = pstmt.executeQuery();
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    ResultSet arrRs = arr.getResultSet();
    Assert.assertTrue(arrRs.next());
    Assert.assertEquals(in[0], arrRs.getObject(2));

    Assert.assertTrue(arrRs.next());
    Assert.assertEquals(in[1], arrRs.getObject(2));
  }

  @Test
  public void testCreateArrayWithNonStandardDelimiter() throws SQLException {
    PGbox[] in = new PGbox[2];
    in[0] = new PGbox(1, 2, 3, 4);
    in[1] = new PGbox(5, 6, 7, 8);

    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::box[]");
    pstmt.setArray(1, _conn.createArrayOf("box", in));
    ResultSet rs = pstmt.executeQuery();
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    ResultSet arrRs = arr.getResultSet();
    Assert.assertTrue(arrRs.next());
    Assert.assertEquals(in[0], arrRs.getObject(2));
    Assert.assertTrue(arrRs.next());
    Assert.assertEquals(in[1], arrRs.getObject(2));
    Assert.assertFalse(arrRs.next());
  }

  @Test
  public void testCreateArrayOfNull() throws SQLException {
    String sql = "SELECT ?";
    // We must provide the type information for V2 protocol
    if (preferQueryMode == PreferQueryMode.SIMPLE) {
      sql = "SELECT ?::int8[]";
    }

    PreparedStatement pstmt = _conn.prepareStatement(sql);
    String[] in = new String[2];
    in[0] = null;
    in[1] = null;
    pstmt.setArray(1, _conn.createArrayOf("int8", in));

    ResultSet rs = pstmt.executeQuery();
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Long[] out = (Long[]) arr.getArray();

    Assert.assertEquals(2, out.length);
    Assert.assertNull(out[0]);
    Assert.assertNull(out[1]);
  }

  @Test
  public void testCreateEmptyArrayOfIntViaAlias() throws SQLException {
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::int[]");
    Integer[] in = new Integer[0];
    pstmt.setArray(1, _conn.createArrayOf("integer", in));

    ResultSet rs = pstmt.executeQuery();
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Integer[] out = (Integer[]) arr.getArray();

    Assert.assertEquals(0, out.length);

    ResultSet arrRs = arr.getResultSet();
    Assert.assertFalse(arrRs.next());
  }

  @Test
  public void testCreateArrayWithoutServer() throws SQLException {
    String[][] in = new String[2][2];
    in[0][0] = "a";
    in[0][1] = "";
    in[1][0] = "\\";
    in[1][1] = "\"\\'z";

    Array arr = _conn.createArrayOf("varchar", in);
    String[][] out = (String[][]) arr.getArray();

    Assert.assertEquals(2, out.length);
    Assert.assertEquals(2, out[0].length);
    Assert.assertEquals("a", out[0][0]);
    Assert.assertEquals("", out[0][1]);
    Assert.assertEquals("\\", out[1][0]);
    Assert.assertEquals("\"\\'z", out[1][1]);
  }

  @Test
  public void testCreatePrimitiveArray() throws SQLException {
    double[][] in = new double[2][2];
    in[0][0] = 3.5;
    in[0][1] = -4.5;
    in[1][0] = 10.0 / 3;
    in[1][1] = 77;

    Array arr = _conn.createArrayOf("float8", in);
    Double[][] out = (Double[][]) arr.getArray();

    Assert.assertEquals(2, out.length);
    Assert.assertEquals(2, out[0].length);
    Assert.assertEquals(3.5, out[0][0], 0.00001);
    Assert.assertEquals(-4.5, out[0][1], 0.00001);
    Assert.assertEquals(10.0 / 3, out[1][0], 0.00001);
    Assert.assertEquals(77, out[1][1], 0.00001);
  }

  @Test
  public void testUUIDArray() throws SQLException {
    Assume.assumeTrue("UUID is not supported in PreferQueryMode.SIMPLE",
        preferQueryMode != PreferQueryMode.SIMPLE);
    Assume.assumeTrue("UUID requires PostgreSQL 8.3+",
        TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_3));
    UUID uuid1 = UUID.randomUUID();
    UUID uuid2 = UUID.randomUUID();
    UUID uuid3 = UUID.randomUUID();

    // insert a uuid array, and check
    PreparedStatement pstmt1 = _conn.prepareStatement("INSERT INTO arrtest(uuidarr) VALUES (?)");
    pstmt1.setArray(1, _conn.createArrayOf("uuid", new UUID[]{uuid1, uuid2, uuid3}));
    pstmt1.executeUpdate();

    PreparedStatement pstmt2 =
        _conn.prepareStatement("SELECT uuidarr FROM arrtest WHERE uuidarr @> ?");
    pstmt2.setObject(1, _conn.createArrayOf("uuid", new UUID[]{uuid1}), Types.OTHER);
    ResultSet rs = pstmt2.executeQuery();
    Assert.assertTrue(rs.next());
    Array arr = rs.getArray(1);
    UUID[] out = (UUID[]) arr.getArray();

    Assert.assertEquals(3, out.length);
    Assert.assertEquals(uuid1, out[0]);
    Assert.assertEquals(uuid2, out[1]);
    Assert.assertEquals(uuid3, out[2]);

    // concatenate a uuid, and check
    UUID uuid4 = UUID.randomUUID();
    PreparedStatement pstmt3 =
        _conn.prepareStatement("UPDATE arrtest SET uuidarr = uuidarr || ? WHERE uuidarr @> ?");
    pstmt3.setObject(1, uuid4, Types.OTHER);
    pstmt3.setArray(2, _conn.createArrayOf("uuid", new UUID[]{uuid1}));
    pstmt3.executeUpdate();

    // --
    pstmt2.setObject(1, _conn.createArrayOf("uuid", new UUID[]{uuid4}), Types.OTHER);
    rs = pstmt2.executeQuery();
    Assert.assertTrue(rs.next());
    arr = rs.getArray(1);
    out = (UUID[]) arr.getArray();

    Assert.assertEquals(4, out.length);
    Assert.assertEquals(uuid1, out[0]);
    Assert.assertEquals(uuid2, out[1]);
    Assert.assertEquals(uuid3, out[2]);
    Assert.assertEquals(uuid4, out[3]);
  }

  @Test
  public void testSetObjectFromJavaArray() throws SQLException {
    String[] strArray = new String[]{"a", "b", "c"};
    Object[] objCopy = Arrays.copyOf(strArray, strArray.length, Object[].class);

    PreparedStatement pstmt = _conn.prepareStatement("INSERT INTO arrtest(strarr) VALUES (?)");

    //cannot handle generic Object[]
    try {
      pstmt.setObject(1, objCopy, Types.ARRAY);
      pstmt.executeUpdate();
      Assert.fail("setObject() with a Java array parameter and Types.ARRAY shouldn't succeed");
    } catch (org.postgresql.util.PSQLException ex) {
      // Expected failure.
    }

    try {
      pstmt.setObject(1, objCopy);
      pstmt.executeUpdate();
      Assert.fail("setObject() with a Java array parameter and no Types argument shouldn't succeed");
    } catch (org.postgresql.util.PSQLException ex) {
      // Expected failure.
    }

    pstmt.setObject(1, strArray);
    pstmt.executeUpdate();

    pstmt.setObject(1, strArray, Types.ARRAY);
    pstmt.executeUpdate();

    // Correct way, though the use of "text" as a type is non-portable.
    // Only supported for JDK 1.6 and JDBC4
    Array sqlArray = _conn.createArrayOf("text", strArray);
    pstmt.setArray(1, sqlArray);
    pstmt.executeUpdate();

    pstmt.close();
  }

  @Test
  public void testGetArrayOfComposites() throws SQLException {
    Assume.assumeTrue("array_agg(expression) requires PostgreSQL 8.4+",
        TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_4));

    PreparedStatement insert_parent_pstmt =
        _conn.prepareStatement("INSERT INTO arrcompprnttest (name) "
            + "VALUES ('aParent');");
    insert_parent_pstmt.execute();

    String[] children = {
        "November 5, 2013",
        "\"A Book Title\"",
        "4\" by 6\"",
        "5\",3\""};

    PreparedStatement insert_children_pstmt =
        _conn.prepareStatement("INSERT INTO arrcompchldttest (name,description,parent) "
            + "VALUES ('child1',?,1),"
            + "('child2',?,1),"
            + "('child3',?,1),"
            + "('child4',?,1);");

    insert_children_pstmt.setString(1, children[0]);
    insert_children_pstmt.setString(2, children[1]);
    insert_children_pstmt.setString(3, children[2]);
    insert_children_pstmt.setString(4, children[3]);

    insert_children_pstmt.execute();

    PreparedStatement pstmt = _conn.prepareStatement(
        "SELECT arrcompprnttest.name, "
            + "array_agg("
            + "DISTINCT(arrcompchldttest.id, "
            + "arrcompchldttest.name, "
            + "arrcompchldttest.description)) "
            + "AS children "
            + "FROM arrcompprnttest "
            + "LEFT JOIN arrcompchldttest "
            + "ON (arrcompchldttest.parent = arrcompprnttest.id) "
            + "WHERE arrcompprnttest.id=? "
            + "GROUP BY arrcompprnttest.name;");
    pstmt.setInt(1, 1);
    ResultSet rs = pstmt.executeQuery();

    Assert.assertNotNull(rs);
    Assert.assertTrue(rs.next());

    Array childrenArray = rs.getArray("children");
    Assert.assertNotNull(childrenArray);

    ResultSet rsChildren = childrenArray.getResultSet();
    Assert.assertNotNull(rsChildren);
    while (rsChildren.next()) {
      String comp = rsChildren.getString(2);
      PGtokenizer token = new PGtokenizer(PGtokenizer.removePara(comp), ',');
      token.remove("\"", "\""); // remove surrounding double quotes
      if (2 < token.getSize()) {
        int childID = Integer.parseInt(token.getToken(0));
        // remove double quotes escaping with double quotes
        String value = token.getToken(2).replace("\"\"", "\"");
        Assert.assertEquals(children[childID - 1], value);
      } else {
        Assert.fail("Needs to have 3 tokens");
      }
    }
  }

  @Test
  public void testCasingComposite() throws SQLException {
    Assume.assumeTrue("Arrays of composite types requires PostgreSQL 8.3+",
        TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_3));

    PGobject cc = new PGobject();
    cc.setType("\"CorrectCasing\"");
    cc.setValue("(1)");
    Object[] in = new Object[1];
    in[0] = cc;

    Array arr = _conn.createArrayOf("\"CorrectCasing\"", in);
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::\"CorrectCasing\"[]");
    pstmt.setArray(1, arr);
    ResultSet rs = pstmt.executeQuery();

    Assert.assertTrue(rs.next());
    Object[] resArr = (Object[]) rs.getArray(1).getArray();

    Assert.assertTrue(resArr[0] instanceof PGobject);
    PGobject resObj = (PGobject) resArr[0];
    Assert.assertEquals("(1)", resObj.getValue());
  }

  @Test
  public void testCasingBuiltinAlias() throws SQLException {
    Array arr = _conn.createArrayOf("INT", new Integer[]{1, 2, 3});
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::INT[]");
    pstmt.setArray(1, arr);
    ResultSet rs = pstmt.executeQuery();

    Assert.assertTrue(rs.next());
    Integer[] resArr = (Integer[]) rs.getArray(1).getArray();

    Assert.assertArrayEquals(new Integer[]{1, 2, 3}, resArr);
  }

  @Test
  public void testCasingBuiltinNonAlias() throws SQLException {
    Array arr = _conn.createArrayOf("INT4", new Integer[]{1, 2, 3});
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::INT4[]");
    pstmt.setArray(1, arr);
    ResultSet rs = pstmt.executeQuery();

    Assert.assertTrue(rs.next());
    Integer[] resArr = (Integer[]) rs.getArray(1).getArray();

    Assert.assertArrayEquals(new Integer[]{1, 2, 3}, resArr);
  }

  @Test
  public void testEvilCasing() throws SQLException {
    Assume.assumeTrue("Arrays of composite types requires PostgreSQL 8.3+",
        TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_3));

    PGobject cc = new PGobject();
    cc.setType("\"Evil.Table\"");
    cc.setValue("(1)");
    Object[] in = new Object[1];
    in[0] = cc;

    Array arr = _conn.createArrayOf("\"Evil.Table\"", in);
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?::\"Evil.Table\"[]");
    pstmt.setArray(1, arr);
    ResultSet rs = pstmt.executeQuery();

    Assert.assertTrue(rs.next());
    Object[] resArr = (Object[]) rs.getArray(1).getArray();

    Assert.assertTrue(resArr[0] instanceof PGobject);
    PGobject resObj = (PGobject) resArr[0];
    Assert.assertEquals("(1)", resObj.getValue());
  }

  @Test
  public void testToString() throws SQLException {
    Double[] d = new Double[4];

    d[0] = 3.5;
    d[1] = -4.5;
    d[2] = null;
    d[3] = 77.0;

    Array arr = con.createArrayOf("float8", d);
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO arrtest(floatarr) VALUES (?)");
    ResultSet rs = null;

    try {
      pstmt.setArray(1, arr);
      pstmt.execute();
    } finally {
      TestUtil.closeQuietly(pstmt);
    }

    Statement stmt = null;
    try {
      stmt = con.createStatement();

      rs = stmt.executeQuery("select floatarr from arrtest");

      while (rs.next()) {
        Array doubles = rs.getArray(1);
        String actual = doubles.toString();
        if (actual != null) {
          // Remove all double quotes. They do not make a difference here.
          actual = actual.replaceAll("\"", "");
          // Replace X.0 with just X
          actual = actual.replaceAll("\\.0+([^0-9])", "$1");
        }
        Assert.assertEquals("Array.toString should use square braces",
            "{3.5,-4.5,NULL,77}", actual);
      }

    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(stmt);
    }
  }

  @Test
  public void nullArray() throws SQLException {
    PreparedStatement ps = con.prepareStatement("INSERT INTO arrtest(floatarr) VALUES (?)");

    ps.setNull(1, Types.ARRAY, "float8[]");
    ps.execute();

    ps.close();
    ps = con.prepareStatement("select floatarr from arrtest");
    ResultSet rs = ps.executeQuery();
    Assert.assertTrue("arrtest should contain a row", rs.next());
    Array getArray = rs.getArray(1);
    Assert.assertNull("null array should return null value on getArray", getArray);
    Object getObject = rs.getObject(1);
    Assert.assertNull("null array should return null on getObject", getObject);
  }

  @Test
  public void createNullArray() throws SQLException {
    Array arr = con.createArrayOf("float8", null);
    Assert.assertNotNull(arr);
    Assert.assertNull(arr.getArray());
  }

  @Test
  public void multiDimIntArray() throws SQLException {
    Array arr = con.createArrayOf("int4", new int[][]{{1,2}, {3,4}});
    PreparedStatement ps = con.prepareStatement("select ?::int4[][]");
    ps.setArray(1, arr);
    ResultSet rs = ps.executeQuery();
    rs.next();
    Array resArray = rs.getArray(1);
    String stringValue = resArray.toString();
    // Both {{"1","2"},{"3","4"}} and {{1,2},{3,4}} are the same array representation
    stringValue = stringValue.replaceAll("\"", "");
    Assert.assertEquals("{{1,2},{3,4}}", stringValue);
    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(ps);
  }
}
