/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.geometric.PGbox;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.test.util.RegexMatcher;
import org.postgresql.util.PGobject;
import org.postgresql.util.PGtokenizer;

import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

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

  @BeforeAll
  static void createTables() throws Exception {
    try (Connection conn = TestUtil.openDB()) {
      TestUtil.createTable(conn, "arrtest",
          "intarr int[], decarr decimal(2,1)[], strarr text[]"
          + (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v8_3) ? ", uuidarr uuid[]" : "")
          + ", floatarr float8[]"
          + ", intarr2 int4[][]");
      TestUtil.createTable(conn, "arrcompprnttest", "id serial, name character(10)");
      TestUtil.createTable(conn, "arrcompchldttest",
          "id serial, name character(10), description character varying, parent integer");
      TestUtil.createTable(conn, "\"CorrectCasing\"", "id serial");
      TestUtil.createTable(conn, "\"Evil.Table\"", "id serial");
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection conn = TestUtil.openDB()) {
      TestUtil.dropTable(conn, "arrtest");
      TestUtil.dropTable(conn, "arrcompprnttest");
      TestUtil.dropTable(conn, "arrcompchldttest");
      TestUtil.dropTable(conn, "\"CorrectCasing\"");
      TestUtil.dropTable(conn, "\"Evil.Table\"");
    }
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    conn = con;

    TestUtil.execute(conn, "TRUNCATE arrtest CASCADE");
    TestUtil.execute(conn, "TRUNCATE arrcompprnttest RESTART IDENTITY CASCADE");
    TestUtil.execute(conn, "TRUNCATE arrcompchldttest RESTART IDENTITY CASCADE");
  }

  @Test
  public void testCreateArrayOfBool() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::bool[]");
    pstmt.setArray(1, conn.unwrap(PgConnection.class).createArrayOf("boolean", new boolean[]{true, true, false}));

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Boolean[] out = (Boolean[]) arr.getArray();

    assertEquals(3, out.length);
    assertEquals(Boolean.TRUE, out[0]);
    assertEquals(Boolean.TRUE, out[1]);
    assertEquals(Boolean.FALSE, out[2]);
  }

  /**
   * Typed {@code getObject(col, int[].class)} routes int4[] through the codec
   * (ArrayCodec.decodeBinaryAs / decodeTextAs and the int4 fast leaf). Running
   * under both binary modes exercises the binary and text walkers and asserts
   * they agree.
   */
  @Test
  public void testGetObjectIntArrayViaCodec() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{1,2,3}'::int4[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertArrayEquals(new int[]{1, 2, 3}, rs.getObject(1, int[].class));
    assertArrayEquals(new Integer[]{1, 2, 3}, rs.getObject(1, Integer[].class));
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetObjectIntegerArrayWithNullViaCodec() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{1,NULL,3}'::int4[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertArrayEquals(new Integer[]{1, null, 3}, rs.getObject(1, Integer[].class));
    // a primitive int[] cannot hold a NULL element
    assertThrows(SQLException.class, () -> rs.getObject(1, int[].class));
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetObjectIntArrayMultiDimViaCodec() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{{1,2},{3,4}}'::int4[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    int[][] m = rs.getObject(1, int[][].class);
    assertArrayEquals(new int[]{1, 2}, m[0]);
    assertArrayEquals(new int[]{3, 4}, m[1]);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetObjectIntArrayMatchesGetArray() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{10,20,30}'::int4[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Integer[] viaCodec = rs.getObject(1, Integer[].class);
    Integer[] viaGetArray = (Integer[]) rs.getArray(1).getArray();
    assertArrayEquals(viaGetArray, viaCodec);
    rs.close();
    pstmt.close();
  }

  /**
   * getArray() for int8[] / int4[] now decodes through the shared walker with the
   * element's fast leaf. These pin that it returns the same typed array the legacy
   * decoder did (Long[] / Integer[]) — across both binary and text modes.
   */
  @Test
  public void testGetArrayInt8ReturnsLongArray() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{1,2,3}'::int8[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object arr = rs.getArray(1).getArray();
    assertInstanceOf(Long[].class, arr);
    assertArrayEquals(new Long[]{1L, 2L, 3L}, (Long[]) arr);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayInt8WithNulls() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{1,NULL,3}'::int8[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Long[] arr = (Long[]) rs.getArray(1).getArray();
    assertArrayEquals(new Long[]{1L, null, 3L}, arr);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayInt8MultiDim() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{{1,2},{3,4}}'::int8[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Long[][] arr = (Long[][]) rs.getArray(1).getArray();
    assertArrayEquals(new Long[]{1L, 2L}, arr[0]);
    assertArrayEquals(new Long[]{3L, 4L}, arr[1]);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayInt4ReturnsIntegerArrayViaWalker() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{1,2,3}'::int4[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object arr = rs.getArray(1).getArray();
    assertInstanceOf(Integer[].class, arr);
    assertArrayEquals(new Integer[]{1, 2, 3}, (Integer[]) arr);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayInt2ReturnsShortArray() throws SQLException {
    // Legacy getArray() returned Short[] for int2[] (distinct from the scalar
    // codec's Integer default); the fast leaf must preserve that.
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{1,NULL,3}'::int2[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object arr = rs.getArray(1).getArray();
    assertInstanceOf(Short[].class, arr);
    assertArrayEquals(new Short[]{1, null, 3}, (Short[]) arr);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayBoolReturnsBooleanArray() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{t,NULL,f}'::bool[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object arr = rs.getArray(1).getArray();
    assertInstanceOf(Boolean[].class, arr);
    assertArrayEquals(new Boolean[]{true, null, false}, (Boolean[]) arr);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayFloat8ReturnsDoubleArray() throws SQLException {
    PreparedStatement pstmt =
        conn.prepareStatement("SELECT '{1.5,NaN,Infinity,-Infinity}'::float8[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object arr = rs.getArray(1).getArray();
    assertInstanceOf(Double[].class, arr);
    assertArrayEquals(
        new Double[]{1.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY},
        (Double[]) arr);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayFloat4ReturnsFloatArray() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{1.5,NULL,NaN}'::float4[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object arr = rs.getArray(1).getArray();
    assertInstanceOf(Float[].class, arr);
    assertArrayEquals(new Float[]{1.5f, null, Float.NaN}, (Float[]) arr);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayOidReturnsLongArray() throws SQLException {
    // oid is unsigned int4; legacy getArray() returned Long[].
    PreparedStatement pstmt =
        conn.prepareStatement("SELECT '{16384,2147483648,4294967295}'::oid[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object arr = rs.getArray(1).getArray();
    assertInstanceOf(Long[].class, arr);
    assertArrayEquals(new Long[]{16384L, 2147483648L, 4294967295L}, (Long[]) arr);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayTextReturnsStringArrayWithSpecialChars() throws SQLException {
    // Elements containing the delimiter, a quote and a backslash exercise the
    // server's quoting/escaping and the cursor's unescaping on the read path.
    PreparedStatement pstmt =
        conn.prepareStatement("SELECT ARRAY['a', 'b,c', 'a\"b', 'c\\d']::text[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object arr = rs.getArray(1).getArray();
    assertInstanceOf(String[].class, arr);
    assertArrayEquals(new String[]{"a", "b,c", "a\"b", "c\\d"}, (String[]) arr);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayVarcharMultiDimAndNull() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{{a,NULL},{c,d}}'::varchar[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    String[][] arr = (String[][]) rs.getArray(1).getArray();
    assertArrayEquals(new String[]{"a", null}, arr[0]);
    assertArrayEquals(new String[]{"c", "d"}, arr[1]);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayNumericReturnsBigDecimalArray() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '{1.5,2.25,NULL,-3.14}'::numeric[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object arr = rs.getArray(1).getArray();
    assertInstanceOf(java.math.BigDecimal[].class, arr);
    assertArrayEquals(new java.math.BigDecimal[]{
        new java.math.BigDecimal("1.5"), new java.math.BigDecimal("2.25"),
        null, new java.math.BigDecimal("-3.14")}, (java.math.BigDecimal[]) arr);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayUuidReturnsUuidArray() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement(
        "SELECT '{550e8400-e29b-41d4-a716-446655440000,NULL}'::uuid[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object arr = rs.getArray(1).getArray();
    assertInstanceOf(UUID[].class, arr);
    assertArrayEquals(new UUID[]{
        UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), null}, (UUID[]) arr);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testGetArrayByteaReturnsByteArrayArray() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement(
        "SELECT ARRAY['\\x0102'::bytea, '\\xaabb'::bytea, NULL::bytea]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object arr = rs.getArray(1).getArray();
    assertInstanceOf(byte[][].class, arr);
    byte[][] out = (byte[][]) arr;
    assertArrayEquals(new byte[]{0x01, 0x02}, out[0]);
    assertArrayEquals(new byte[]{(byte) 0xaa, (byte) 0xbb}, out[1]);
    assertNull(out[2]);
    rs.close();
    pstmt.close();
  }

  @Test
  public void testCreateArrayOfInt() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::int[]");
    Integer[] in = new Integer[3];
    in[0] = 0;
    in[1] = -1;
    in[2] = 2;
    pstmt.setArray(1, conn.createArrayOf("int4", in));

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Integer[] out = (Integer[]) arr.getArray();

    assertEquals(3, out.length);
    assertEquals(0, out[0].intValue());
    assertEquals(-1, out[1].intValue());
    assertEquals(2, out[2].intValue());
  }

  @Test
  public void testCreateArrayOfBytes() throws SQLException {

    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::bytea[]");
    final byte[][] in = new byte[][]{{0x01, (byte) 0xFF, (byte) 0x12}, {}, {(byte) 0xAC, (byte) 0xE4}, null};
    final Array createdArray = conn.createArrayOf("bytea", in);

    byte[][] inCopy = (byte[][]) createdArray.getArray();

    assertEquals(4, inCopy.length);

    assertArrayEquals(in[0], inCopy[0]);
    assertArrayEquals(in[1], inCopy[1]);
    assertArrayEquals(in[2], inCopy[2]);
    assertArrayEquals(in[3], inCopy[3]);
    assertNull(inCopy[3]);

    pstmt.setArray(1, createdArray);

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);

    byte[][] out = (byte[][]) arr.getArray();

    assertEquals(4, out.length);

    assertArrayEquals(in[0], out[0]);
    assertArrayEquals(in[1], out[1]);
    assertArrayEquals(in[2], out[2]);
    assertArrayEquals(in[3], out[3]);
    assertNull(out[3]);
  }

  @Test
  public void testCreateArrayOfBytesFromString() throws SQLException {

    assumeMinimumServerVersion("support for bytea[] as string requires hex string support from 9.0",
        ServerVersion.v9_0);

    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::bytea[]");
    final byte[][] in = new byte[][]{{0x01, (byte) 0xFF, (byte) 0x12}, {}, {(byte) 0xAC, (byte) 0xE4}, null};

    pstmt.setString(1, "{\"\\\\x01ff12\",\"\\\\x\",\"\\\\xace4\",NULL}");

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);

    byte[][] out = (byte[][]) arr.getArray();

    assertEquals(4, out.length);

    assertArrayEquals(in[0], out[0]);
    assertArrayEquals(in[1], out[1]);
    assertArrayEquals(in[2], out[2]);
    assertArrayEquals(in[3], out[3]);
    assertNull(out[3]);
  }

  @Test
  public void testCreateArrayOfSmallInt() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::smallint[]");
    Short[] in = new Short[3];
    in[0] = 0;
    in[1] = -1;
    in[2] = 2;
    pstmt.setArray(1, conn.createArrayOf("int2", in));

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Short[] out = (Short[]) arr.getArray();

    assertEquals(3, out.length);
    assertEquals(0, out[0].shortValue());
    assertEquals(-1, out[1].shortValue());
    assertEquals(2, out[2].shortValue());
  }

  @Test
  public void testCreateArrayOfMultiString() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::text[]");
    String[][] in = new String[2][2];
    in[0][0] = "a";
    in[0][1] = "";
    in[1][0] = "\\";
    in[1][1] = "\"\\'z";
    pstmt.setArray(1, conn.createArrayOf("text", in));

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    String[][] out = (String[][]) arr.getArray();

    assertEquals(2, out.length);
    assertEquals(2, out[0].length);
    assertEquals("a", out[0][0]);
    assertEquals("", out[0][1]);
    assertEquals("\\", out[1][0]);
    assertEquals("\"\\'z", out[1][1]);
  }

  @Test
  public void testCreateArrayOfMultidimensionalInt() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::int4[][]");
    int[][] in = new int[][]{{1, 2}, {3, 4}};
    pstmt.setArray(1, conn.createArrayOf("int4", in));

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Integer[][] out = (Integer[][]) arr.getArray();

    assertEquals(2, out.length);
    assertArrayEquals(new Integer[]{1, 2}, out[0]);
    assertArrayEquals(new Integer[]{3, 4}, out[1]);
  }

  @Test
  public void testCreateArrayOfRejectsInvalidMultidimensionalShape() {
    assertThrows(SQLException.class,
        () -> conn.createArrayOf("int4", new Integer[][]{{1}, {2, 3}}));
    assertThrows(SQLException.class,
        () -> conn.createArrayOf("int4", new Integer[][]{{1}, null}));
  }

  @Test
  public void testCreateArrayOfMultiJson() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_2)) {
      return;
    }
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::json[]");
    PGobject p1 = new PGobject();
    p1.setType("json");
    p1.setValue("{\"x\": 10}");

    PGobject p2 = new PGobject();
    p2.setType("json");
    p2.setValue("{\"x\": 20}");
    PGobject[] in = new PGobject[]{p1, p2};
    pstmt.setArray(1, conn.createArrayOf("json", in));

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    ResultSet arrRs = arr.getResultSet();
    assertTrue(arrRs.next());
    assertEquals(in[0], arrRs.getObject(2));

    assertTrue(arrRs.next());
    assertEquals(in[1], arrRs.getObject(2));
  }

  @Test
  public void testCreateArrayWithNonStandardDelimiter() throws SQLException {
    PGbox[] in = new PGbox[2];
    in[0] = new PGbox(1, 2, 3, 4);
    in[1] = new PGbox(5, 6, 7, 8);

    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::box[]");
    pstmt.setArray(1, conn.createArrayOf("box", in));
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

  @Test
  public void testCreateArrayOfNull() throws SQLException {
    String sql = "SELECT ?";
    // We must provide the type information for V2 protocol
    if (preferQueryMode == PreferQueryMode.SIMPLE) {
      sql = "SELECT ?::int8[]";
    }

    PreparedStatement pstmt = conn.prepareStatement(sql);
    String[] in = new String[2];
    in[0] = null;
    in[1] = null;
    pstmt.setArray(1, conn.createArrayOf("int8", in));

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Long[] out = (Long[]) arr.getArray();

    assertEquals(2, out.length);
    assertNull(out[0]);
    assertNull(out[1]);
  }

  @Test
  public void testCreateEmptyArrayOfIntViaAlias() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::int[]");
    Integer[] in = new Integer[0];
    pstmt.setArray(1, conn.createArrayOf("integer", in));

    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    Integer[] out = (Integer[]) arr.getArray();

    assertEquals(0, out.length);

    ResultSet arrRs = arr.getResultSet();
    assertFalse(arrRs.next());
  }

  @Test
  public void testCreateArrayWithoutServer() throws SQLException {
    String[][] in = new String[2][2];
    in[0][0] = "a";
    in[0][1] = "";
    in[1][0] = "\\";
    in[1][1] = "\"\\'z";

    Array arr = conn.createArrayOf("varchar", in);
    String[][] out = (String[][]) arr.getArray();

    assertEquals(2, out.length);
    assertEquals(2, out[0].length);
    assertEquals("a", out[0][0]);
    assertEquals("", out[0][1]);
    assertEquals("\\", out[1][0]);
    assertEquals("\"\\'z", out[1][1]);
  }

  @Test
  public void testCreatePrimitiveArray() throws SQLException {
    double[][] in = new double[2][2];
    in[0][0] = 3.5;
    in[0][1] = -4.5;
    in[1][0] = 10.0 / 3;
    in[1][1] = 77;

    Array arr = conn.createArrayOf("float8", in);
    Double[][] out = (Double[][]) arr.getArray();

    assertEquals(2, out.length);
    assertEquals(2, out[0].length);
    assertEquals(3.5, out[0][0], 0.00001);
    assertEquals(-4.5, out[0][1], 0.00001);
    assertEquals(10.0 / 3, out[1][0], 0.00001);
    assertEquals(77, out[1][1], 0.00001);
  }

  @Test
  public void testUUIDArray() throws SQLException {
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE, "UUID is not supported in PreferQueryMode.SIMPLE");
    assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v8_3), "UUID requires PostgreSQL 8.3+");
    UUID uuid1 = UUID.randomUUID();
    UUID uuid2 = UUID.randomUUID();
    UUID uuid3 = UUID.randomUUID();

    // insert a uuid array, and check
    PreparedStatement pstmt1 = conn.prepareStatement("INSERT INTO arrtest(uuidarr) VALUES (?)");
    pstmt1.setArray(1, conn.createArrayOf("uuid", new UUID[]{uuid1, uuid2, uuid3}));
    pstmt1.executeUpdate();

    PreparedStatement pstmt2 =
        conn.prepareStatement("SELECT uuidarr FROM arrtest WHERE uuidarr @> ?");
    pstmt2.setObject(1, conn.createArrayOf("uuid", new UUID[]{uuid1}), Types.OTHER);
    ResultSet rs = pstmt2.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    UUID[] out = (UUID[]) arr.getArray();

    assertEquals(3, out.length);
    assertEquals(uuid1, out[0]);
    assertEquals(uuid2, out[1]);
    assertEquals(uuid3, out[2]);

    // concatenate a uuid, and check
    UUID uuid4 = UUID.randomUUID();
    PreparedStatement pstmt3 =
        conn.prepareStatement("UPDATE arrtest SET uuidarr = uuidarr || ? WHERE uuidarr @> ?");
    pstmt3.setObject(1, uuid4, Types.OTHER);
    pstmt3.setArray(2, conn.createArrayOf("uuid", new UUID[]{uuid1}));
    pstmt3.executeUpdate();

    // --
    pstmt2.setObject(1, conn.createArrayOf("uuid", new UUID[]{uuid4}), Types.OTHER);
    rs = pstmt2.executeQuery();
    assertTrue(rs.next());
    arr = rs.getArray(1);
    out = (UUID[]) arr.getArray();

    assertEquals(4, out.length);
    assertEquals(uuid1, out[0]);
    assertEquals(uuid2, out[1]);
    assertEquals(uuid3, out[2]);
    assertEquals(uuid4, out[3]);
  }

  @Test
  public void testSetObjectFromJavaArray() throws SQLException {
    String[] strArray = new String[]{"a", "b", "c"};
    Object[] objCopy = Arrays.copyOf(strArray, strArray.length, Object[].class);

    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO arrtest(strarr) VALUES (?)");

    // cannot handle generic Object[]
    try {
      pstmt.setObject(1, objCopy, Types.ARRAY);
      pstmt.executeUpdate();
      fail("setObject() with a Java array parameter and Types.ARRAY shouldn't succeed");
    } catch (org.postgresql.util.PSQLException ex) {
      // Expected failure.
    }

    try {
      pstmt.setObject(1, objCopy);
      pstmt.executeUpdate();
      fail("setObject() with a Java array parameter and no Types argument shouldn't succeed");
    } catch (org.postgresql.util.PSQLException ex) {
      // Expected failure.
    }

    pstmt.setObject(1, strArray);
    pstmt.executeUpdate();

    pstmt.setObject(1, strArray, Types.ARRAY);
    pstmt.executeUpdate();

    // Correct way, though the use of "text" as a type is non-portable.
    // Only supported for JDK 1.6 and JDBC4
    Array sqlArray = conn.createArrayOf("text", strArray);
    pstmt.setArray(1, sqlArray);
    pstmt.executeUpdate();

    pstmt.close();
  }

  @Test
  public void testGetArrayOfComposites() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v8_4), "array_agg(expression) requires PostgreSQL 8.4+");

    PreparedStatement insertParentPstmt =
        conn.prepareStatement("INSERT INTO arrcompprnttest (name) "
            + "VALUES ('aParent');");
    insertParentPstmt.execute();

    String[] children = {
        "November 5, 2013",
        "\"A Book Title\"",
        "4\" by 6\"",
        "5\",3\""};

    PreparedStatement insertChildrenPstmt =
        conn.prepareStatement("INSERT INTO arrcompchldttest (name,description,parent) "
            + "VALUES ('child1',?,1),"
            + "('child2',?,1),"
            + "('child3',?,1),"
            + "('child4',?,1);");

    insertChildrenPstmt.setString(1, children[0]);
    insertChildrenPstmt.setString(2, children[1]);
    insertChildrenPstmt.setString(3, children[2]);
    insertChildrenPstmt.setString(4, children[3]);

    insertChildrenPstmt.execute();

    PreparedStatement pstmt = conn.prepareStatement(
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

    assertNotNull(rs);
    assertTrue(rs.next());

    Array childrenArray = rs.getArray("children");
    assertNotNull(childrenArray);

    ResultSet rsChildren = childrenArray.getResultSet();
    assertNotNull(rsChildren);
    while (rsChildren.next()) {
      String comp = rsChildren.getString(2);
      PGtokenizer token = new PGtokenizer(PGtokenizer.removePara(comp), ',');
      token.remove("\"", "\""); // remove surrounding double quotes
      if (2 < token.getSize()) {
        int childID = Integer.parseInt(token.getToken(0));
        // remove double quotes escaping with double quotes
        String value = token.getToken(2).replace("\"\"", "\"");
        assertEquals(children[childID - 1], value);
      } else {
        fail("Needs to have 3 tokens");
      }
    }
  }

  @Test
  public void testCasingComposite() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v8_3), "Arrays of composite types requires PostgreSQL 8.3+");

    PGobject cc = new PGobject();
    cc.setType("\"CorrectCasing\"");
    cc.setValue("(1)");
    Object[] in = new Object[1];
    in[0] = cc;

    Array arr = conn.createArrayOf("\"CorrectCasing\"", in);
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::\"CorrectCasing\"[]");
    pstmt.setArray(1, arr);
    ResultSet rs = pstmt.executeQuery();

    assertTrue(rs.next());
    Object[] resArr = (Object[]) rs.getArray(1).getArray();

    assertInstanceOf(PGobject.class, resArr[0]);
    PGobject resObj = (PGobject) resArr[0];
    assertEquals("(1)", resObj.getValue());
  }

  @Test
  public void testCasingBuiltinAlias() throws SQLException {
    Array arr = conn.createArrayOf("INT", new Integer[]{1, 2, 3});
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::INT[]");
    pstmt.setArray(1, arr);
    ResultSet rs = pstmt.executeQuery();

    assertTrue(rs.next());
    Integer[] resArr = (Integer[]) rs.getArray(1).getArray();

    assertArrayEquals(new Integer[]{1, 2, 3}, resArr);
  }

  @Test
  public void testCasingBuiltinNonAlias() throws SQLException {
    Array arr = conn.createArrayOf("INT4", new Integer[]{1, 2, 3});
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::INT4[]");
    pstmt.setArray(1, arr);
    ResultSet rs = pstmt.executeQuery();

    assertTrue(rs.next());
    Integer[] resArr = (Integer[]) rs.getArray(1).getArray();

    assertArrayEquals(new Integer[]{1, 2, 3}, resArr);
  }

  @Test
  public void testEvilCasing() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v8_3), "Arrays of composite types requires PostgreSQL 8.3+");

    PGobject cc = new PGobject();
    cc.setType("\"Evil.Table\"");
    cc.setValue("(1)");
    Object[] in = new Object[1];
    in[0] = cc;

    Array arr = conn.createArrayOf("\"Evil.Table\"", in);
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?::\"Evil.Table\"[]");
    pstmt.setArray(1, arr);
    ResultSet rs = pstmt.executeQuery();

    assertTrue(rs.next());
    Object[] resArr = (Object[]) rs.getArray(1).getArray();

    assertInstanceOf(PGobject.class, resArr[0]);
    PGobject resObj = (PGobject) resArr[0];
    assertEquals("(1)", resObj.getValue());
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
          // if a binary array is provided, the string representation looks like [0:1][0:1]={{1,2},{3,4}}
          int idx = actual.indexOf('=');
          if (idx > 0) {
            actual = actual.substring(idx + 1);
          }
          // Remove all double quotes. They do not make a difference here.
          actual = actual.replaceAll("\"", "");
        }
        //the string format may vary based on how data stored
        MatcherAssert.assertThat(actual, RegexMatcher.matchesPattern("\\{3\\.5,-4\\.5,NULL,77(.0)?\\}"));
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
    assertTrue(rs.next(), "arrtest should contain a row");
    Array getArray = rs.getArray(1);
    assertNull(getArray, "null array should return null value on getArray");
    Object getObject = rs.getObject(1);
    assertNull(getObject, "null array should return null on getObject");
  }

  @Test
  public void createNullArray() throws SQLException {
    Array arr = con.createArrayOf("float8", null);
    assertNotNull(arr);
    assertNull(arr.getArray());
  }

  @Test
  public void multiDimIntArray() throws SQLException {
    Array arr = con.createArrayOf("int4", new int[][]{{1, 2}, {3, 4}});
    PreparedStatement ps = con.prepareStatement("select ?::int4[][]");
    ps.setArray(1, arr);
    ResultSet rs = ps.executeQuery();
    rs.next();
    Array resArray = rs.getArray(1);
    String stringValue = resArray.toString();
    // if a binary array is provided, the string representation looks like [0:1][0:1]={{1,2},{3,4}}
    int idx = stringValue.indexOf('=');
    if (idx > 0) {
      stringValue = stringValue.substring(idx + 1);
    }
    // Both {{"1","2"},{"3","4"}} and {{1,2},{3,4}} are the same array representation
    stringValue = stringValue.replaceAll("\"", "");
    assertEquals("{{1,2},{3,4}}", stringValue);
    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(ps);
  }

  @Test
  public void insertAndQueryMultiDimArray() throws SQLException {
    Array arr = con.createArrayOf("int4", new int[][]{{1, 2}, {3, 4}});
    PreparedStatement insertPs = con.prepareStatement("INSERT INTO arrtest(intarr2) VALUES (?)");
    insertPs.setArray(1, arr);
    insertPs.execute();
    insertPs.close();

    PreparedStatement selectPs = con.prepareStatement("SELECT intarr2 FROM arrtest");
    ResultSet rs = selectPs.executeQuery();
    rs.next();

    Array array = rs.getArray(1);
    Integer[][] secondRowValues = (Integer[][]) array.getArray(2, 1);

    assertEquals(3, secondRowValues[0][0].intValue());
    assertEquals(4, secondRowValues[0][1].intValue());
  }

  @Test
  public void testJsonbArray() throws  SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_4), "jsonb requires PostgreSQL 9.4+");
    TestUtil.createTempTable(con, "jsonbarray", "jbarray jsonb[]");
    try (Statement stmt = con.createStatement()) {
      stmt.executeUpdate("insert into jsonbarray values( ARRAY['{\"a\":\"a\"}'::jsonb, '{\"b\":\"b\"}'::jsonb] )");
      try (ResultSet rs = stmt.executeQuery("select jbarray from jsonbarray")) {
        assertTrue(rs.next());
        Array jsonArray = rs.getArray(1);
        assertNotNull(jsonArray);
        assertEquals("jsonb", jsonArray.getBaseTypeName());
      }
    }
  }
}
