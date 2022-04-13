/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.stream.Collectors;

/*
 * Some simple tests based on problems reported by users. Hopefully these will help prevent previous
 * problems from re-occurring ;-)
 *
 */
@RunWith(Parameterized.class)
public class DateTest {
  private static final TimeZone saveTZ = TimeZone.getDefault();

  private Connection con;

  public DateTest(String zoneId) {
    TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
  }

  @Parameterized.Parameters(name = "zoneId = {0}")
  public static Iterable<Object[]> data() {
    List<String> ids = new ArrayList<>();
    ids.add("Africa/Casablanca");
    ids.add("America/New_York");
    ids.add("America/Toronto");
    ids.add("Atlantic/Azores");
    ids.add("Europe/Berlin");
    ids.add("Europe/Moscow");
    ids.add("Pacific/Apia");
    ids.add("Pacific/Niue");
    for (int i = -12; i <= 13; i++) {
      ids.add(String.format("GMT%+02d", i));
    }
    return ids.stream().map(id -> new Object[] { id }).collect(Collectors.toList());
  }

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTable(con, "testdate", "dt date");
    TestUtil.createTable(con, "testts", "dt timestamp");
    TestUtil.createTable(con, "testtstz", "dt timestamptz");
  }

  @After
  public void tearDown() throws Exception {
    TimeZone.setDefault(saveTZ);
    TestUtil.dropTable(con, "testdate");
    TestUtil.dropTable(con, "testts");
    TestUtil.dropTable(con, "testtstz");
    TestUtil.closeDB(con);
  }

  /*
   * Tests the time methods in ResultSet
   */
  @Test
  public void testGetDate() throws SQLException {
    for (String table : Arrays.asList("testdate", "testts", "testtstz")) {
      Properties properties = new Properties();
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

  /*
   * Tests the time methods in PreparedStatement
   */
  @Test
  public void testSetDate() throws SQLException {
    for (String table : Arrays.asList("testdate", "testts", "testtstz")) {
      Properties properties = new Properties();
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
    assertEquals(table, makeDate(1950, 2, 7), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1970, 6, 2), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1999, 8, 11), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(2001, 2, 13), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1950, 4, 2), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1970, 11, 30), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1988, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(2003, 7, 9), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1934, 2, 28), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1969, 4, 3), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1982, 8, 3), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(2012, 3, 15), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1912, 5, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1971, 12, 15), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1984, 12, 3), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(2000, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(3456, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(-100, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(1, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(0, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(table, makeDate(0, 12, 31), d);

    assertTrue(!rs.next());

    rs.close();
    st.close();
  }

  private java.sql.Date makeDate(int y, int m, int d) {
    return new java.sql.Date(y - 1900, m - 1, d);
  }
}
