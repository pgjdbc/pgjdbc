/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/*
 * Some simple tests based on problems reported by users. Hopefully these will help prevent previous
 * problems from re-occurring ;-)
 *
 */
@RunWith(Parameterized.class)
public class DateTest extends BaseTest4 {
  private static final TimeZone saveTZ = TimeZone.getDefault();

  private final String type;
  private final String zoneId;

  public DateTest(String type, String zoneId, BinaryMode binaryMode) {
    this.type = type;
    this.zoneId = zoneId;
    TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "type = {0}, zoneId = {1}, binary = {2}")
  public static Iterable<Object[]> data() {
    final List<Object[]> data = new ArrayList<>();
    for (String type : Arrays.asList("date", "timestamp", "timestamptz")) {
      Stream<String> tzIds = Stream.of("Africa/Casablanca", "America/New_York", "America/Toronto",
          "Europe/Berlin", "Europe/Moscow", "Pacific/Apia", "America/Los_Angeles");
      // some selection of static GMT offsets (not all, as this takes too long):
      tzIds = Stream.concat(tzIds, IntStream.of(-12, -11, -5, -1, 0, 1, 3, 12, 13)
          .mapToObj(i -> String.format(Locale.ROOT, "GMT%+02d", i)));
      for (String tzId : (Iterable<String>) tzIds::iterator) {
        for (BinaryMode binaryMode : BinaryMode.values()) {
          data.add(new Object[]{type, tzId, binaryMode});
        }
      }
    }
    return data;
  }

  @Before
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "test", "dt ".concat(type));
  }

  @After
  public void tearDown() throws SQLException {
    TimeZone.setDefault(saveTZ);
    TestUtil.dropTable(con, "test");
    super.tearDown();
  }

  /*
   * Tests the time methods in ResultSet
   */
  @Test
  public void testGetDate() throws SQLException {
    assumeTrue("TODO: Test fails on some server versions with local time zones (not GMT based)",
        false == Objects.equals(type, "timestamptz") || zoneId.startsWith("GMT"));
    try (Statement stmt = con.createStatement()) {
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1950-02-07'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1970-06-02'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1999-08-11'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'2001-02-13'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1950-04-02'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1970-11-30'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1988-01-01'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'2003-07-09'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1934-02-28'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1969-04-03'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1982-08-03'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'2012-03-15'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1912-05-01'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1971-12-15'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'1984-12-03'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'2000-01-01'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'3456-01-01'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'0101-01-01 BC'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'0001-01-01'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'0001-01-01 BC'")));
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("test", "'0001-12-31 BC'")));

      /* dateTest() contains all of the tests */
      dateTest();

      assertEquals(21, stmt.executeUpdate("DELETE FROM test"));
    }
  }

  /*
   * Tests the time methods in PreparedStatement
   */
  @Test
  public void testSetDate() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      PreparedStatement ps = con.prepareStatement(TestUtil.insertSQL("test", "?"));

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

      dateTest();

      assertEquals(21, stmt.executeUpdate("DELETE FROM test"));
    }
  }

  /*
   * Helper for the date tests. It tests what should be in the db
   */
  private void dateTest() throws SQLException {
    Statement st = con.createStatement();
    ResultSet rs;
    java.sql.Date d;

    rs = st.executeQuery(TestUtil.selectSQL("test", "dt"));
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
