/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.Oid;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/*
 * Some simple tests based on problems reported by users. Hopefully these will help prevent previous
 * problems from re-occurring ;-)
 *
 */
public class DateTest {
  private Connection con;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTable(con, "testdate", "dt date");
    TestUtil.createTable(con, "testtz", "dt timestamptz");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(con, "testdate");
    TestUtil.dropTable(con, "testtz");
    TestUtil.closeDB(con);
  }

  /*
   * Tests the time methods in ResultSet
   */
  @Test
  public void testGetDate() throws SQLException {
    for (String table : new String[] {"testdate", "testtz"}) {
      for (boolean binaryTransfer : new boolean[] {false, true}) {
        Properties properties = new Properties();
        if (binaryTransfer) {
          properties.setProperty(
              PGProperty.BINARY_TRANSFER_ENABLE.getName(), String.format("%d", Oid.DATE));
        }
        try (Connection con = TestUtil.openDB(properties)) {
          Statement stmt = con.createStatement();

          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1950-02-07'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1970-06-02'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1999-08-11'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'2001-02-13'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1950-04-02'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1970-11-30'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1988-01-01'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'2003-07-09'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1934-02-28'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1969-04-03'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1982-08-03'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'2012-03-15'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1912-05-01'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1971-12-15'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'1984-12-03'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'2000-01-01'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'3456-01-01'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'0101-01-01 BC'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'0001-01-01'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'0001-01-01 BC'")));
          assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(table, "'0001-12-31 BC'")));

          /* dateTest() contains all of the tests */
          dateTest(table);

          assertEquals(21, stmt.executeUpdate("DELETE FROM " + table));
          stmt.close();
        }
      }
    }
  }

  /*
   * Tests the time methods in PreparedStatement
   */
  @Test
  public void testSetDate() throws SQLException {
    for (String table : new String[] {"testdate", "testtz"}) {
      for (boolean binaryTransfer : new boolean[] {false, true}) {
        Properties properties = new Properties();
        if (binaryTransfer) {
          properties.setProperty(
              PGProperty.BINARY_TRANSFER_ENABLE.getName(), String.format("%d", Oid.DATE));
        }
        try (Connection con = TestUtil.openDB(properties)) {
          Statement stmt = con.createStatement();
          PreparedStatement ps = con.prepareStatement(TestUtil.insertSQL(table, "?"));

          ps.setDate(1, makeDate(1950, 2, 7));
          assertEquals(1, ps.executeUpdate());

          ps.setDate(1, makeDate(1970, 6, 2));
          assertEquals(1, ps.executeUpdate());

          ps.setDate(1, makeDate(1999, 8, 11));
          assertEquals(1, ps.executeUpdate());

          ps.setDate(1, makeDate(2001, 2, 13));
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, java.sql.Timestamp.valueOf("1950-04-02 12:00:00"), java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, java.sql.Timestamp.valueOf("1970-11-30 3:00:00"), java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, java.sql.Timestamp.valueOf("1988-01-01 13:00:00"), java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, java.sql.Timestamp.valueOf("2003-07-09 12:00:00"), java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, "1934-02-28", java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, "1969-04-03", java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, "1982-08-03", java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, "2012-03-15", java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, java.sql.Date.valueOf("1912-05-01"), java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, java.sql.Date.valueOf("1971-12-15"), java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, java.sql.Date.valueOf("1984-12-03"), java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, java.sql.Date.valueOf("2000-01-01"), java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, java.sql.Date.valueOf("3456-01-01"), java.sql.Types.DATE);
          assertEquals(1, ps.executeUpdate());

          // We can't use valueOf on BC dates.
          ps.setObject(1, makeDate(-100, 1, 1));
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, makeDate(1, 1, 1));
          assertEquals(1, ps.executeUpdate());

          // Note: Year 0 in Java is year '0001-01-01 BC' in PostgreSQL.
          ps.setObject(1, makeDate(0, 1, 1));
          assertEquals(1, ps.executeUpdate());

          ps.setObject(1, makeDate(0, 12, 31));
          assertEquals(1, ps.executeUpdate());

          ps.close();

          dateTest(table);

          assertEquals(21, stmt.executeUpdate("DELETE FROM " + table));
          stmt.close();
        }
      }
    }
  }

  /*
   * Helper for the date tests. It tests what should be in the db
   */
  private void dateTest(String table) throws SQLException {
    Statement st = con.createStatement();
    ResultSet rs;
    java.sql.Date d;

    rs = st.executeQuery(TestUtil.selectSQL(table, "dt"));
    assertNotNull(rs);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1950, 2, 7), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1970, 6, 2), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1999, 8, 11), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(2001, 2, 13), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1950, 4, 2), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1970, 11, 30), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1988, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(2003, 7, 9), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1934, 2, 28), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1969, 4, 3), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1982, 8, 3), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(2012, 3, 15), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1912, 5, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1971, 12, 15), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1984, 12, 3), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(2000, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(3456, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(-100, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(0, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(0, 12, 31), d);

    assertTrue(!rs.next());

    rs.close();
    st.close();
  }

  private java.sql.Date makeDate(int y, int m, int d) {
    return new java.sql.Date(y - 1900, m - 1, d);
  }
}
