/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import org.postgresql.test.util.BrokenInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;


public class PreparedStatementTest extends BaseTest {

  public PreparedStatementTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "streamtable", "bin bytea, str text");
    TestUtil.createTable(con, "texttable", "ch char(3), te text, vc varchar(3)");
    TestUtil.createTable(con, "intervaltable", "i interval");
  }

  protected void tearDown() throws SQLException {
    TestUtil.dropTable(con, "streamtable");
    TestUtil.dropTable(con, "texttable");
    TestUtil.dropTable(con, "intervaltable");
    super.tearDown();
  }

  public void testSetBinaryStream() throws SQLException {
    ByteArrayInputStream bais;
    byte buf[] = new byte[10];
    for (int i = 0; i < buf.length; i++) {
      buf[i] = (byte) i;
    }

    bais = null;
    doSetBinaryStream(bais, 0);

    bais = new ByteArrayInputStream(new byte[0]);
    doSetBinaryStream(bais, 0);

    bais = new ByteArrayInputStream(buf);
    doSetBinaryStream(bais, 0);

    bais = new ByteArrayInputStream(buf);
    doSetBinaryStream(bais, 10);
  }

  public void testSetAsciiStream() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, "ASCII"));
    pw.println("Hello");
    pw.flush();

    ByteArrayInputStream bais;

    bais = new ByteArrayInputStream(baos.toByteArray());
    doSetAsciiStream(bais, 0);

    bais = new ByteArrayInputStream(baos.toByteArray());
    doSetAsciiStream(bais, 6);

    bais = new ByteArrayInputStream(baos.toByteArray());
    doSetAsciiStream(bais, 100);
  }

  public void testExecuteStringOnPreparedStatement() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT 1");

    try {
      pstmt.executeQuery("SELECT 2");
      fail("Expected an exception when executing a new SQL query on a prepared statement");
    } catch (SQLException e) {
    }

    try {
      pstmt.executeUpdate("UPDATE streamtable SET bin=bin");
      fail("Expected an exception when executing a new SQL update on a prepared statement");
    } catch (SQLException e) {
    }

    try {
      pstmt.execute("UPDATE streamtable SET bin=bin");
      fail("Expected an exception when executing a new SQL statement on a prepared statement");
    } catch (SQLException e) {
    }
  }

  public void testBinaryStreamErrorsRestartable() throws SQLException {
    // The V2 protocol does not have the ability to recover when
    // streaming data to the server. We could potentially try
    // introducing a syntax error to force the query to fail, but
    // that seems dangerous.
    //
    if (!TestUtil.isProtocolVersion(con, 3)) {
      return;
    }

    byte buf[] = new byte[10];
    for (int i = 0; i < buf.length; i++) {
      buf[i] = (byte) i;
    }

    // InputStream is shorter than the length argument implies.
    InputStream is = new ByteArrayInputStream(buf);
    runBrokenStream(is, buf.length + 1);

    // InputStream throws an Exception during read.
    is = new BrokenInputStream(new ByteArrayInputStream(buf), buf.length / 2);
    runBrokenStream(is, buf.length);

    // Invalid length < 0.
    is = new ByteArrayInputStream(buf);
    runBrokenStream(is, -1);

    // Total Bind message length too long.
    is = new ByteArrayInputStream(buf);
    runBrokenStream(is, Integer.MAX_VALUE);
  }

  private void runBrokenStream(InputStream is, int length) throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");
      pstmt.setBinaryStream(1, is, length);
      pstmt.setString(2, "Other");
      pstmt.executeUpdate();
      fail("This isn't supposed to work.");
    } catch (SQLException sqle) {
      // don't need to rollback because we're in autocommit mode
      pstmt.close();

      // verify the connection is still valid and the row didn't go in.
      Statement stmt = con.createStatement();
      ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM streamtable");
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      rs.close();
      stmt.close();
    }
  }

  private void doSetBinaryStream(ByteArrayInputStream bais, int length) throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");
    pstmt.setBinaryStream(1, bais, length);
    pstmt.setString(2, null);
    pstmt.executeUpdate();
    pstmt.close();
  }

  private void doSetAsciiStream(InputStream is, int length) throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");
    pstmt.setBytes(1, null);
    pstmt.setAsciiStream(2, is, length);
    pstmt.executeUpdate();
    pstmt.close();
  }

  public void testTrailingSpaces() throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("INSERT INTO texttable (ch, te, vc) VALUES (?, ?, ?) ");
    String str = "a  ";
    pstmt.setString(1, str);
    pstmt.setString(2, str);
    pstmt.setString(3, str);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("SELECT ch, te, vc FROM texttable WHERE ch=? AND te=? AND vc=?");
    pstmt.setString(1, str);
    pstmt.setString(2, str);
    pstmt.setString(3, str);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals(str, rs.getString(1));
    assertEquals(str, rs.getString(2));
    assertEquals(str, rs.getString(3));
    rs.close();
    pstmt.close();
  }

  public void testSetNull() throws SQLException {
    // valid: fully qualified type to setNull()
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO texttable (te) VALUES (?)");
    pstmt.setNull(1, Types.VARCHAR);
    pstmt.executeUpdate();

    // valid: fully qualified type to setObject()
    pstmt.setObject(1, null, Types.VARCHAR);
    pstmt.executeUpdate();

    // valid: setObject() with partial type info and a typed "null object instance"
    org.postgresql.util.PGobject dummy = new org.postgresql.util.PGobject();
    dummy.setType("text");
    dummy.setValue(null);
    pstmt.setObject(1, dummy, Types.OTHER);
    pstmt.executeUpdate();

    // setObject() with no type info
    pstmt.setObject(1, null);
    pstmt.executeUpdate();

    // setObject() with insufficient type info
    pstmt.setObject(1, null, Types.OTHER);
    pstmt.executeUpdate();

    // setNull() with insufficient type info
    pstmt.setNull(1, Types.OTHER);
    pstmt.executeUpdate();

    pstmt.close();
  }

  public void testSingleQuotes() throws SQLException {
    String[] testStrings = new String[]{
        "bare ? question mark",
        "quoted \\' single quote",
        "doubled '' single quote",
        "octal \\060 constant",
        "escaped \\? question mark",
        "double \\\\ backslash",
        "double \" quote",
    };

    String[] testStringsStdConf = new String[]{
        "bare ? question mark",
        "quoted '' single quote",
        "doubled '' single quote",
        "octal 0 constant",
        "escaped ? question mark",
        "double \\ backslash",
        "double \" quote",
    };

    String[] expected = new String[]{
        "bare ? question mark",
        "quoted ' single quote",
        "doubled ' single quote",
        "octal 0 constant",
        "escaped ? question mark",
        "double \\ backslash",
        "double \" quote",
    };

    if (!TestUtil.haveMinimumServerVersion(con, "8.2")) {
      for (int i = 0; i < testStrings.length; ++i) {
        PreparedStatement pstmt = con.prepareStatement("SELECT '" + testStrings[i] + "'");
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        assertEquals(expected[i], rs.getString(1));
        rs.close();
        pstmt.close();
      }
    } else {
      boolean oldStdStrings = TestUtil.getStandardConformingStrings(con);
      Statement stmt = con.createStatement();

      // Test with standard_conforming_strings turned off.
      stmt.execute("SET standard_conforming_strings TO off");
      for (int i = 0; i < testStrings.length; ++i) {
        PreparedStatement pstmt = con.prepareStatement("SELECT '" + testStrings[i] + "'");
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        assertEquals(expected[i], rs.getString(1));
        rs.close();
        pstmt.close();
      }

      // Test with standard_conforming_strings turned off...
      // ... using the escape string syntax (E'').
      stmt.execute("SET standard_conforming_strings TO on");
      for (int i = 0; i < testStrings.length; ++i) {
        PreparedStatement pstmt = con.prepareStatement("SELECT E'" + testStrings[i] + "'");
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        assertEquals(expected[i], rs.getString(1));
        rs.close();
        pstmt.close();
      }
      // ... using standard conforming input strings.
      for (int i = 0; i < testStrings.length; ++i) {
        PreparedStatement pstmt = con.prepareStatement("SELECT '" + testStringsStdConf[i] + "'");
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        assertEquals(expected[i], rs.getString(1));
        rs.close();
        pstmt.close();
      }

      stmt.execute("SET standard_conforming_strings TO " + (oldStdStrings ? "on" : "off"));
      stmt.close();
    }
  }

  public void testDoubleQuotes() throws SQLException {
    String[] testStrings = new String[]{
        "bare ? question mark",
        "single ' quote",
        "doubled '' single quote",
        "doubled \"\" double quote",
        "no backslash interpretation here: \\",
    };

    for (int i = 0; i < testStrings.length; ++i) {
      PreparedStatement pstmt =
          con.prepareStatement("CREATE TABLE \"" + testStrings[i] + "\" (i integer)");
      pstmt.executeUpdate();
      pstmt.close();

      pstmt = con.prepareStatement("DROP TABLE \"" + testStrings[i] + "\"");
      pstmt.executeUpdate();
      pstmt.close();
    }
  }

  public void testDollarQuotes() throws SQLException {
    // dollar-quotes are supported in the backend since version 8.0
    if (!TestUtil.haveMinimumServerVersion(con, "8.0")) {
      return;
    }

    PreparedStatement st;
    ResultSet rs;

    st = con.prepareStatement("SELECT $$;$$ WHERE $x$?$x$=$_0$?$_0$ AND $$?$$=?");
    st.setString(1, "?");
    rs = st.executeQuery();
    assertTrue(rs.next());
    assertEquals(";", rs.getString(1));
    assertFalse(rs.next());
    st.close();

    st = con.prepareStatement(
        "SELECT $__$;$__$ WHERE ''''=$q_1$'$q_1$ AND ';'=?;"
            + "SELECT $x$$a$;$x $a$$x$ WHERE $$;$$=? OR ''=$c$c$;$c$;"
            + "SELECT ?");
    st.setString(1, ";");
    st.setString(2, ";");
    st.setString(3, "$a$ $a$");

    assertTrue(st.execute());
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(";", rs.getString(1));
    assertFalse(rs.next());

    assertTrue(st.getMoreResults());
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("$a$;$x $a$", rs.getString(1));
    assertFalse(rs.next());

    assertTrue(st.getMoreResults());
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("$a$ $a$", rs.getString(1));
    assertFalse(rs.next());
    st.close();
  }

  public void testDollarQuotesAndIdentifiers() throws SQLException {
    // dollar-quotes are supported in the backend since version 8.0
    if (!TestUtil.haveMinimumServerVersion(con, "8.0")) {
      return;
    }

    PreparedStatement st;

    con.createStatement().execute("CREATE TEMP TABLE a$b$c(a varchar, b varchar)");
    st = con.prepareStatement("INSERT INTO a$b$c (a, b) VALUES (?, ?)");
    st.setString(1, "a");
    st.setString(2, "b");
    st.executeUpdate();
    st.close();

    con.createStatement().execute("CREATE TEMP TABLE e$f$g(h varchar, e$f$g varchar) ");
    st = con.prepareStatement("UPDATE e$f$g SET h = ? || e$f$g");
    st.setString(1, "a");
    st.executeUpdate();
    st.close();
  }

  public void testComments() throws SQLException {
    PreparedStatement st;
    ResultSet rs;

    st = con.prepareStatement("SELECT /*?*/ /*/*/*/**/*/*/*/1;SELECT ?;--SELECT ?");
    st.setString(1, "a");
    assertTrue(st.execute());
    assertTrue(st.getMoreResults());
    assertFalse(st.getMoreResults());
    st.close();

    st = con.prepareStatement("SELECT /**/'?'/*/**/*/ WHERE '?'=/*/*/*?*/*/*/--?\n?");
    st.setString(1, "?");
    rs = st.executeQuery();
    assertTrue(rs.next());
    assertEquals("?", rs.getString(1));
    assertFalse(rs.next());
    st.close();
  }

  public void testDoubleQuestionMark() throws SQLException {
    PreparedStatement st;
    ResultSet rs;

    st = con.prepareStatement("select ??- lseg '((-1,0),(1,0))';");
    rs = st.executeQuery();
    assertTrue(rs.next());
    assertEquals("t", rs.getString(1));
    assertFalse(rs.next());
    st.close();

    st = con.prepareStatement("select lseg '((-1,0),(1,0))' ??# box '((-2,-2),(2,2))';");
    rs = st.executeQuery();
    assertTrue(rs.next());
    assertEquals("t", rs.getString(1));
    assertFalse(rs.next());
    st.close();
  }

  public void testDouble() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE TEMP TABLE double_tab (max_double float, min_double float, null_value float)");
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("insert into double_tab values (?,?,?)");
    pstmt.setDouble(1, 1.0E125);
    pstmt.setDouble(2, 1.0E-130);
    pstmt.setNull(3, Types.DOUBLE);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from double_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    double d = rs.getDouble(1);
    assertTrue(rs.getDouble(1) == 1.0E125);
    assertTrue(rs.getDouble(2) == 1.0E-130);
    rs.getDouble(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testFloat() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE TEMP TABLE float_tab (max_float real, min_float real, null_value real)");
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("insert into float_tab values (?,?,?)");
    pstmt.setFloat(1, (float) 1.0E37);
    pstmt.setFloat(2, (float) 1.0E-37);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from float_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    float f = rs.getFloat(1);
    assertTrue("expected 1.0E37,received " + rs.getFloat(1), rs.getFloat(1) == (float) 1.0E37);
    assertTrue("expected 1.0E-37,received " + rs.getFloat(2), rs.getFloat(2) == (float) 1.0E-37);
    rs.getDouble(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testBoolean() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE TEMP TABLE bool_tab (max_val boolean, min_val boolean, null_val boolean)");
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("insert into bool_tab values (?,?,?)");
    pstmt.setBoolean(1, true);
    pstmt.setBoolean(2, false);
    pstmt.setNull(3, Types.BIT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from bool_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected true,received " + rs.getBoolean(1), rs.getBoolean(1) == true);
    assertTrue("expected false,received " + rs.getBoolean(2), rs.getBoolean(2) == false);
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testSetFloatInteger() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE float_tab (max_val float8, min_val float, null_val float8)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInteger = new Integer(2147483647);
    Integer minInteger = new Integer(-2147483648);

    Double maxFloat = new Double(2147483647);
    Double minFloat = new Double(-2147483648);

    pstmt = con.prepareStatement("insert into float_tab values (?,?,?)");
    pstmt.setObject(1, maxInteger, Types.FLOAT);
    pstmt.setObject(2, minInteger, Types.FLOAT);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from float_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + maxFloat + " ,received " + rs.getObject(1),
        ((Double) rs.getObject(1)).equals(maxFloat));
    assertTrue("expected " + minFloat + " ,received " + rs.getObject(2),
        ((Double) rs.getObject(2)).equals(minFloat));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testSetFloatString() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE float_tab (max_val float8, min_val float8, null_val float8)");
    pstmt.executeUpdate();
    pstmt.close();

    String maxStringFloat = new String("1.0E37");
    String minStringFloat = new String("1.0E-37");
    Double maxFloat = new Double(1.0E37);
    Double minFloat = new Double(1.0E-37);

    pstmt = con.prepareStatement("insert into float_tab values (?,?,?)");
    pstmt.setObject(1, maxStringFloat, Types.FLOAT);
    pstmt.setObject(2, minStringFloat, Types.FLOAT);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from float_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected true,received " + rs.getObject(1),
        ((Double) rs.getObject(1)).equals(maxFloat));
    assertTrue("expected false,received " + rs.getBoolean(2),
        ((Double) rs.getObject(2)).equals(minFloat));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testSetFloatBigDecimal() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE float_tab (max_val float8, min_val float8, null_val float8)");
    pstmt.executeUpdate();
    pstmt.close();

    BigDecimal maxBigDecimalFloat = new BigDecimal("1.0E37");
    BigDecimal minBigDecimalFloat = new BigDecimal("1.0E-37");
    Double maxFloat = new Double(1.0E37);
    Double minFloat = new Double(1.0E-37);

    pstmt = con.prepareStatement("insert into float_tab values (?,?,?)");
    pstmt.setObject(1, maxBigDecimalFloat, Types.FLOAT);
    pstmt.setObject(2, minBigDecimalFloat, Types.FLOAT);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from float_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + maxFloat + " ,received " + rs.getObject(1),
        ((Double) rs.getObject(1)).equals(maxFloat));
    assertTrue("expected " + minFloat + " ,received " + rs.getObject(2),
        ((Double) rs.getObject(2)).equals(minFloat));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testSetTinyIntFloat() throws SQLException {
    PreparedStatement pstmt = con
        .prepareStatement("CREATE temp TABLE tiny_int (max_val int4, min_val int4, null_val int4)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInt = new Integer(127);
    Integer minInt = new Integer(-127);
    Float maxIntFloat = new Float(127);
    Float minIntFloat = new Float(-127);

    pstmt = con.prepareStatement("insert into tiny_int values (?,?,?)");
    pstmt.setObject(1, maxIntFloat, Types.TINYINT);
    pstmt.setObject(2, minIntFloat, Types.TINYINT);
    pstmt.setNull(3, Types.TINYINT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from tiny_int");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertEquals("maxInt as rs.getObject", maxInt, rs.getObject(1));
    assertEquals("minInt as rs.getObject", minInt, rs.getObject(2));
    rs.getObject(3);
    assertTrue("rs.wasNull after rs.getObject", rs.wasNull());
    assertEquals("maxInt as rs.getInt", maxInt, (Integer) rs.getInt(1));
    assertEquals("minInt as rs.getInt", minInt, (Integer) rs.getInt(2));
    rs.getInt(3);
    assertTrue("rs.wasNull after rs.getInt", rs.wasNull());
    assertEquals("maxInt as rs.getLong", Long.valueOf(maxInt), (Long) rs.getLong(1));
    assertEquals("minInt as rs.getLong", Long.valueOf(minInt), (Long) rs.getLong(2));
    rs.getLong(3);
    assertTrue("rs.wasNull after rs.getLong", rs.wasNull());
    assertEquals("maxInt as rs.getBigDecimal", BigDecimal.valueOf(maxInt), rs.getBigDecimal(1));
    assertEquals("minInt as rs.getBigDecimal", BigDecimal.valueOf(minInt), rs.getBigDecimal(2));
    assertNull("rs.getBigDecimal", rs.getBigDecimal(3));
    assertTrue("rs.getBigDecimal after rs.getLong", rs.wasNull());
    assertEquals("maxInt as rs.getBigDecimal(scale=0)", BigDecimal.valueOf(maxInt),
        rs.getBigDecimal(1, 0));
    assertEquals("minInt as rs.getBigDecimal(scale=0)", BigDecimal.valueOf(minInt),
        rs.getBigDecimal(2, 0));
    assertNull("rs.getBigDecimal(scale=0)", rs.getBigDecimal(3, 0));
    assertTrue("rs.getBigDecimal after rs.getLong", rs.wasNull());
    assertEquals("maxInt as rs.getBigDecimal(scale=1)",
        BigDecimal.valueOf(maxInt).setScale(1, BigDecimal.ROUND_HALF_EVEN), rs.getBigDecimal(1, 1));
    assertEquals("minInt as rs.getBigDecimal(scale=1)",
        BigDecimal.valueOf(minInt).setScale(1, BigDecimal.ROUND_HALF_EVEN), rs.getBigDecimal(2, 1));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testSetSmallIntFloat() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE small_int (max_val int4, min_val int4, null_val int4)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInt = new Integer(32767);
    Integer minInt = new Integer(-32768);
    Float maxIntFloat = new Float(32767);
    Float minIntFloat = new Float(-32768);

    pstmt = con.prepareStatement("insert into small_int values (?,?,?)");
    pstmt.setObject(1, maxIntFloat, Types.SMALLINT);
    pstmt.setObject(2, minIntFloat, Types.SMALLINT);
    pstmt.setNull(3, Types.TINYINT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from small_int");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + maxInt + " ,received " + rs.getObject(1),
        ((Integer) rs.getObject(1)).equals(maxInt));
    assertTrue("expected " + minInt + " ,received " + rs.getObject(2),
        ((Integer) rs.getObject(2)).equals(minInt));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testSetIntFloat() throws SQLException {
    PreparedStatement pstmt = con
        .prepareStatement("CREATE temp TABLE int_TAB (max_val int4, min_val int4, null_val int4)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInt = new Integer(1000);
    Integer minInt = new Integer(-1000);
    Float maxIntFloat = new Float(1000);
    Float minIntFloat = new Float(-1000);

    pstmt = con.prepareStatement("insert into int_tab values (?,?,?)");
    pstmt.setObject(1, maxIntFloat, Types.INTEGER);
    pstmt.setObject(2, minIntFloat, Types.INTEGER);
    pstmt.setNull(3, Types.INTEGER);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from int_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + maxInt + " ,received " + rs.getObject(1),
        ((Integer) rs.getObject(1)).equals(maxInt));
    assertTrue("expected " + minInt + " ,received " + rs.getObject(2),
        ((Integer) rs.getObject(2)).equals(minInt));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testSetBooleanDouble() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE double_tab (max_val float, min_val float, null_val float)");
    pstmt.executeUpdate();
    pstmt.close();

    Boolean trueVal = Boolean.TRUE;
    Boolean falseVal = Boolean.FALSE;
    Double dBooleanTrue = new Double(1);
    Double dBooleanFalse = new Double(0);

    pstmt = con.prepareStatement("insert into double_tab values (?,?,?)");
    pstmt.setObject(1, trueVal, Types.DOUBLE);
    pstmt.setObject(2, falseVal, Types.DOUBLE);
    pstmt.setNull(3, Types.DOUBLE);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from double_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + dBooleanTrue + " ,received " + rs.getObject(1),
        ((Double) rs.getObject(1)).equals(dBooleanTrue));
    assertTrue("expected " + dBooleanFalse + " ,received " + rs.getObject(2),
        ((Double) rs.getObject(2)).equals(dBooleanFalse));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testSetBooleanNumeric() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE numeric_tab (max_val numeric(30,15), min_val numeric(30,15), null_val numeric(30,15))");
    pstmt.executeUpdate();
    pstmt.close();

    Boolean trueVal = Boolean.TRUE;
    Boolean falseVal = Boolean.FALSE;
    BigDecimal dBooleanTrue = new BigDecimal(1);
    BigDecimal dBooleanFalse = new BigDecimal(0);

    pstmt = con.prepareStatement("insert into numeric_tab values (?,?,?)");
    pstmt.setObject(1, trueVal, Types.NUMERIC, 2);
    pstmt.setObject(2, falseVal, Types.NUMERIC, 2);
    pstmt.setNull(3, Types.DOUBLE);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from numeric_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + dBooleanTrue + " ,received " + rs.getObject(1),
        ((BigDecimal) rs.getObject(1)).compareTo(dBooleanTrue) == 0);
    assertTrue("expected " + dBooleanFalse + " ,received " + rs.getObject(2),
        ((BigDecimal) rs.getObject(2)).compareTo(dBooleanFalse) == 0);
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testSetBooleanDecimal() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE DECIMAL_TAB (max_val numeric(30,15), min_val numeric(30,15), null_val numeric(30,15))");
    pstmt.executeUpdate();
    pstmt.close();

    Boolean trueVal = Boolean.TRUE;
    Boolean falseVal = Boolean.FALSE;
    BigDecimal dBooleanTrue = new BigDecimal(1);
    BigDecimal dBooleanFalse = new BigDecimal(0);

    pstmt = con.prepareStatement("insert into DECIMAL_TAB values (?,?,?)");
    pstmt.setObject(1, trueVal, Types.DECIMAL, 2);
    pstmt.setObject(2, falseVal, Types.DECIMAL, 2);
    pstmt.setNull(3, Types.DOUBLE);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from DECIMAL_TAB");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertTrue("expected " + dBooleanTrue + " ,received " + rs.getObject(1),
        ((BigDecimal) rs.getObject(1)).compareTo(dBooleanTrue) == 0);
    assertTrue("expected " + dBooleanFalse + " ,received " + rs.getObject(2),
        ((BigDecimal) rs.getObject(2)).compareTo(dBooleanFalse) == 0);
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  public void testSetObjectBigDecimalUnscaled() throws SQLException {
    TestUtil.createTempTable(con, "decimal_scale",
        "n1 numeric, n2 numeric, n3 numeric, n4 numeric");
    PreparedStatement pstmt = con.prepareStatement("insert into decimal_scale values(?,?,?,?)");
    BigDecimal v = new BigDecimal("3.141593");
    pstmt.setObject(1, v, Types.NUMERIC);

    String vs = v.toPlainString();
    pstmt.setObject(2, vs, Types.NUMERIC);

    Float vf = Float.valueOf(vs);
    pstmt.setObject(3, vf, Types.NUMERIC);

    Double vd = Double.valueOf(vs);
    pstmt.setObject(4, vd, Types.NUMERIC);

    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select n1,n2,n3,n4 from decimal_scale");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertTrue("expected numeric set via BigDecimal " + v + " stored as " + rs.getBigDecimal(1),
        v.compareTo(rs.getBigDecimal(1)) == 0);
    assertTrue("expected numeric set via String" + vs + " stored as " + rs.getBigDecimal(2),
        v.compareTo(rs.getBigDecimal(2)) == 0);
    // float is really bad...
    assertTrue("expected numeric set via Float" + vf + " stored as " + rs.getBigDecimal(3),
        v.compareTo(rs.getBigDecimal(3).setScale(6, RoundingMode.HALF_UP)) == 0);
    assertTrue("expected numeric set via Double" + vd + " stored as " + rs.getBigDecimal(4),
        v.compareTo(rs.getBigDecimal(4)) == 0);

    rs.close();
    pstmt.close();
  }

  public void testSetObjectBigDecimalWithScale() throws SQLException {
    TestUtil.createTempTable(con, "decimal_scale",
        "n1 numeric, n2 numeric, n3 numeric, n4 numeric");
    PreparedStatement psinsert = con.prepareStatement("insert into decimal_scale values(?,?,?,?)");
    PreparedStatement psselect = con.prepareStatement("select n1,n2,n3,n4 from decimal_scale");
    PreparedStatement pstruncate = con.prepareStatement("truncate table decimal_scale");

    BigDecimal v = new BigDecimal("3.141593");
    String vs = v.toPlainString();
    Float vf = Float.valueOf(vs);
    Double vd = Double.valueOf(vs);

    for (int s = 0; s < 6; s++) {
      psinsert.setObject(1, v, Types.NUMERIC, s);
      psinsert.setObject(2, vs, Types.NUMERIC, s);
      psinsert.setObject(3, vf, Types.NUMERIC, s);
      psinsert.setObject(4, vd, Types.NUMERIC, s);

      psinsert.executeUpdate();

      ResultSet rs = psselect.executeQuery();
      assertTrue(rs.next());
      BigDecimal vscaled = v.setScale(s, RoundingMode.HALF_UP);
      assertTrue(
          "expected numeric set via BigDecimal " + v + " with scale " + s + " stored as " + vscaled,
          vscaled.compareTo(rs.getBigDecimal(1)) == 0);
      assertTrue(
          "expected numeric set via String" + vs + " with scale " + s + " stored as " + vscaled,
          vscaled.compareTo(rs.getBigDecimal(2)) == 0);
      assertTrue(
          "expected numeric set via Float" + vf + " with scale " + s + " stored as " + vscaled,
          vscaled.compareTo(rs.getBigDecimal(3)) == 0);
      assertTrue(
          "expected numeric set via Double" + vd + " with scale " + s + " stored as " + vscaled,
          vscaled.compareTo(rs.getBigDecimal(4)) == 0);
      rs.close();
      pstruncate.executeUpdate();
    }

    psinsert.close();
    psselect.close();
    pstruncate.close();
  }

  public void testUnknownSetObject() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO intervaltable(i) VALUES (?)");

    if (TestUtil.isProtocolVersion(con, 3)) {
      pstmt.setString(1, "1 week");
      try {
        pstmt.executeUpdate();
        fail("Should have failed with type mismatch.");
      } catch (SQLException sqle) {
      }
    }

    pstmt.setObject(1, "1 week", Types.OTHER);
    pstmt.executeUpdate();
    pstmt.close();
  }

  /**
   * With autoboxing this apparently happens more often now.
   */
  public void testSetObjectCharacter() throws SQLException {
    PreparedStatement ps = con.prepareStatement("INSERT INTO texttable(te) VALUES (?)");
    ps.setObject(1, new Character('z'));
    ps.executeUpdate();
    ps.close();
  }

  /**
   * When we have parameters of unknown type and it's not using the unnamed statement, we issue a
   * protocol level statment describe message for the V3 protocol. This test just makes sure that
   * works.
   */
  public void testStatementDescribe() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::int");
    pstmt.setObject(1, new Integer(2), Types.OTHER);
    for (int i = 0; i < 10; i++) {
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      rs.close();
    }
    pstmt.close();
  }

}
