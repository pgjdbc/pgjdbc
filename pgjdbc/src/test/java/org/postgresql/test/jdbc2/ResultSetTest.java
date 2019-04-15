/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGobject;

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

    // Boolean Tests
    TestUtil.createTable(con, "testboolstring", "a varchar(30), b boolean");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('1 ', true)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('0', false)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES(' t', true)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('f', false)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('True', true)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('      False   ', false)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('yes', true)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('  no  ', false)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('y', true)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('n', false)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('oN', true)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('oFf', false)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('OK', null)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('NOT', null)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('not a boolean', null)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('1.0', null)");
    stmt.executeUpdate("INSERT INTO testboolstring VALUES('0.0', null)");

    TestUtil.createTable(con, "testboolfloat", "a float4, b boolean");
    stmt.executeUpdate("INSERT INTO testboolfloat VALUES('1.0'::real, true)");
    stmt.executeUpdate("INSERT INTO testboolfloat VALUES('0.0'::real, false)");
    stmt.executeUpdate("INSERT INTO testboolfloat VALUES(1.000::real, true)");
    stmt.executeUpdate("INSERT INTO testboolfloat VALUES(0.000::real, false)");
    stmt.executeUpdate("INSERT INTO testboolfloat VALUES('1.001'::real, null)");
    stmt.executeUpdate("INSERT INTO testboolfloat VALUES('-1.001'::real, null)");
    stmt.executeUpdate("INSERT INTO testboolfloat VALUES(123.4::real, null)");
    stmt.executeUpdate("INSERT INTO testboolfloat VALUES(1.234e2::real, null)");
    stmt.executeUpdate("INSERT INTO testboolfloat VALUES(100.00e-2::real, true)");

    TestUtil.createTable(con, "testboolint", "a bigint, b boolean");
    stmt.executeUpdate("INSERT INTO testboolint VALUES(1, true)");
    stmt.executeUpdate("INSERT INTO testboolint VALUES(0, false)");
    stmt.executeUpdate("INSERT INTO testboolint VALUES(-1, null)");
    stmt.executeUpdate("INSERT INTO testboolint VALUES(9223372036854775807, null)");
    stmt.executeUpdate("INSERT INTO testboolint VALUES(-9223372036854775808, null)");
    // End Boolean Tests

    // TestUtil.createTable(con, "testbit", "a bit");

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
    // TestUtil.dropTable(con, "testbit");
    TestUtil.dropTable(con, "testboolstring");
    TestUtil.dropTable(con, "testboolfloat");
    TestUtil.dropTable(con, "testboolint");
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
  public void testRelative() throws SQLException {
    Statement stmt =
        con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    ResultSet rs = stmt.executeQuery("SELECT * FROM testrs");

    assertTrue(!rs.relative(0));
    assertEquals(0, rs.getRow());
    assertTrue(rs.isBeforeFirst());

    assertTrue(rs.relative(2));
    assertEquals(2, rs.getRow());

    assertTrue(rs.relative(1));
    assertEquals(3, rs.getRow());

    assertTrue(rs.relative(0));
    assertEquals(3, rs.getRow());

    assertTrue(!rs.relative(-3));
    assertEquals(0, rs.getRow());
    assertTrue(rs.isBeforeFirst());

    assertTrue(rs.relative(4));
    assertEquals(4, rs.getRow());

    assertTrue(rs.relative(-1));
    assertEquals(3, rs.getRow());

    assertTrue(!rs.relative(6));
    assertEquals(0, rs.getRow());
    assertTrue(rs.isAfterLast());

    assertTrue(rs.relative(-4));
    assertEquals(3, rs.getRow());

    assertTrue(!rs.relative(-6));
    assertEquals(0, rs.getRow());
    assertTrue(rs.isBeforeFirst());

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

  @Test
  public void testBooleanString() throws SQLException {
    testBoolean("testboolstring", 0);
    testBoolean("testboolstring", 1);
    testBoolean("testboolstring", 5);
    testBoolean("testboolstring", -1);
  }

  @Test
  public void testBooleanFloat() throws SQLException {
    testBoolean("testboolfloat", 0);
    testBoolean("testboolfloat", 1);
    testBoolean("testboolfloat", 5);
    testBoolean("testboolfloat", -1);
  }

  @Test
  public void testBooleanInt() throws SQLException {
    testBoolean("testboolint", 0);
    testBoolean("testboolint", 1);
    testBoolean("testboolint", 5);
    testBoolean("testboolint", -1);
  }

  public void testBoolean(String table, int prepareThreshold) throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("select a, b from " + table);
    ((org.postgresql.PGStatement) pstmt).setPrepareThreshold(prepareThreshold);
    ResultSet rs = pstmt.executeQuery();
    while (rs.next()) {
      rs.getBoolean(2);
      Boolean expected = rs.wasNull() ? null : rs.getBoolean(2); // Hack to get SQL NULL
      if (expected != null) {
        assertEquals(expected, rs.getBoolean(1));
      } else {
        // expected value with null are bad values
        try {
          rs.getBoolean(1);
          fail();
        } catch (SQLException e) {
          assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
        }
      }
    }
    rs.close();
    pstmt.close();
  }

  @Test
  public void testgetBooleanJDBCCompliance() throws SQLException {
    // The JDBC specification in Table B-6 "Use of ResultSet getter Methods to Retrieve JDBC Data Types"
    // the getBoolean have this Supported JDBC Type: TINYINT, SMALLINT, INTEGER, BIGINT, REAL, FLOAT,
    // DOUBLE, DECIAML, NUMERIC, BIT, BOOLEAN, CHAR, VARCHAR, LONGVARCHAR

    // There is no TINYINT in PostgreSQL
    testgetBoolean("int2"); // SMALLINT
    testgetBoolean("int4"); // INTEGER
    testgetBoolean("int8"); // BIGINT
    testgetBoolean("float4"); // REAL
    testgetBoolean("float8"); // FLOAT, DOUBLE
    testgetBoolean("numeric"); // DECIMAL, NUMERIC
    testgetBoolean("bpchar"); // CHAR
    testgetBoolean("varchar"); // VARCHAR
    testgetBoolean("text"); // LONGVARCHAR?
  }

  public void testgetBoolean(String dataType) throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select 1::" + dataType + ", 0::" + dataType + ", 2::" + dataType);
    assertTrue(rs.next());
    assertEquals(true, rs.getBoolean(1));
    assertEquals(false, rs.getBoolean(2));

    try {
      // The JDBC ResultSet JavaDoc states that only 1 and 0 are valid values, so 2 should return error.
      rs.getBoolean(3);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"2\"", e.getMessage());
    }
    rs.close();
    stmt.close();
  }

  @Test
  public void testgetBadBoolean() throws SQLException {
    testBadBoolean("'2017-03-13 14:25:48.130861'::timestamp", "2017-03-13 14:25:48.130861");
    testBadBoolean("'2017-03-13'::date", "2017-03-13");
    testBadBoolean("'2017-03-13 14:25:48.130861'::time", "14:25:48.130861");
    testBadBoolean("ARRAY[[1,0],[0,1]]", "{{1,0},{0,1}}");
    testBadBoolean("29::bit(4)", "1101");
  }

  @Test
  public void testGetBadUuidBoolean() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3));
    testBadBoolean("'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid", "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");
  }

  public void testBadBoolean(String select, String value) throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select " + select);
    assertTrue(rs.next());
    try {
      rs.getBoolean(1);
      fail();
    } catch (SQLException e) {
      assertEquals(org.postgresql.util.PSQLState.CANNOT_COERCE.getState(), e.getSQLState());
      assertEquals("Cannot cast to boolean: \"" + value + "\"", e.getMessage());
    }
    rs.close();
    stmt.close();
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
    assumeTrue("Simple protocol only mode does not support server-prepared statements",
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
