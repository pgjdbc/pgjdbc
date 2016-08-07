/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGobject;

import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;

/*
 * ResultSet tests.
 */
public class ResultSetTest extends BaseTest4 {
  @Override
  public void setUp() throws Exception {
    super.setUp();
    Statement stmt = con.createStatement();

    TestUtil.createTable(con, "testrs", "id integer");

    stmt.executeUpdate("INSERT INTO testrs VALUES (1)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (2)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (3)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (4)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (6)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (9)");

    TestUtil.createTable(con, "teststring", "a text");
    stmt.executeUpdate("INSERT INTO teststring VALUES ('12345')");

    TestUtil.createTable(con, "testint", "a int");
    stmt.executeUpdate("INSERT INTO testint VALUES (12345)");

    TestUtil.createTable(con, "testbool", "a boolean");

    // TestUtil.createTable(con, "testbit", "a bit");

    TestUtil.createTable(con, "testboolstring", "a varchar(30)");

    stmt.executeUpdate("INSERT INTO testboolstring VALUES('true')");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('false')");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('t')");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('f')");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('1.0')");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('0.0')");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('TRUE')");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('this is not true')");

    TestUtil.createTable(con, "testnumeric", "a numeric");
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('1.0')");
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('0.0')");
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('-1.0')");
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('1.2')");
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('-2.5')");
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('99999.2')");
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('99999')");
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('-99999.2')");
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('-99999')");

    // Integer.MaxValue
    stmt.execute("INSERT INTO testnumeric VALUES('2147483647')");

    // Integer.MinValue
    stmt.execute("INSERT INTO testnumeric VALUES('-2147483648')");

    stmt.executeUpdate("INSERT INTO testnumeric VALUES('2147483648')");
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('-2147483649')");

    // Long.MaxValue
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('9223372036854775807')");

    // Long.MinValue
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('-9223372036854775808')");

    stmt.executeUpdate("INSERT INTO testnumeric VALUES('9223372036854775808')");
    stmt.executeUpdate("INSERT INTO testnumeric VALUES('-9223372036854775809')");

    TestUtil.createTable(con, "testpgobject", "id integer NOT NULL, d date, PRIMARY KEY (id)");
    stmt.execute("INSERT INTO testpgobject VALUES(1, '2010-11-3')");

    stmt.close();
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testrs");
    TestUtil.dropTable(con, "teststring");
    TestUtil.dropTable(con, "testint");
    TestUtil.dropTable(con, "testbool");
    // TestUtil.dropTable(con, "testbit");
    TestUtil.dropTable(con, "testboolstring");
    TestUtil.dropTable(con, "testnumeric");
    TestUtil.dropTable(con, "testpgobject");
    super.tearDown();
  }

  @Test
  public void testBackward() throws SQLException {
    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    ResultSet rs = stmt.executeQuery("SELECT * FROM testrs");
    rs.afterLast();
    assertTrue(rs.previous());
    rs.close();
    stmt.close();
  }

  @Test
  public void testAbsolute() throws SQLException {
    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    ResultSet rs = stmt.executeQuery("SELECT * FROM testrs");

    assertTrue(!rs.absolute(0));
    assertEquals(0, rs.getRow());

    assertTrue(rs.absolute(-1));
    assertEquals(6, rs.getRow());

    assertTrue(rs.absolute(1));
    assertEquals(1, rs.getRow());

    assertTrue(!rs.absolute(-10));
    assertEquals(0, rs.getRow());
    assertTrue(rs.next());
    assertEquals(1, rs.getRow());

    assertTrue(!rs.absolute(10));
    assertEquals(0, rs.getRow());
    assertTrue(rs.previous());
    assertEquals(6, rs.getRow());

    stmt.close();
  }

  @Test
  public void testEmptyResult() throws SQLException {
    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    ResultSet rs = stmt.executeQuery("SELECT * FROM testrs where id=100");
    rs.beforeFirst();
    rs.afterLast();
    assertTrue(!rs.first());
    assertTrue(!rs.last());
    assertTrue(!rs.next());
  }

  @Test
  public void testMaxFieldSize() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.setMaxFieldSize(2);

    ResultSet rs = stmt.executeQuery("select * from testint");

    // max should not apply to the following since per the spec
    // it should apply only to binary and char/varchar columns
    rs.next();
    assertEquals("12345", rs.getString(1));
    // getBytes returns 5 bytes for txt transfer, 4 for bin transfer
    assertTrue(rs.getBytes(1).length >= 4);

    // max should apply to the following since the column is
    // a varchar column
    rs = stmt.executeQuery("select * from teststring");
    rs.next();
    assertEquals("12", rs.getString(1));
    assertEquals("12", new String(rs.getBytes(1)));
  }

  public void booleanTests(boolean useServerPrepare) throws SQLException {
    java.sql.PreparedStatement pstmt = con.prepareStatement("insert into testbool values (?)");
    if (useServerPrepare) {
      ((org.postgresql.PGStatement) pstmt).setUseServerPrepare(true);
    }

    pstmt.setObject(1, new Float(0), java.sql.Types.BIT);
    pstmt.executeUpdate();

    pstmt.setObject(1, new Float(1), java.sql.Types.BIT);
    pstmt.executeUpdate();

    pstmt.setObject(1, "False", java.sql.Types.BIT);
    pstmt.executeUpdate();

    pstmt.setObject(1, "True", java.sql.Types.BIT);
    pstmt.executeUpdate();

    ResultSet rs = con.createStatement().executeQuery("select * from testbool");
    for (int i = 0; i < 2; i++) {
      assertTrue(rs.next());
      assertEquals(false, rs.getBoolean(1));
      assertTrue(rs.next());
      assertEquals(true, rs.getBoolean(1));
    }

    /*
     * pstmt = con.prepareStatement("insert into testbit values (?)");
     *
     * pstmt.setObject(1, new Float(0), java.sql.Types.BIT); pstmt.executeUpdate();
     *
     * pstmt.setObject(1, new Float(1), java.sql.Types.BIT); pstmt.executeUpdate();
     *
     * pstmt.setObject(1, "false", java.sql.Types.BIT); pstmt.executeUpdate();
     *
     * pstmt.setObject(1, "true", java.sql.Types.BIT); pstmt.executeUpdate();
     *
     * rs = con.createStatement().executeQuery("select * from testbit");
     *
     * for (int i = 0;i<2; i++) { assertTrue(rs.next()); assertEquals(false, rs.getBoolean(1));
     * assertTrue(rs.next()); assertEquals(true, rs.getBoolean(1)); }
     */

    rs = con.createStatement().executeQuery("select * from testboolstring");

    for (int i = 0; i < 4; i++) {
      assertTrue(rs.next());
      assertEquals(true, rs.getBoolean(1));
      assertTrue(rs.next());
      assertEquals(false, rs.getBoolean(1));
    }
  }

  @Test
  public void testBoolean() throws SQLException {
    booleanTests(true);
    booleanTests(false);
  }

  @Test
  public void testgetByte() throws SQLException {
    ResultSet rs = con.createStatement().executeQuery("select * from testnumeric");

    assertTrue(rs.next());
    assertEquals(1, rs.getByte(1));

    assertTrue(rs.next());
    assertEquals(0, rs.getByte(1));

    assertTrue(rs.next());
    assertEquals(-1, rs.getByte(1));

    assertTrue(rs.next());
    assertEquals(1, rs.getByte(1));

    assertTrue(rs.next());
    assertEquals(-2, rs.getByte(1));

    while (rs.next()) {
      try {
        rs.getByte(1);
        fail("Exception expected.");
      } catch (Exception e) {
      }
    }
    rs.close();
  }

  @Test
  public void testgetShort() throws SQLException {
    ResultSet rs = con.createStatement().executeQuery("select * from testnumeric");

    assertTrue(rs.next());
    assertEquals(1, rs.getShort(1));

    assertTrue(rs.next());
    assertEquals(0, rs.getShort(1));

    assertTrue(rs.next());
    assertEquals(-1, rs.getShort(1));

    assertTrue(rs.next());
    assertEquals(1, rs.getShort(1));

    assertTrue(rs.next());
    assertEquals(-2, rs.getShort(1));

    while (rs.next()) {
      try {
        rs.getShort(1);
        fail("Exception expected.");
      } catch (Exception e) {
      }
    }
    rs.close();
  }

  @Test
  public void testgetInt() throws SQLException {
    ResultSet rs = con.createStatement().executeQuery("select * from testnumeric");

    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(0, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(-1, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(-2, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(99999, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(99999, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(-99999, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(-99999, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(Integer.MAX_VALUE, rs.getInt(1));

    assertTrue(rs.next());
    assertEquals(Integer.MIN_VALUE, rs.getInt(1));

    while (rs.next()) {
      try {
        rs.getInt(1);
        fail("Exception expected." + rs.getString(1));
      } catch (Exception e) {
      }
    }
    rs.close();
  }

  @Test
  public void testgetLong() throws SQLException {
    ResultSet rs = con.createStatement().executeQuery("select * from testnumeric");

    assertTrue(rs.next());
    assertEquals(1, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(0, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(-1, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(1, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(-2, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(99999, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(99999, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(-99999, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(-99999, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals((Integer.MAX_VALUE), rs.getLong(1));

    assertTrue(rs.next());
    assertEquals((Integer.MIN_VALUE), rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(((long) Integer.MAX_VALUE) + 1, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(((long) Integer.MIN_VALUE) - 1, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(Long.MAX_VALUE, rs.getLong(1));

    assertTrue(rs.next());
    assertEquals(Long.MIN_VALUE, rs.getLong(1));

    while (rs.next()) {
      try {
        rs.getLong(1);
        fail("Exception expected." + rs.getString(1));
      } catch (Exception e) {
      }
    }
    rs.close();
  }

  @Test
  public void testParameters() throws SQLException {
    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
    stmt.setFetchSize(100);
    stmt.setFetchDirection(ResultSet.FETCH_UNKNOWN);

    ResultSet rs = stmt.executeQuery("SELECT * FROM testrs");

    assertEquals(ResultSet.CONCUR_UPDATABLE, stmt.getResultSetConcurrency());
    assertEquals(ResultSet.TYPE_SCROLL_SENSITIVE, stmt.getResultSetType());
    assertEquals(100, stmt.getFetchSize());
    assertEquals(ResultSet.FETCH_UNKNOWN, stmt.getFetchDirection());

    assertEquals(ResultSet.CONCUR_UPDATABLE, rs.getConcurrency());
    assertEquals(ResultSet.TYPE_SCROLL_SENSITIVE, rs.getType());
    assertEquals(100, rs.getFetchSize());
    assertEquals(ResultSet.FETCH_UNKNOWN, rs.getFetchDirection());

    rs.close();
    stmt.close();
  }

  @Test
  public void testZeroRowResultPositioning() throws SQLException {
    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs =
        stmt.executeQuery("SELECT * FROM pg_database WHERE datname='nonexistantdatabase'");
    assertTrue(!rs.previous());
    assertTrue(!rs.previous());
    assertTrue(!rs.next());
    assertTrue(!rs.next());
    assertTrue(!rs.next());
    assertTrue(!rs.next());
    assertTrue(!rs.next());
    assertTrue(!rs.previous());
    assertTrue(!rs.first());
    assertTrue(!rs.last());
    assertEquals(0, rs.getRow());
    assertTrue(!rs.absolute(1));
    assertTrue(!rs.relative(1));
    assertTrue(!rs.isBeforeFirst());
    assertTrue(!rs.isAfterLast());
    assertTrue(!rs.isFirst());
    assertTrue(!rs.isLast());
    rs.close();
    stmt.close();
  }

  @Test
  public void testRowResultPositioning() throws SQLException {
    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    // Create a one row result set.
    ResultSet rs = stmt.executeQuery("SELECT * FROM pg_database WHERE datname='template1'");

    assertTrue(rs.isBeforeFirst());
    assertTrue(!rs.isAfterLast());
    assertTrue(!rs.isFirst());
    assertTrue(!rs.isLast());

    assertTrue(rs.next());

    assertTrue(!rs.isBeforeFirst());
    assertTrue(!rs.isAfterLast());
    assertTrue(rs.isFirst());
    assertTrue(rs.isLast());

    assertTrue(!rs.next());

    assertTrue(!rs.isBeforeFirst());
    assertTrue(rs.isAfterLast());
    assertTrue(!rs.isFirst());
    assertTrue(!rs.isLast());

    assertTrue(rs.previous());

    assertTrue(!rs.isBeforeFirst());
    assertTrue(!rs.isAfterLast());
    assertTrue(rs.isFirst());
    assertTrue(rs.isLast());

    assertTrue(rs.absolute(1));

    assertTrue(!rs.isBeforeFirst());
    assertTrue(!rs.isAfterLast());
    assertTrue(rs.isFirst());
    assertTrue(rs.isLast());

    assertTrue(!rs.absolute(0));

    assertTrue(rs.isBeforeFirst());
    assertTrue(!rs.isAfterLast());
    assertTrue(!rs.isFirst());
    assertTrue(!rs.isLast());

    assertTrue(!rs.absolute(2));

    assertTrue(!rs.isBeforeFirst());
    assertTrue(rs.isAfterLast());
    assertTrue(!rs.isFirst());
    assertTrue(!rs.isLast());

    rs.close();
    stmt.close();
  }

  @Test
  public void testForwardOnlyExceptions() throws SQLException {
    // Test that illegal operations on a TYPE_FORWARD_ONLY resultset
    // correctly result in throwing an exception.
    Statement stmt = con.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    ResultSet rs = stmt.executeQuery("SELECT * FROM testnumeric");

    try {
      rs.absolute(1);
      fail("absolute() on a TYPE_FORWARD_ONLY resultset did not throw an exception");
    } catch (SQLException e) {
    }
    try {
      rs.afterLast();
      fail(
          "afterLast() on a TYPE_FORWARD_ONLY resultset did not throw an exception on a TYPE_FORWARD_ONLY resultset");
    } catch (SQLException e) {
    }
    try {
      rs.beforeFirst();
      fail("beforeFirst() on a TYPE_FORWARD_ONLY resultset did not throw an exception");
    } catch (SQLException e) {
    }
    try {
      rs.first();
      fail("first() on a TYPE_FORWARD_ONLY resultset did not throw an exception");
    } catch (SQLException e) {
    }
    try {
      rs.last();
      fail("last() on a TYPE_FORWARD_ONLY resultset did not throw an exception");
    } catch (SQLException e) {
    }
    try {
      rs.previous();
      fail("previous() on a TYPE_FORWARD_ONLY resultset did not throw an exception");
    } catch (SQLException e) {
    }
    try {
      rs.relative(1);
      fail("relative() on a TYPE_FORWARD_ONLY resultset did not throw an exception");
    } catch (SQLException e) {
    }

    try {
      rs.setFetchDirection(ResultSet.FETCH_REVERSE);
      fail(
          "setFetchDirection(FETCH_REVERSE) on a TYPE_FORWARD_ONLY resultset did not throw an exception");
    } catch (SQLException e) {
    }

    try {
      rs.setFetchDirection(ResultSet.FETCH_UNKNOWN);
      fail(
          "setFetchDirection(FETCH_UNKNOWN) on a TYPE_FORWARD_ONLY resultset did not throw an exception");
    } catch (SQLException e) {
    }

    rs.close();
    stmt.close();
  }

  @Test
  public void testCaseInsensitiveFindColumn() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT id, id AS \"ID2\" FROM testrs");
    assertEquals(1, rs.findColumn("id"));
    assertEquals(1, rs.findColumn("ID"));
    assertEquals(1, rs.findColumn("Id"));
    assertEquals(2, rs.findColumn("id2"));
    assertEquals(2, rs.findColumn("ID2"));
    assertEquals(2, rs.findColumn("Id2"));
    try {
      rs.findColumn("id3");
      fail("There isn't an id3 column in the ResultSet.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  public void testGetOutOfBounds() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT id FROM testrs");
    assertTrue(rs.next());

    try {
      rs.getInt(-9);
    } catch (SQLException sqle) {
    }

    try {
      rs.getInt(1000);
    } catch (SQLException sqle) {
    }
  }

  @Test
  public void testClosedResult() throws SQLException {
    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = stmt.executeQuery("SELECT id FROM testrs");
    rs.close();

    rs.close(); // Closing twice is allowed.
    try {
      rs.getInt(1);
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
    try {
      rs.getInt("id");
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
    try {
      rs.getType();
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
    try {
      rs.wasNull();
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
    try {
      rs.absolute(3);
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
    try {
      rs.isBeforeFirst();
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
    try {
      rs.setFetchSize(10);
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
    try {
      rs.getMetaData();
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
    try {
      rs.rowUpdated();
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
    try {
      rs.updateInt(1, 1);
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
    try {
      rs.moveToInsertRow();
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
    try {
      rs.clearWarnings();
      fail("Expected SQLException");
    } catch (SQLException e) {
    }
  }

  /*
   * The JDBC spec says when you have duplicate column names, the first one should be returned.
   */
  @Test
  public void testDuplicateColumnNameOrder() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT 1 AS a, 2 AS a");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt("a"));
  }

  @Test
  public void testTurkishLocale() throws SQLException {
    Locale current = Locale.getDefault();
    try {
      Locale.setDefault(new Locale("tr", "TR"));
      Statement stmt = con.createStatement();
      ResultSet rs = stmt.executeQuery("SELECT id FROM testrs");
      int sum = 0;
      while (rs.next()) {
        sum += rs.getInt("ID");
      }
      rs.close();
      assertEquals(25, sum);
    } finally {
      Locale.setDefault(current);
    }
  }

  @Test
  public void testUpdateWithPGobject() throws SQLException {
    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);

    ResultSet rs = stmt.executeQuery("select * from testpgobject where id = 1");
    assertTrue(rs.next());
    assertEquals("2010-11-03", rs.getDate("d").toString());

    PGobject pgobj = new PGobject();
    pgobj.setType("date");
    pgobj.setValue("2014-12-23");
    rs.updateObject("d", pgobj);
    rs.updateRow();
    rs.close();

    ResultSet rs1 = stmt.executeQuery("select * from testpgobject where id = 1");
    assertTrue(rs1.next());
    assertEquals("2014-12-23", rs1.getDate("d").toString());
    rs1.close();

    stmt.close();
  }

  /**
   * Test the behavior of the result set column mapping cache for simple statements.
   */
  @Test
  public void testStatementResultSetColumnMappingCache() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select * from testrs");
    Map<String, Integer> columnNameIndexMap;
    columnNameIndexMap = getResultSetColumnNameIndexMap(rs);
    assertEquals(null, columnNameIndexMap);
    assertTrue(rs.next());
    columnNameIndexMap = getResultSetColumnNameIndexMap(rs);
    assertEquals(null, columnNameIndexMap);
    rs.getInt("ID");
    columnNameIndexMap = getResultSetColumnNameIndexMap(rs);
    assertNotNull(columnNameIndexMap);
    rs.getInt("id");
    assertSame(columnNameIndexMap, getResultSetColumnNameIndexMap(rs));
    rs.close();
    rs = stmt.executeQuery("select * from testrs");
    columnNameIndexMap = getResultSetColumnNameIndexMap(rs);
    assertEquals(null, columnNameIndexMap);
    assertTrue(rs.next());
    rs.getInt("Id");
    columnNameIndexMap = getResultSetColumnNameIndexMap(rs);
    assertNotNull(columnNameIndexMap);
    rs.close();
    stmt.close();
  }

  /**
   * Test the behavior of the result set column mapping cache for prepared statements.
   */
  @Test
  public void testPreparedStatementResultSetColumnMappingCache() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT id FROM testrs");
    ResultSet rs = pstmt.executeQuery();
    Map<String, Integer> columnNameIndexMap;
    columnNameIndexMap = getResultSetColumnNameIndexMap(rs);
    assertEquals(null, columnNameIndexMap);
    assertTrue(rs.next());
    columnNameIndexMap = getResultSetColumnNameIndexMap(rs);
    assertEquals(null, columnNameIndexMap);
    rs.getInt("id");
    columnNameIndexMap = getResultSetColumnNameIndexMap(rs);
    assertNotNull(columnNameIndexMap);
    rs.close();
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    columnNameIndexMap = getResultSetColumnNameIndexMap(rs);
    assertEquals(null, columnNameIndexMap);
    rs.getInt("id");
    columnNameIndexMap = getResultSetColumnNameIndexMap(rs);
    assertNotNull(columnNameIndexMap);
    rs.close();
    pstmt.close();
  }

  /**
   * Test the behavior of the result set column mapping cache for prepared statements once the
   * statement is named.
   */
  @Test
  public void testNamedPreparedStatementResultSetColumnMappingCache() throws SQLException {
    Assume.assumeTrue("Simple protocol only mode does not support server-prepared statements",
        preferQueryMode != PreferQueryMode.SIMPLE);
    PreparedStatement pstmt = con.prepareStatement("SELECT id FROM testrs");
    ResultSet rs;
    // Make sure the prepared statement is named.
    // This ensures column mapping cache is reused across different result sets.
    for (int i = 0; i < 5; i++) {
      rs = pstmt.executeQuery();
      rs.close();
    }
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    rs.getInt("id");
    Map<String, Integer> columnNameIndexMap;
    columnNameIndexMap = getResultSetColumnNameIndexMap(rs);
    assertNotNull(columnNameIndexMap);
    rs.close();
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    rs.getInt("id");
    assertSame(
        "Cached mapping should be same between different result sets of same named prepared statement",
        columnNameIndexMap, getResultSetColumnNameIndexMap(rs));
    rs.close();
    pstmt.close();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Integer> getResultSetColumnNameIndexMap(ResultSet stmt) {
    try {
      Field columnNameIndexMapField = stmt.getClass().getDeclaredField("columnNameIndexMap");
      columnNameIndexMapField.setAccessible(true);
      return (Map<String, Integer>) columnNameIndexMapField.get(stmt);
    } catch (Exception e) {
    }
    return null;
  }

}
