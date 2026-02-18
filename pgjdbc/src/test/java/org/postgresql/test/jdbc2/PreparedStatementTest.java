/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGStatement;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PgStatement;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.DisabledIfServerVersionGreater;
import org.postgresql.test.util.BrokenInputStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

@ParameterizedClass
@MethodSource("data")
public class PreparedStatementTest extends BaseTest4 {

  private static final int NUMERIC_MAX_PRECISION = 1000;
  private static final int NUMERIC_MAX_DISPLAY_SCALE = NUMERIC_MAX_PRECISION;

  public PreparedStatementTest(BinaryMode binaryMode) {
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
    TestUtil.createTable(con, "streamtable", "bin bytea, str text");
    TestUtil.createTable(con, "texttable", "ch char(3), te text, vc varchar(3)");
    TestUtil.createTable(con, "intervaltable", "i interval");
    TestUtil.createTable(con, "inttable", "a int");
    TestUtil.createTable(con, "bool_tab", "bool_val boolean, null_val boolean, tf_val boolean, "
        + "truefalse_val boolean, yn_val boolean, yesno_val boolean, "
        + "onoff_val boolean, onezero_val boolean");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "streamtable");
    TestUtil.dropTable(con, "texttable");
    TestUtil.dropTable(con, "intervaltable");
    TestUtil.dropTable(con, "inttable");
    TestUtil.dropTable(con, "bool_tab");
    super.tearDown();
  }

  private int getNumberOfServerPreparedStatements(String sql)
      throws SQLException {
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    try {
      pstmt = con.prepareStatement(
          "select count(*) from pg_prepared_statements where statement = ?");
      pstmt.setString(1, sql);
      rs = pstmt.executeQuery();
      rs.next();
      return rs.getInt(1);
    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(pstmt);
    }
  }

  @Test
  public void testSetBinaryStream() throws SQLException {
    ByteArrayInputStream bais;
    byte[] buf = new byte[10];
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

  @Test
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

  @Test
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

  @Test
  public void testBinaryStreamErrorsRestartable() throws SQLException, IOException {
    byte[] buf = new byte[10];
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

  private void runBrokenStream(InputStream is, int length) throws SQLException, IOException {
    assertThrows(
        SQLException.class,
        () -> {
          try (PreparedStatement pstmt =
                   con.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");) {
            pstmt.setBinaryStream(1, is, length);
            pstmt.setString(2, "Other");
            pstmt.executeUpdate();
          }
        },
        () -> {
          String available;
          try {
            available = String.valueOf(is.available());
          } catch (IOException e) {
            available = "exception from .available(): " + e.getMessage();
          }
          return "the provided stream length is " + length
              + ", and the number of available bytes on stream length is " + available
              + ", so the bind should fail";
        }
    );
    // verify the connection is still valid and the row didn't go in.
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM streamtable");) {
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
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

  @Test
  public void ByteArrayInputStream_setBinaryStream_toString() throws SQLException {
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO streamtable VALUES (?,?)")) {

      byte[] buf = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
      ByteArrayInputStream byteStream = new ByteArrayInputStream(buf);

      pstmt.setBinaryStream(1, byteStream, buf.length);
      assertEquals("INSERT INTO streamtable VALUES (?,?)", assertDoesNotThrow(
          pstmt::toString,
          "PreparedStatement#toString call should succeed with half-set parameters"), "InputStream parameter should come as ?, and the second parameter is unset, so it should be ? as well");

      pstmt.setString(2, "test");

      String expected = "INSERT INTO streamtable VALUES (?,('test'))";
      assertEquals(expected, assertDoesNotThrow(
          pstmt::toString,
          "PreparedStatement#toString call should succeed after setting parameters"), "InputStream parameter should come as ? when calling PreparedStatement#toString as we can't process input stream twice yet");

      assertEquals(expected, assertDoesNotThrow(
          pstmt::toString,
          "Second PreparedStatement#toString call should succeed as well"), "InputStream parameter should come as ? when calling PreparedStatement#toString as we can't process input stream twice yet");

      pstmt.execute();

      assertEquals(expected, assertDoesNotThrow(
          pstmt::toString,
          "PreparedStatement#toString call should succeed even after execute()"), "PreparedStatement#toString after .execute()");
    }
  }

  @Test
  public void ByteArrayInputStream_setBinaryStream_addBatch_toString() throws SQLException {
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO streamtable VALUES (?,?)")) {

      pstmt.setBinaryStream(1, new ByteArrayInputStream(new byte[]{0, 1}), 2);
      pstmt.setString(2, "line1");
      pstmt.addBatch();
      pstmt.setBinaryStream(1, new ByteArrayInputStream(new byte[]{0, 1, 2}), 3);
      pstmt.setString(2, "line2");
      pstmt.addBatch();

      String expected = "INSERT INTO streamtable VALUES (?,('line1'));\n"
          + "INSERT INTO streamtable VALUES (?,('line2'))";
      assertEquals(expected, assertDoesNotThrow(
          pstmt::toString,
          "PreparedStatement#toString call should succeed after addBatch"), "InputStream parameter should come as ? when calling PreparedStatement#toString as we can't process input stream twice yet");

      assertEquals(expected, assertDoesNotThrow(
          pstmt::toString,
          "Second PreparedStatement#toString call should succeed as well"), "InputStream parameter should come as ? when calling PreparedStatement#toString as we can't process input stream twice yet");

      pstmt.executeBatch();

      assertEquals("INSERT INTO streamtable VALUES (?,('line2'))", assertDoesNotThrow(
          pstmt::toString,
          "PreparedStatement#toString call should succeed even after executeBatch()"), "PreparedStatement#toString after executeBatch() seem to equal to the latest parameter row");
    }
  }

  @Test
  public void ByteArray_setBytes_toString() throws SQLException {
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO streamtable VALUES (?,?)")) {

      byte[] buf = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

      pstmt.setBytes(1, buf);

      assertEquals("INSERT INTO streamtable VALUES ('\\x00010203040506070809'::bytea,?)", assertDoesNotThrow(
          pstmt::toString,
          "PreparedStatement#toString call should succeed with half-set parameters"), "byte[] parameter could be rendered, and the second parameter is unset, so it should be ? as well");

      pstmt.setString(2, "test");

      String expected = "INSERT INTO streamtable VALUES ('\\x00010203040506070809'::bytea,('test'))";
      assertEquals(expected, assertDoesNotThrow(
          pstmt::toString,
          "PreparedStatement#toString call should succeed after setting parameters"), "byte[] should be rendered when calling PreparedStatement#toString");

      assertEquals(expected, assertDoesNotThrow(
          pstmt::toString,
          "Second PreparedStatement#toString call should succeed as well"), "byte[] should be rendered when calling PreparedStatement#toString");

      pstmt.execute();

      assertEquals(expected, assertDoesNotThrow(
          pstmt::toString,
          "PreparedStatement#toString call should succeed even after execute()"), "PreparedStatement#toString after .execute()");
    }
  }

  @Test
  public void ByteArray_setBytes_addBatch_toString() throws SQLException {
    try (PreparedStatement pstmt =
             con.prepareStatement("INSERT INTO streamtable VALUES (?,?)")) {

      pstmt.setBytes(1, new byte[]{0, 1});
      pstmt.setString(2, "line1");
      pstmt.addBatch();

      pstmt.setBytes(1, new byte[]{0, 1, 2});
      pstmt.setString(2, "line2");
      pstmt.addBatch();

      String expected = "INSERT INTO streamtable VALUES ('\\x0001'::bytea,('line1'));\n"
          + "INSERT INTO streamtable VALUES ('\\x000102'::bytea,('line2'))";
      assertEquals(expected, assertDoesNotThrow(
          pstmt::toString,
          "PreparedStatement#toString call should succeed after addBatch"), "byte[] should be rendered when calling PreparedStatement#toString");

      assertEquals(expected, assertDoesNotThrow(
          pstmt::toString,
          "Second PreparedStatement#toString call should succeed as well"), "byte[] should be rendered when calling PreparedStatement#toString");

      pstmt.executeBatch();

      assertEquals("INSERT INTO streamtable VALUES ('\\x000102'::bytea,('line2'))", assertDoesNotThrow(
          pstmt::toString,
          "PreparedStatement#toString call should succeed even after executeBatch()"), "PreparedStatement#toString after executeBatch() seem to equal to the latest parameter row");
    }
  }

  @Test
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

  @Test
  public void testBinds() throws SQLException {
    // braces around (42) are required to puzzle the parser
    String query = "INSERT INTO inttable(a) VALUES (?);SELECT (42)";
    PreparedStatement ps = con.prepareStatement(query);
    ps.setInt(1, 100500);
    ps.execute();
    ResultSet rs = ps.getResultSet();
    assertNull(rs, "insert produces no results ==> getResultSet should be null");
    assertTrue(ps.getMoreResults(), "There are two statements => getMoreResults should be true");
    rs = ps.getResultSet();
    assertNotNull(rs, "select produces results ==> getResultSet should be not null");
    assertTrue(rs.next(), "select produces 1 row ==> rs.next should be true");
    assertEquals(42, rs.getInt(1), "second result of query " + query);

    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(ps);
  }

  @Test
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

    assumeMinimumServerVersion(ServerVersion.v8_3);
    pstmt = con.prepareStatement("select 'ok' where ?=? or (? is null) ");
    pstmt.setObject(1, UUID.randomUUID(), Types.OTHER);
    pstmt.setNull(2, Types.OTHER, "uuid");
    pstmt.setNull(3, Types.OTHER, "uuid");
    ResultSet rs = pstmt.executeQuery();

    assertTrue(rs.next());
    assertEquals("ok", rs.getObject(1));

    rs.close();
    pstmt.close();

  }

  @DisabledIfServerVersionGreater("19")
  @Test
  public void testSingleQuotes() throws SQLException {
    // This test is only relevant for PostgreSQL 18 and below
    // as of 4576208 in postgres non-standard strings now throw an error.

    String[] testStrings = new String[]{
      "bare ? question mark",
      "quoted \\' single quote",
      "doubled '' single quote",
      "octal \\060 constant",
      "escaped \\? question mark",
      "double \\\\ backslash",
      "double \" quote",};

    String[] testStringsStdConf = new String[]{
      "bare ? question mark",
      "quoted '' single quote",
      "doubled '' single quote",
      "octal 0 constant",
      "escaped ? question mark",
      "double \\ backslash",
      "double \" quote",};

    String[] expected = new String[]{
      "bare ? question mark",
      "quoted ' single quote",
      "doubled ' single quote",
      "octal 0 constant",
      "escaped ? question mark",
      "double \\ backslash",
      "double \" quote",};

    boolean oldStdStrings = TestUtil.getStandardConformingStrings(con);
    Statement stmt = con.createStatement();

    // Test with standard_conforming_strings turned off.
    stmt.execute("SET standard_conforming_strings TO off");
    for (int i = 0; i < testStrings.length; i++) {
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
    for (int i = 0; i < testStrings.length; i++) {
      PreparedStatement pstmt = con.prepareStatement("SELECT E'" + testStrings[i] + "'");
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(expected[i], rs.getString(1));
      rs.close();
      pstmt.close();
    }
    // ... using standard conforming input strings.
    for (int i = 0; i < testStrings.length; i++) {
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

  @Test
  public void testDoubleQuotes() throws SQLException {
    String[] testStrings = new String[]{
        "bare ? question mark",
        "single ' quote",
        "doubled '' single quote",
        "doubled \"\" double quote",
        "no backslash interpretation here: \\",
    };

    for (String testString : testStrings) {
      PreparedStatement pstmt =
          con.prepareStatement("CREATE TABLE \"" + testString + "\" (i integer)");
      pstmt.executeUpdate();
      pstmt.close();

      pstmt = con.prepareStatement("DROP TABLE \"" + testString + "\"");
      pstmt.executeUpdate();
      pstmt.close();
    }
  }

  @Test
  public void testDollarQuotes() throws SQLException {
    // dollar-quotes are supported in the backend since version 8.0
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

  @Test
  public void testDollarQuotesAndIdentifiers() throws SQLException {
    // dollar-quotes are supported in the backend since version 8.0
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

  @Test
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

  @Test
  public void testDoubleQuestionMark() throws SQLException {
    PreparedStatement st;
    ResultSet rs;

    st = con.prepareStatement("select ??- lseg '((-1,0),(1,0))';");
    rs = st.executeQuery();
    assertTrue(rs.next());
    // Bool values in binary mode are first converted to their Java type (Boolean), and then
    // converted to String, which means that we receive 'true'. Bool values in text mode are
    // returned as the same text value that was returned by the server, i.e. 't'.
    assertEquals(binaryMode == BinaryMode.FORCE && preferQueryMode != PreferQueryMode.SIMPLE ? "true" : "t", rs.getString(1));
    assertFalse(rs.next());
    st.close();

    st = con.prepareStatement("select lseg '((-1,0),(1,0))' ??# box '((-2,-2),(2,2))';");
    rs = st.executeQuery();
    assertTrue(rs.next());
    assertEquals(binaryMode == BinaryMode.FORCE && preferQueryMode != PreferQueryMode.SIMPLE ? "true" : "t", rs.getString(1));
    assertFalse(rs.next());
    st.close();
  }

  @Test
  public void testNumeric() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE TEMP TABLE numeric_tab (max_numeric_positive numeric, min_numeric_positive numeric, max_numeric_negative numeric, min_numeric_negative numeric, null_value numeric)");
    pstmt.executeUpdate();
    pstmt.close();

    char[] wholeDigits = new char[NUMERIC_MAX_DISPLAY_SCALE];
    for (int i = 0; i < NUMERIC_MAX_DISPLAY_SCALE; i++) {
      wholeDigits[i] = '9';
    }

    char[] fractionDigits = new char[NUMERIC_MAX_PRECISION];
    for (int i = 0; i < NUMERIC_MAX_PRECISION; i++) {
      fractionDigits[i] = '9';
    }

    String maxValueString = new String(wholeDigits);
    String minValueString = new String(fractionDigits);
    BigDecimal[] values = new BigDecimal[4];
    values[0] = new BigDecimal(maxValueString);
    values[1] = new BigDecimal("-" + maxValueString);
    values[2] = new BigDecimal(minValueString);
    values[3] = new BigDecimal("-" + minValueString);

    pstmt = con.prepareStatement("insert into numeric_tab values (?,?,?,?,?)");
    for (int i = 1; i < 5; i++) {
      pstmt.setBigDecimal(i, values[i - 1]);
    }

    pstmt.setNull(5, Types.NUMERIC);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from numeric_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    for (int i = 1; i < 5; i++) {
      assertEquals(0, rs.getBigDecimal(i).compareTo(values[i - 1]));
    }
    rs.getDouble(5);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
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
    assertEquals(1.0E125, rs.getDouble(1));
    assertEquals(1.0E-130, rs.getDouble(2));
    rs.getDouble(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
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
    assertEquals((float) 1.0E37, rs.getFloat(1), "expected 1.0E37,received " + rs.getFloat(1));
    assertEquals((float) 1.0E-37, rs.getFloat(2), "expected 1.0E-37,received " + rs.getFloat(2));
    rs.getDouble(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testNaNLiteralsSimpleStatement() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select 'NaN'::numeric, 'NaN'::real, 'NaN'::double precision");
    checkNaNLiterals(stmt, rs);
  }

  @Test
  public void testNaNLiteralsPreparedStatement() throws SQLException {
    PreparedStatement stmt = con.prepareStatement("select 'NaN'::numeric, 'NaN'::real, 'NaN'::double precision");
    checkNaNLiterals(stmt, stmt.executeQuery());
  }

  private static void checkNaNLiterals(Statement stmt, ResultSet rs) throws SQLException {
    rs.next();
    assertTrue(Double.isNaN((Double) rs.getObject(3)), "Double.isNaN((Double) rs.getObject");
    assertTrue(Double.isNaN(rs.getDouble(3)), "Double.isNaN(rs.getDouble");
    assertTrue(Float.isNaN((Float) rs.getObject(2)), "Float.isNaN((Float) rs.getObject");
    assertTrue(Float.isNaN(rs.getFloat(2)), "Float.isNaN(rs.getFloat");
    assertTrue(Double.isNaN((Double) rs.getObject(1)), "Double.isNaN((Double) rs.getObject");
    assertTrue(Double.isNaN(rs.getDouble(1)), "Double.isNaN(rs.getDouble");
    try {
      rs.getBigDecimal(1);
      fail("NaN::numeric rs.getBigDecimal");
    } catch (SQLException e) {
      assertEquals(PSQLState.NUMERIC_VALUE_OUT_OF_RANGE.getState(), e.getSQLState());
      assertEquals(GT.tr("Bad value for type {0} : {1}", "BigDecimal", "NaN"), e.getMessage());
    }

    rs.close();
    stmt.close();
  }

  @Test
  public void testInfinityLiteralsSimpleStatement() throws SQLException {
    assumeMinimumServerVersion("v14 introduced 'Infinity'::numeric", ServerVersion.v14);

    String query = "SELECT 'Infinity'::numeric, 'Infinity'::real, 'Infinity'::double precision, "
        + "'-Infinity'::numeric, '-Infinity'::real, '-Infinity'::double precision";
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery(query)) {
      checkInfinityLiterals(rs);
    }
  }

  @Test
  public void testInfinityLiteralsPreparedStatement() throws SQLException {
    assumeMinimumServerVersion("v14 introduced 'Infinity'::numeric", ServerVersion.v14);

    String query = "SELECT 'Infinity'::numeric, 'Infinity'::real, 'Infinity'::double precision, "
        + "'-Infinity'::numeric, '-Infinity'::real, '-Infinity'::double precision";
    try (PreparedStatement stmt = con.prepareStatement(query);
         ResultSet rs = stmt.executeQuery()) {
      checkInfinityLiterals(rs);
    }
  }

  private static void checkInfinityLiterals(ResultSet rs) throws SQLException {
    rs.next();
    assertEquals(Double.POSITIVE_INFINITY, rs.getObject(1), "inf numeric rs.getObject");
    assertEquals(Double.POSITIVE_INFINITY, rs.getDouble(1), 0.0, "inf numeric rs.getDouble");
    assertEquals(Float.POSITIVE_INFINITY, rs.getObject(2), "inf real rs.getObject");
    assertEquals(Float.POSITIVE_INFINITY, rs.getFloat(2), 0.0, "inf real rs.getFloat");
    assertEquals(Double.POSITIVE_INFINITY, rs.getObject(3), "inf double precision rs.getObject");
    assertEquals(Double.POSITIVE_INFINITY, rs.getDouble(3), 0.0, "inf double precision rs.getDouble");

    assertEquals(Double.NEGATIVE_INFINITY, rs.getObject(4), "-inf numeric rs.getObject");
    assertEquals(Double.NEGATIVE_INFINITY, rs.getDouble(4), 0.0, "-inf numeric rs.getDouble");
    assertEquals(Float.NEGATIVE_INFINITY, rs.getObject(5), "-inf real rs.getObject");
    assertEquals(Float.NEGATIVE_INFINITY, rs.getFloat(5), 0.0, "-inf real rs.getFloat");
    assertEquals(Double.NEGATIVE_INFINITY, rs.getObject(6), "-inf double precision rs.getObject");
    assertEquals(Double.NEGATIVE_INFINITY, rs.getDouble(6), 0.0, "-inf double precision rs.getDouble");

    try {
      rs.getBigDecimal(1);
      fail("inf numeric rs.getBigDecimal");
    } catch (SQLException e) {
      assertEquals(PSQLState.NUMERIC_VALUE_OUT_OF_RANGE.getState(), e.getSQLState());
      assertEquals(GT.tr("Bad value for type {0} : {1}", "BigDecimal", "Infinity"), e.getMessage());
    }

    try {
      rs.getBigDecimal(4);
      fail("-inf numeric rs.getBigDecimal");
    } catch (SQLException e) {
      assertEquals(PSQLState.NUMERIC_VALUE_OUT_OF_RANGE.getState(), e.getSQLState());
      assertEquals(GT.tr("Bad value for type {0} : {1}", "BigDecimal", "-Infinity"), e.getMessage());
    }
  }

  @Test
  public void testSpecialSetDoubleFloat() throws SQLException {
    PreparedStatement ps = con.prepareStatement("select ?, ?, ?, ?, ?, ?");
    ps.setFloat(1, Float.NaN);
    ps.setDouble(2, Double.NaN);
    ps.setFloat(3, Float.POSITIVE_INFINITY);
    ps.setDouble(4, Double.POSITIVE_INFINITY);
    ps.setFloat(5, Float.NEGATIVE_INFINITY);
    ps.setDouble(6, Double.NEGATIVE_INFINITY);

    checkNaNParams(ps);
  }

  @Test
  public void testSpecialSetObject() throws SQLException {
    PreparedStatement ps = con.prepareStatement("select ?, ?, ?, ?, ?, ?");
    ps.setObject(1, Float.NaN);
    ps.setObject(2, Double.NaN);
    ps.setObject(3, Float.POSITIVE_INFINITY);
    ps.setObject(4, Double.POSITIVE_INFINITY);
    ps.setObject(5, Float.NEGATIVE_INFINITY);
    ps.setObject(6, Double.NEGATIVE_INFINITY);

    checkNaNParams(ps);
  }

  private static void checkNaNParams(PreparedStatement ps) throws SQLException {
    ResultSet rs = ps.executeQuery();
    rs.next();

    assertTrue(Float.isNaN((Float) rs.getObject(1)), "Float.isNaN((Float) rs.getObject");
    assertTrue(Float.isNaN(rs.getFloat(1)), "Float.isNaN(rs.getFloat");
    assertTrue(Double.isNaN((Double) rs.getObject(2)), "Double.isNaN((Double) rs.getObject");
    assertTrue(Double.isNaN(rs.getDouble(2)), "Double.isNaN(rs.getDouble");
    assertEquals(Float.POSITIVE_INFINITY, rs.getObject(3), "Float.POSITIVE_INFINITY rs.getObject");
    assertEquals(Float.POSITIVE_INFINITY, rs.getFloat(3), 0, "Float.POSITIVE_INFINITY rs.getFloat");
    assertEquals(Double.POSITIVE_INFINITY, rs.getObject(4), "Double.POSITIVE_INFINITY rs.getObject");
    assertEquals(Double.POSITIVE_INFINITY, rs.getDouble(4), 0, "Double.POSITIVE_INFINITY rs.getDouble");
    assertEquals(Float.NEGATIVE_INFINITY, rs.getObject(5), "Float.NEGATIVE_INFINITY rs.getObject");
    assertEquals(Float.NEGATIVE_INFINITY, rs.getFloat(5), 0, "Float.NEGATIVE_INFINITY rs.getFloat");
    assertEquals(Double.NEGATIVE_INFINITY, rs.getObject(6), "Double.NEGATIVE_INFINITY rs.getObject");
    assertEquals(Double.NEGATIVE_INFINITY, rs.getDouble(6), 0, "Double.NEGATIVE_INFINITY rs.getDouble");

    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(ps);
  }

  @Test
  public void testBoolean() throws SQLException {
    testBoolean(0);
    testBoolean(1);
    testBoolean(5);
    testBoolean(-1);
  }

  public void testBoolean(int prepareThreshold) throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("insert into bool_tab values (?,?,?,?,?,?,?,?)");
    ((org.postgresql.PGStatement) pstmt).setPrepareThreshold(prepareThreshold);

    // Test TRUE values
    pstmt.setBoolean(1, true);
    pstmt.setObject(1, Boolean.TRUE);
    pstmt.setNull(2, Types.BIT);
    pstmt.setObject(3, 't', Types.BIT);
    pstmt.setObject(3, 'T', Types.BIT);
    pstmt.setObject(3, "t", Types.BIT);
    pstmt.setObject(4, "true", Types.BIT);
    pstmt.setObject(5, 'y', Types.BIT);
    pstmt.setObject(5, 'Y', Types.BIT);
    pstmt.setObject(5, "Y", Types.BIT);
    pstmt.setObject(6, "YES", Types.BIT);
    pstmt.setObject(7, "On", Types.BIT);
    pstmt.setObject(8, '1', Types.BIT);
    pstmt.setObject(8, "1", Types.BIT);
    assertEquals(1, pstmt.executeUpdate(), "one row inserted, true values");
    // Test FALSE values
    pstmt.setBoolean(1, false);
    pstmt.setObject(1, Boolean.FALSE);
    pstmt.setNull(2, Types.BOOLEAN);
    pstmt.setObject(3, 'f', Types.BOOLEAN);
    pstmt.setObject(3, 'F', Types.BOOLEAN);
    pstmt.setObject(3, "F", Types.BOOLEAN);
    pstmt.setObject(4, "false", Types.BOOLEAN);
    pstmt.setObject(5, 'n', Types.BOOLEAN);
    pstmt.setObject(5, 'N', Types.BOOLEAN);
    pstmt.setObject(5, "N", Types.BOOLEAN);
    pstmt.setObject(6, "NO", Types.BOOLEAN);
    pstmt.setObject(7, "Off", Types.BOOLEAN);
    pstmt.setObject(8, "0", Types.BOOLEAN);
    pstmt.setObject(8, '0', Types.BOOLEAN);
    assertEquals(1, pstmt.executeUpdate(), "one row inserted, false values");
    // Test weird values
    pstmt.setObject(1, (byte) 0, Types.BOOLEAN);
    pstmt.setObject(2, BigDecimal.ONE, Types.BOOLEAN);
    pstmt.setObject(3, 0L, Types.BOOLEAN);
    pstmt.setObject(4, 0x1, Types.BOOLEAN);
    pstmt.setObject(5, (float) 0, Types.BOOLEAN);
    pstmt.setObject(5, 1.0d, Types.BOOLEAN);
    pstmt.setObject(5, 0.0f, Types.BOOLEAN);
    pstmt.setObject(6, Integer.valueOf("1"), Types.BOOLEAN);
    pstmt.setObject(7, new java.math.BigInteger("0"), Types.BOOLEAN);
    pstmt.clearParameters();
    pstmt.close();

    pstmt = con.prepareStatement("select * from bool_tab");
    ((org.postgresql.PGStatement) pstmt).setPrepareThreshold(prepareThreshold);
    ResultSet rs = pstmt.executeQuery();

    assertTrue(rs.next());
    assertTrue(rs.getBoolean(1), "expected true, received " + rs.getBoolean(1));
    rs.getFloat(2);
    assertTrue(rs.wasNull());
    assertTrue(rs.getBoolean(3), "expected true, received " + rs.getBoolean(3));
    assertTrue(rs.getBoolean(4), "expected true, received " + rs.getBoolean(4));
    assertTrue(rs.getBoolean(5), "expected true, received " + rs.getBoolean(5));
    assertTrue(rs.getBoolean(6), "expected true, received " + rs.getBoolean(6));
    assertTrue(rs.getBoolean(7), "expected true, received " + rs.getBoolean(7));
    assertTrue(rs.getBoolean(8), "expected true, received " + rs.getBoolean(8));

    assertTrue(rs.next());
    assertFalse(rs.getBoolean(1), "expected false, received " + rs.getBoolean(1));
    rs.getBoolean(2);
    assertTrue(rs.wasNull());
    assertFalse(rs.getBoolean(3), "expected false, received " + rs.getBoolean(3));
    assertFalse(rs.getBoolean(4), "expected false, received " + rs.getBoolean(4));
    assertFalse(rs.getBoolean(5), "expected false, received " + rs.getBoolean(5));
    assertFalse(rs.getBoolean(6), "expected false, received " + rs.getBoolean(6));
    assertFalse(rs.getBoolean(7), "expected false, received " + rs.getBoolean(7));
    assertFalse(rs.getBoolean(8), "expected false, received " + rs.getBoolean(8));

    rs.close();
    pstmt.close();

    pstmt = con.prepareStatement("TRUNCATE TABLE bool_tab");
    pstmt.executeUpdate();
    pstmt.close();
  }

  @Test
  public void testBadBoolean() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO bad_bool VALUES (?)");
    try {
      pstmt.setObject(1, "this is not boolean", Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"this is not boolean\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, 'X', Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"X\"", e.getMessage());
    }
    try {
      java.io.File obj = new java.io.File("");
      pstmt.setObject(1, obj, Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean", e.getMessage());
    }
    try {
      pstmt.setObject(1, "1.0", Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"1.0\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, "-1", Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"-1\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, "ok", Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"ok\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, 0.99f, Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"0.99\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, -0.01d, Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"-0.01\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, new java.sql.Date(0), Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean", e.getMessage());
    }
    try {
      pstmt.setObject(1, new java.math.BigInteger("1000"), Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"1000\"", e.getMessage());
    }
    try {
      pstmt.setObject(1, Math.PI, Types.BOOLEAN);
      fail();
    } catch (SQLException e) {
      assertEquals(PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"3.141592653589793\"", e.getMessage());
    }
    pstmt.close();
  }

  @Test
  public void testSetFloatInteger() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE float_tab (max_val float8, min_val float, null_val float8)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInteger = 2147483647;
    Integer minInteger = -2147483648;

    Double maxFloat = 2147483647.0;
    Double minFloat = (double) -2147483648;

    pstmt = con.prepareStatement("insert into float_tab values (?,?,?)");
    pstmt.setObject(1, maxInteger, Types.FLOAT);
    pstmt.setObject(2, minInteger, Types.FLOAT);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from float_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertEquals(maxFloat, rs.getObject(1));
    assertEquals(minFloat, rs.getObject(2));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetFloatString() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE float_tab (max_val float8, min_val float8, null_val float8)");
    pstmt.executeUpdate();
    pstmt.close();

    String maxStringFloat = "1.0E37";
    String minStringFloat = "1.0E-37";
    Double maxFloat = 1.0E37;
    Double minFloat = 1.0E-37;

    pstmt = con.prepareStatement("insert into float_tab values (?,?,?)");
    pstmt.setObject(1, maxStringFloat, Types.FLOAT);
    pstmt.setObject(2, minStringFloat, Types.FLOAT);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.setObject(1, "1.0", Types.FLOAT);
    pstmt.setObject(2, "0.0", Types.FLOAT);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from float_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertEquals(maxFloat, ((Double) rs.getObject(1)));
    assertEquals(minFloat, ((Double) rs.getObject(2)));
    assertEquals(maxFloat, rs.getDouble(1));
    assertEquals(minFloat, rs.getDouble(2));
    rs.getFloat(3);
    assertTrue(rs.wasNull());

    assertTrue(rs.next());
    assertTrue(rs.getBoolean(1));
    assertFalse(rs.getBoolean(2));

    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetFloatBigDecimal() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE float_tab (max_val float8, min_val float8, null_val float8)");
    pstmt.executeUpdate();
    pstmt.close();

    BigDecimal maxBigDecimalFloat = new BigDecimal("1.0E37");
    BigDecimal minBigDecimalFloat = new BigDecimal("1.0E-37");
    Double maxFloat = 1.0E37;
    Double minFloat = 1.0E-37;

    pstmt = con.prepareStatement("insert into float_tab values (?,?,?)");
    pstmt.setObject(1, maxBigDecimalFloat, Types.FLOAT);
    pstmt.setObject(2, minBigDecimalFloat, Types.FLOAT);
    pstmt.setNull(3, Types.FLOAT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from float_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertEquals(maxFloat, ((Double) rs.getObject(1)));
    assertEquals(minFloat, ((Double) rs.getObject(2)));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetTinyIntFloat() throws SQLException {
    PreparedStatement pstmt = con
        .prepareStatement("CREATE temp TABLE tiny_int (max_val int4, min_val int4, null_val int4)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInt = 127;
    Integer minInt = -127;
    Float maxIntFloat = 127F;
    Float minIntFloat = (float) -127;

    pstmt = con.prepareStatement("insert into tiny_int values (?,?,?)");
    pstmt.setObject(1, maxIntFloat, Types.TINYINT);
    pstmt.setObject(2, minIntFloat, Types.TINYINT);
    pstmt.setNull(3, Types.TINYINT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from tiny_int");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertEquals(maxInt, rs.getObject(1), "maxInt as rs.getObject");
    assertEquals(minInt, rs.getObject(2), "minInt as rs.getObject");
    rs.getObject(3);
    assertTrue(rs.wasNull(), "rs.wasNull after rs.getObject");
    assertEquals(maxInt, (Integer) rs.getInt(1), "maxInt as rs.getInt");
    assertEquals(minInt, (Integer) rs.getInt(2), "minInt as rs.getInt");
    rs.getInt(3);
    assertTrue(rs.wasNull(), "rs.wasNull after rs.getInt");
    assertEquals(Long.valueOf(maxInt), (Long) rs.getLong(1), "maxInt as rs.getLong");
    assertEquals(Long.valueOf(minInt), (Long) rs.getLong(2), "minInt as rs.getLong");
    rs.getLong(3);
    assertTrue(rs.wasNull(), "rs.wasNull after rs.getLong");
    assertEquals(BigDecimal.valueOf(maxInt), rs.getBigDecimal(1), "maxInt as rs.getBigDecimal");
    assertEquals(BigDecimal.valueOf(minInt), rs.getBigDecimal(2), "minInt as rs.getBigDecimal");
    assertNull(rs.getBigDecimal(3), "rs.getBigDecimal");
    assertTrue(rs.wasNull(), "rs.getBigDecimal after rs.getLong");
    assertEquals(BigDecimal.valueOf(maxInt), rs.getBigDecimal(1, 0), "maxInt as rs.getBigDecimal(scale=0)");
    assertEquals(BigDecimal.valueOf(minInt), rs.getBigDecimal(2, 0), "minInt as rs.getBigDecimal(scale=0)");
    assertNull(rs.getBigDecimal(3, 0), "rs.getBigDecimal(scale=0)");
    assertTrue(rs.wasNull(), "rs.getBigDecimal after rs.getLong");
    assertEquals(BigDecimal.valueOf(maxInt).setScale(1, RoundingMode.HALF_EVEN), rs.getBigDecimal(1, 1), "maxInt as rs.getBigDecimal(scale=1)");
    assertEquals(BigDecimal.valueOf(minInt).setScale(1, RoundingMode.HALF_EVEN), rs.getBigDecimal(2, 1), "minInt as rs.getBigDecimal(scale=1)");
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetSmallIntFloat() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE small_int (max_val int4, min_val int4, null_val int4)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInt = 32767;
    Integer minInt = -32768;
    Float maxIntFloat = 32767F;
    Float minIntFloat = (float) -32768;

    pstmt = con.prepareStatement("insert into small_int values (?,?,?)");
    pstmt.setObject(1, maxIntFloat, Types.SMALLINT);
    pstmt.setObject(2, minIntFloat, Types.SMALLINT);
    pstmt.setNull(3, Types.TINYINT);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from small_int");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertEquals(maxInt, rs.getObject(1));
    assertEquals(minInt, rs.getObject(2));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();
  }

  @Test
  public void testSetIntFloat() throws SQLException {
    PreparedStatement pstmt = con
        .prepareStatement("CREATE temp TABLE int_TAB (max_val int4, min_val int4, null_val int4)");
    pstmt.executeUpdate();
    pstmt.close();

    Integer maxInt = 1000;
    Integer minInt = -1000;
    Float maxIntFloat = 1000F;
    Float minIntFloat = (float) -1000;

    pstmt = con.prepareStatement("insert into int_tab values (?,?,?)");
    pstmt.setObject(1, maxIntFloat, Types.INTEGER);
    pstmt.setObject(2, minIntFloat, Types.INTEGER);
    pstmt.setNull(3, Types.INTEGER);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from int_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertEquals(maxInt, ((Integer) rs.getObject(1)));
    assertEquals(minInt, ((Integer) rs.getObject(2)));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetBooleanDouble() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE double_tab (max_val float, min_val float, null_val float)");
    pstmt.executeUpdate();
    pstmt.close();

    Double dBooleanTrue = 1.0;
    Double dBooleanFalse = (double) 0;

    pstmt = con.prepareStatement("insert into double_tab values (?,?,?)");
    pstmt.setObject(1, Boolean.TRUE, Types.DOUBLE);
    pstmt.setObject(2, Boolean.FALSE, Types.DOUBLE);
    pstmt.setNull(3, Types.DOUBLE);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from double_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertEquals(dBooleanTrue, rs.getObject(1));
    assertEquals(dBooleanFalse, rs.getObject(2));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetBooleanNumeric() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE numeric_tab (max_val numeric(30,15), min_val numeric(30,15), null_val numeric(30,15))");
    pstmt.executeUpdate();
    pstmt.close();

    BigDecimal dBooleanTrue = new BigDecimal(1);
    BigDecimal dBooleanFalse = new BigDecimal(0);

    pstmt = con.prepareStatement("insert into numeric_tab values (?,?,?)");
    pstmt.setObject(1, Boolean.TRUE, Types.NUMERIC, 2);
    pstmt.setObject(2, Boolean.FALSE, Types.NUMERIC, 2);
    pstmt.setNull(3, Types.DOUBLE);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from numeric_tab");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertEquals(0, ((BigDecimal) rs.getObject(1)).compareTo(dBooleanTrue));
    assertEquals(0, ((BigDecimal) rs.getObject(2)).compareTo(dBooleanFalse));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
  public void testSetBooleanDecimal() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "CREATE temp TABLE DECIMAL_TAB (max_val numeric(30,15), min_val numeric(30,15), null_val numeric(30,15))");
    pstmt.executeUpdate();
    pstmt.close();

    BigDecimal dBooleanTrue = new BigDecimal(1);
    BigDecimal dBooleanFalse = new BigDecimal(0);

    pstmt = con.prepareStatement("insert into DECIMAL_TAB values (?,?,?)");
    pstmt.setObject(1, Boolean.TRUE, Types.DECIMAL, 2);
    pstmt.setObject(2, Boolean.FALSE, Types.DECIMAL, 2);
    pstmt.setNull(3, Types.DOUBLE);
    pstmt.executeUpdate();
    pstmt.close();

    pstmt = con.prepareStatement("select * from DECIMAL_TAB");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());

    assertEquals(0, ((BigDecimal) rs.getObject(1)).compareTo(dBooleanTrue));
    assertEquals(0, ((BigDecimal) rs.getObject(2)).compareTo(dBooleanFalse));
    rs.getFloat(3);
    assertTrue(rs.wasNull());
    rs.close();
    pstmt.close();

  }

  @Test
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
    assertEquals(0, v.compareTo(rs.getBigDecimal(1)), "expected numeric set via BigDecimal " + v + " stored as " + rs.getBigDecimal(1));
    assertEquals(0, v.compareTo(rs.getBigDecimal(2)), "expected numeric set via String" + vs + " stored as " + rs.getBigDecimal(2));
    // float is really bad...
    assertEquals(0, v.compareTo(rs.getBigDecimal(3).setScale(6, RoundingMode.HALF_UP)), "expected numeric set via Float" + vf + " stored as " + rs.getBigDecimal(3));
    assertEquals(0, v.compareTo(rs.getBigDecimal(4)), "expected numeric set via Double" + vd + " stored as " + rs.getBigDecimal(4));

    rs.close();
    pstmt.close();
  }

  @Test
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
      assertEquals(0, vscaled.compareTo(rs.getBigDecimal(1)), "expected numeric set via BigDecimal " + v + " with scale " + s + " stored as " + vscaled);
      assertEquals(0, vscaled.compareTo(rs.getBigDecimal(2)), "expected numeric set via String" + vs + " with scale " + s + " stored as " + vscaled);
      assertEquals(0, vscaled.compareTo(rs.getBigDecimal(3)), "expected numeric set via Float" + vf + " with scale " + s + " stored as " + vscaled);
      assertEquals(0, vscaled.compareTo(rs.getBigDecimal(4)), "expected numeric set via Double" + vd + " with scale " + s + " stored as " + vscaled);
      rs.close();
      pstruncate.executeUpdate();
    }

    psinsert.close();
    psselect.close();
    pstruncate.close();
  }

  @Test
  public void testSetObjectWithBigDecimal() throws SQLException {
    TestUtil.createTempTable(con, "number_fallback",
            "n1 numeric");
    PreparedStatement psinsert = con.prepareStatement("insert into number_fallback values(?)");
    PreparedStatement psselect = con.prepareStatement("select n1 from number_fallback");

    psinsert.setObject(1, new BigDecimal("733"));
    psinsert.execute();

    ResultSet rs = psselect.executeQuery();
    assertTrue(rs.next());
    assertEquals(0, new BigDecimal("733").compareTo(rs.getBigDecimal(1)), "expected 733, but received " + rs.getBigDecimal(1));

    psinsert.close();
    psselect.close();
  }

  @Test
  public void testSetObjectNumberFallbackWithBigInteger() throws SQLException {
    TestUtil.createTempTable(con, "number_fallback",
            "n1 numeric");
    PreparedStatement psinsert = con.prepareStatement("insert into number_fallback values(?)");
    PreparedStatement psselect = con.prepareStatement("select n1 from number_fallback");

    psinsert.setObject(1, new BigInteger("733"));
    psinsert.execute();

    ResultSet rs = psselect.executeQuery();
    assertTrue(rs.next());
    assertEquals(0, new BigDecimal("733").compareTo(rs.getBigDecimal(1)), "expected 733, but received " + rs.getBigDecimal(1));

    psinsert.close();
    psselect.close();
  }

  @Test
  public void testSetObjectNumberFallbackWithAtomicLong() throws SQLException {
    TestUtil.createTempTable(con, "number_fallback",
            "n1 numeric");
    PreparedStatement psinsert = con.prepareStatement("insert into number_fallback values(?)");
    PreparedStatement psselect = con.prepareStatement("select n1 from number_fallback");

    psinsert.setObject(1, new AtomicLong(733));
    psinsert.execute();

    ResultSet rs = psselect.executeQuery();
    assertTrue(rs.next());
    assertEquals(0, new BigDecimal("733").compareTo(rs.getBigDecimal(1)), "expected 733, but received " + rs.getBigDecimal(1));

    psinsert.close();
    psselect.close();
  }

  @Test
  public void testUnknownSetObject() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO intervaltable(i) VALUES (?)");

    pstmt.setString(1, "1 week");
    try {
      pstmt.executeUpdate();
      assertSame(PreferQueryMode.SIMPLE, preferQueryMode, "When using extended protocol, interval vs character varying type mismatch error is expected");
    } catch (SQLException sqle) {
      // ERROR: column "i" is of type interval but expression is of type character varying
    }

    pstmt.setObject(1, "1 week", Types.OTHER);
    pstmt.executeUpdate();
    pstmt.close();
  }

  /**
   * With autoboxing this apparently happens more often now.
   */
  @Test
  public void testSetObjectCharacter() throws SQLException {
    PreparedStatement ps = con.prepareStatement("INSERT INTO texttable(te) VALUES (?)");
    ps.setObject(1, 'z');
    ps.executeUpdate();
    ps.close();
  }

  /**
   * When we have parameters of unknown type and it's not using the unnamed statement, we issue a
   * protocol level statement describe message for the V3 protocol. This test just makes sure that
   * works.
   */
  @Test
  public void testStatementDescribe() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::int");
    pstmt.setObject(1, 2, Types.OTHER);
    for (int i = 0; i < 10; i++) {
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      rs.close();
    }
    pstmt.close();
  }

  @Test
  public void testBatchWithPrepareThreshold5() throws SQLException {
    assumeBinaryModeRegular();
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE, "simple protocol only does not support prepared statement requests");

    PreparedStatement pstmt = con.prepareStatement("CREATE temp TABLE batch_tab_threshold5 (id bigint, val bigint)");
    pstmt.executeUpdate();
    pstmt.close();

    // When using a prepareThreshold of 5, a batch update should use server-side prepare
    pstmt = con.prepareStatement("INSERT INTO batch_tab_threshold5 (id, val) VALUES (?,?)");
    ((PgStatement) pstmt).setPrepareThreshold(5);
    for (int p = 0; p < 5; p++) {
      for (int i = 0; i <= 5; i++) {
        pstmt.setLong(1, i);
        pstmt.setLong(2, i);
        pstmt.addBatch();
      }
      pstmt.executeBatch();
    }
    pstmt.close();
    assumeFalse(con.unwrap(PgConnection.class).getQueryExecutor().isReWriteBatchedInsertsEnabled(), "Test assertions below support only non-rewritten insert statements");
    assertTrue(((PGStatement) pstmt).isUseServerPrepare(), "prepareThreshold=5, so the statement should be server-prepared");
    assertEquals(1, getNumberOfServerPreparedStatements("INSERT INTO batch_tab_threshold5 (id, val) VALUES ($1,$2)"), "prepareThreshold=5, so the statement should be server-prepared");
  }

  @Test
  public void testBatchWithPrepareThreshold0() throws SQLException {
    assumeBinaryModeRegular();
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE, "simple protocol only does not support prepared statement requests");

    PreparedStatement pstmt = con.prepareStatement("CREATE temp TABLE batch_tab_threshold0 (id bigint, val bigint)");
    pstmt.executeUpdate();
    pstmt.close();

    // When using a prepareThreshold of 0, a batch update should not use server-side prepare
    pstmt = con.prepareStatement("INSERT INTO batch_tab_threshold0 (id, val) VALUES (?,?)");
    ((PgStatement) pstmt).setPrepareThreshold(0);
    for (int p = 0; p < 5; p++) {
      for (int i = 0; i <= 5; i++) {
        pstmt.setLong(1, i);
        pstmt.setLong(2, i);
        pstmt.addBatch();
      }
      pstmt.executeBatch();
    }
    pstmt.close();

    assertFalse(((PGStatement) pstmt).isUseServerPrepare(), "prepareThreshold=0, so the statement should not be server-prepared");
    assertEquals(0, getNumberOfServerPreparedStatements("INSERT INTO batch_tab_threshold0 (id, val) VALUES ($1,$2)"), "prepareThreshold=0, so the statement should not be server-prepared");
  }

  @Test
  public void testSelectPrepareThreshold0AutoCommitFalseFetchSizeNonZero() throws SQLException {
    assumeBinaryModeRegular();
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE, "simple protocol only does not support prepared statement requests");

    con.setAutoCommit(false);
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    try {
      pstmt = con.prepareStatement("SELECT 42");
      ((PgStatement) pstmt).setPrepareThreshold(0);
      pstmt.setFetchSize(1);
      rs = pstmt.executeQuery();
      rs.next();
      assertEquals(42, rs.getInt(1));
    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(pstmt);
    }

    assertFalse(((PGStatement) pstmt).isUseServerPrepare(), "prepareThreshold=0, so the statement should not be server-prepared");

    assertEquals(0, getNumberOfServerPreparedStatements("SELECT 42"), "prepareThreshold=0, so the statement should not be server-prepared");
  }

  @Test
  public void testInappropriateStatementSharing() throws SQLException {
    PreparedStatement ps = con.prepareStatement("SELECT ?::timestamp");
    assertFirstParameterTypeName("after prepare ?::timestamp bind type should be timestamp", "timestamp", ps);
    try {
      Timestamp ts = new Timestamp(1474997614836L);
      // Since PreparedStatement isn't cached immediately, we need to some warm up
      for (int i = 0; i < 3; i++) {
        ResultSet rs;

        // Flip statement to use Oid.DATE
        ps.setNull(1, Types.DATE);
        assertFirstParameterTypeName("set parameter to DATE", "date", ps);
        rs = ps.executeQuery();
        assertFirstParameterTypeName("set parameter to DATE (executeQuery should not affect parameterMetadata)",
            "date", ps);
        try {
          assertTrue(rs.next());
          assertNull(rs.getObject(1), "NULL DATE converted to TIMESTAMP should return NULL value on getObject");
        } finally {
          rs.close();
        }

        // Flop statement to use Oid.UNSPECIFIED
        ps.setTimestamp(1, ts);
        assertFirstParameterTypeName("set parameter to Timestamp", "timestamp", ps);
        rs = ps.executeQuery();
        assertFirstParameterTypeName("set parameter to Timestamp (executeQuery should not affect parameterMetadata)",
            "timestamp", ps);
        try {
          assertTrue(rs.next());
          assertEquals(ts, rs.getObject(1), "Looks like we got a narrowing of the data (TIMESTAMP -> DATE). It might caused by inappropriate caching of the statement.");
        } finally {
          rs.close();
        }
      }
    } finally {
      ps.close();
    }
  }

  private void assertFirstParameterTypeName(String msg, String expected, PreparedStatement ps) throws SQLException {
    if (preferQueryMode == PreferQueryMode.SIMPLE) {
      return;
    }
    ParameterMetaData pmd = ps.getParameterMetaData();
    assertEquals(expected, pmd.getParameterTypeName(1), () -> "getParameterMetaData().getParameterTypeName(1) " + msg);
  }

  @Test
  public void testAlternatingBindType() throws SQLException {
    assumeBinaryModeForce();
    PreparedStatement ps = con.prepareStatement("SELECT /*testAlternatingBindType*/ ?");
    ResultSet rs;
    Logger log = Logger.getLogger("org.postgresql.core.v3.SimpleQuery");
    Level prevLevel = log.getLevel();
    if (prevLevel == null || prevLevel.intValue() > Level.FINER.intValue()) {
      log.setLevel(Level.FINER);
    }
    final AtomicInteger numOfReParses = new AtomicInteger();
    Handler handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        if (record.getMessage().contains("un-prepare it and parse")) {
          numOfReParses.incrementAndGet();
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() throws SecurityException {
      }
    };
    log.addHandler(handler);
    try {
      ps.setString(1, "42");
      rs = ps.executeQuery();
      rs.next();
      assertEquals("42", rs.getObject(1), "setString(1, \"42\") -> \"42\" expected");
      rs.close();

      // The bind type is flipped from VARCHAR to INTEGER, and it causes the driver to prepare statement again
      ps.setNull(1, Types.INTEGER);
      rs = ps.executeQuery();
      rs.next();
      assertNull(rs.getObject(1), "setNull(1, Types.INTEGER) -> null expected");
      assertEquals(1, numOfReParses.get(), "A re-parse was expected, so the number of parses should be 1");
      rs.close();

      // The bind type is flipped from INTEGER to VARCHAR, and it causes the driver to prepare statement again
      ps.setString(1, "42");
      rs = ps.executeQuery();
      rs.next();
      assertEquals("42", rs.getObject(1), "setString(1, \"42\") -> \"42\" expected");
      assertEquals(2, numOfReParses.get(), "One more re-parse is expected, so the number of parses should be 2");
      rs.close();

      // Types.OTHER null is sent as UNSPECIFIED, and pgjdbc does not re-parse on UNSPECIFIED nulls
      // Note: do not rely on absence of re-parse on using Types.OTHER. Try using consistent data types
      ps.setNull(1, Types.OTHER);
      rs = ps.executeQuery();
      rs.next();
      assertNull(rs.getObject(1), "setNull(1, Types.OTHER) -> null expected");
      assertEquals(2, numOfReParses.get(), "setNull(, Types.OTHER) should not cause re-parse");

      // Types.INTEGER null is sent as int4 null, and it leads to re-parse
      ps.setNull(1, Types.INTEGER);
      rs = ps.executeQuery();
      rs.next();
      assertNull(rs.getObject(1), "setNull(1, Types.INTEGER) -> null expected");
      assertEquals(3, numOfReParses.get(), "setNull(, Types.INTEGER) causes re-parse");
      rs.close();
    } finally {
      TestUtil.closeQuietly(ps);
      log.removeHandler(handler);
      log.setLevel(prevLevel);
    }
  }

  @Test
  public void testNoParametersNPE() throws SQLException {
    try {
      PreparedStatement ps = con.prepareStatement("select 1");
      ps.setString(1, "null");
    } catch ( NullPointerException ex ) {
      fail("Should throw a SQLException");
    } catch (SQLException ex) {
      // ignore
    }
  }

  @Test
  public void testBatchSelect() throws SQLException {
    PreparedStatement pstmt = null;
    PreparedStatement batchSelect = null;
    ResultSet rs = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO inttable (a) VALUES (?);"
              + "INSERT INTO inttable (a) VALUES (?);"
              + "INSERT INTO inttable (a) VALUES (?);"
              + "INSERT INTO inttable (a) VALUES (?);");
      ((PgStatement) pstmt).setPrepareThreshold(0);
      for (int i = 0; i < 4; i++) {
        pstmt.setInt(i + 1, i + 1);
      }
      pstmt.execute();
      batchSelect =  con.prepareStatement("select * from inttable where a = ?;"
          + "select * from inttable where a = ?;"
          + "select * from inttable where a = ?;");
      for (int i = 0; i < 3; i++) {
        batchSelect.setInt(i + 1, i + 1);
      }

      List<Integer> results = new ArrayList<>();
      boolean hasResults = batchSelect.execute();
      while (hasResults) {
        rs = batchSelect.getResultSet();
        while (rs.next()) {
          results.add(rs.getInt(1));
        }
        hasResults = batchSelect.getMoreResults();
      }
      assertEquals(Arrays.asList(1, 2, 3), results);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    } finally {
      TestUtil.closeQuietly(rs);
      TestUtil.closeQuietly(pstmt);
      TestUtil.closeQuietly(batchSelect);
    }
  }

  @Test
  public void testBatchSelectWithPrepareThreshold1() throws SQLException {
    assumeBinaryModeRegular();
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE, "simple protocol only does not support prepared statement requests");

    PreparedStatement pstmt =  con.prepareStatement("select * from inttable where a = ?;"
        + "select * from inttable where a = ?;"
        + "select * from inttable where a = ?;");
    ((PgStatement) pstmt).setPrepareThreshold(1);
    for (int i = 0; i < 3; i++) {
      pstmt.setInt(i + 1, i + 1);
    }
    pstmt.execute();
    pstmt.close();
    assumeFalse(con.unwrap(PgConnection.class).getQueryExecutor().isReWriteBatchedInsertsEnabled(),"Test assertions below support only non-rewritten insert statements");
    assertTrue(((PGStatement) pstmt).isUseServerPrepare(), "prepareThreshold=1, so the statement should be server-prepared");
    assertEquals(1, getNumberOfServerPreparedStatements("select * from inttable where a = $1"));
  }

  @Test
  public void testBatchSelectWithDifferentParams() throws SQLException {
    ResultSet rs;
    PreparedStatement stmt = con.prepareStatement("INSERT INTO inttable (a) VALUES (?);"
        + "INSERT INTO inttable (a) VALUES (?);"
        + "INSERT INTO inttable (a) VALUES (?);"
        + "INSERT INTO inttable (a) VALUES (?);");
    ((PgStatement) stmt).setPrepareThreshold(0);
    for (int i = 0; i < 4; i++) {
      stmt.setInt(i + 1, i + 1);
    }
    stmt.execute();
    PreparedStatement pstmt =  con.prepareStatement("select * from inttable where a = ?;"
        + "select * from inttable where a = ?;"
        + "select * from inttable where a = ?;");
    ((PgStatement) pstmt).setPrepareThreshold(1);
    pstmt.setInt(1, 1);
    pstmt.setDouble(2, 2.0);
    pstmt.setFloat(3, 3.0F);
    List<Integer> results = new ArrayList<>();
    boolean hasResults = pstmt.execute();
    while (hasResults) {
      rs = pstmt.getResultSet();
      while (rs.next()) {
        results.add(rs.getInt(1));
      }
      hasResults = pstmt.getMoreResults();
    }
    assertEquals(Arrays.asList(1, 2, 3), results);
    pstmt.close();
  }
}
