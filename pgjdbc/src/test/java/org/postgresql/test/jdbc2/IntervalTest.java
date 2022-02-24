/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PGInterval;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;

public class IntervalTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.createTable(conn, "testinterval", "v interval");
    TestUtil.createTable(conn, "testdate", "v date");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(conn, "testinterval");
    TestUtil.dropTable(conn, "testdate");

    TestUtil.closeDB(conn);
  }

  @Test
  public void testOnlineTests() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO testinterval VALUES (?)");
    pstmt.setObject(1, new PGInterval(2004, 13, 28, 0, 0, 43000.9013));
    pstmt.executeUpdate();
    pstmt.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT v FROM testinterval");
    assertTrue(rs.next());
    PGInterval pgi = (PGInterval) rs.getObject(1);
    assertEquals(2005, pgi.getYears());
    assertEquals(1, pgi.getMonths());
    assertEquals(28, pgi.getDays());
    assertEquals(11, pgi.getHours());
    assertEquals(56, pgi.getMinutes());
    assertEquals(40.9013, pgi.getSeconds(), 0.000001);
    assertTrue(!rs.next());
    rs.close();
    stmt.close();
  }

  @Test
  public void testStringToIntervalCoercion() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2010-01-01'"));
    stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2010-01-02'"));
    stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2010-01-04'"));
    stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2010-01-05'"));
    stmt.close();

    PreparedStatement pstmt = conn.prepareStatement(
        "SELECT v FROM testdate WHERE v < (?::timestamp with time zone + ? * ?::interval) ORDER BY v");
    pstmt.setObject(1, makeDate(2010, 1, 1));
    pstmt.setObject(2, Integer.valueOf(2));
    pstmt.setObject(3, "1 day");
    ResultSet rs = pstmt.executeQuery();

    assertNotNull(rs);

    java.sql.Date d;

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(2010, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(2010, 1, 2), d);

    assertFalse(rs.next());

    rs.close();
    pstmt.close();
  }

  @Test
  public void testIntervalToStringCoercion() throws SQLException {
    PGInterval interval = new PGInterval("1 year 3 months");
    String coercedStringValue = interval.toString();

    assertEquals("1 years 3 mons 0 days 0 hours 0 mins 0.0 secs", coercedStringValue);
  }

  @Test
  public void testDaysHours() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT '101:12:00'::interval");
    assertTrue(rs.next());
    PGInterval i = (PGInterval) rs.getObject(1);
    // 8.1 servers store hours and days separately.
    assertEquals(0, i.getDays());
    assertEquals(101, i.getHours());

    assertEquals(12, i.getMinutes());
  }

  @Test
  public void testAddRounding() {
    PGInterval pgi = new PGInterval(0, 0, 0, 0, 0, 0.6006);
    Calendar cal = Calendar.getInstance();
    long origTime = cal.getTime().getTime();
    pgi.add(cal);
    long newTime = cal.getTime().getTime();
    assertEquals(601, newTime - origTime);
    pgi.setSeconds(-0.6006);
    pgi.add(cal);
    assertEquals(origTime, cal.getTime().getTime());
  }

  @Test
  public void testOfflineTests() throws Exception {
    PGInterval pgi = new PGInterval(2004, 4, 20, 15, 57, 12.1);

    assertEquals(2004, pgi.getYears());
    assertEquals(4, pgi.getMonths());
    assertEquals(20, pgi.getDays());
    assertEquals(15, pgi.getHours());
    assertEquals(57, pgi.getMinutes());
    assertEquals(12.1, pgi.getSeconds(), 0);

    PGInterval pgi2 = new PGInterval("@ 2004 years 4 mons 20 days 15 hours 57 mins 12.1 secs");
    assertEquals(pgi, pgi2);

    // Singular units
    PGInterval pgi3 = new PGInterval("@ 2004 year 4 mon 20 day 15 hour 57 min 12.1 sec");
    assertEquals(pgi, pgi3);

    PGInterval pgi4 = new PGInterval("2004 years 4 mons 20 days 15:57:12.1");
    assertEquals(pgi, pgi4);

    // Ago test
    pgi = new PGInterval("@ 2004 years 4 mons 20 days 15 hours 57 mins 12.1 secs ago");
    assertEquals(-2004, pgi.getYears());
    assertEquals(-4, pgi.getMonths());
    assertEquals(-20, pgi.getDays());
    assertEquals(-15, pgi.getHours());
    assertEquals(-57, pgi.getMinutes());
    assertEquals(-12.1, pgi.getSeconds(), 0);

    // Char test
    pgi = new PGInterval("@ +2004 years -4 mons +20 days -15 hours +57 mins -12.1 secs");
    assertEquals(2004, pgi.getYears());
    assertEquals(-4, pgi.getMonths());
    assertEquals(20, pgi.getDays());
    assertEquals(-15, pgi.getHours());
    assertEquals(57, pgi.getMinutes());
    assertEquals(-12.1, pgi.getSeconds(), 0);

    // Unjustified interval test
    pgi = new PGInterval("@ 0 years 0 mons 0 days 900 hours 0 mins 0.00 secs");
    assertEquals(0, pgi.getYears());
    assertEquals(0, pgi.getMonths());
    assertEquals(0, pgi.getDays());
    assertEquals(900, pgi.getHours());
    assertEquals(0, pgi.getMinutes());
    assertEquals(0, pgi.getSeconds(), 0);
  }

  private Calendar getStartCalendar() {
    Calendar cal = new GregorianCalendar();
    cal.set(Calendar.YEAR, 2005);
    cal.set(Calendar.MONTH, 4);
    cal.set(Calendar.DAY_OF_MONTH, 29);
    cal.set(Calendar.HOUR_OF_DAY, 15);
    cal.set(Calendar.MINUTE, 35);
    cal.set(Calendar.SECOND, 42);
    cal.set(Calendar.MILLISECOND, 100);

    return cal;
  }

  @Test
  public void testCalendar() throws Exception {
    Calendar cal = getStartCalendar();

    PGInterval pgi = new PGInterval("@ 1 year 1 mon 1 day 1 hour 1 minute 1 secs");
    pgi.add(cal);

    assertEquals(2006, cal.get(Calendar.YEAR));
    assertEquals(5, cal.get(Calendar.MONTH));
    assertEquals(30, cal.get(Calendar.DAY_OF_MONTH));
    assertEquals(16, cal.get(Calendar.HOUR_OF_DAY));
    assertEquals(36, cal.get(Calendar.MINUTE));
    assertEquals(43, cal.get(Calendar.SECOND));
    assertEquals(100, cal.get(Calendar.MILLISECOND));

    pgi = new PGInterval("@ 1 year 1 mon 1 day 1 hour 1 minute 1 secs ago");
    pgi.add(cal);

    assertEquals(2005, cal.get(Calendar.YEAR));
    assertEquals(4, cal.get(Calendar.MONTH));
    assertEquals(29, cal.get(Calendar.DAY_OF_MONTH));
    assertEquals(15, cal.get(Calendar.HOUR_OF_DAY));
    assertEquals(35, cal.get(Calendar.MINUTE));
    assertEquals(42, cal.get(Calendar.SECOND));
    assertEquals(100, cal.get(Calendar.MILLISECOND));

    cal = getStartCalendar();

    pgi = new PGInterval("@ 1 year -23 hours -3 mins -3.30 secs");
    pgi.add(cal);

    assertEquals(2006, cal.get(Calendar.YEAR));
    assertEquals(4, cal.get(Calendar.MONTH));
    assertEquals(28, cal.get(Calendar.DAY_OF_MONTH));
    assertEquals(16, cal.get(Calendar.HOUR_OF_DAY));
    assertEquals(32, cal.get(Calendar.MINUTE));
    assertEquals(38, cal.get(Calendar.SECOND));
    assertEquals(800, cal.get(Calendar.MILLISECOND));

    pgi = new PGInterval("@ 1 year -23 hours -3 mins -3.30 secs ago");
    pgi.add(cal);

    assertEquals(2005, cal.get(Calendar.YEAR));
    assertEquals(4, cal.get(Calendar.MONTH));
    assertEquals(29, cal.get(Calendar.DAY_OF_MONTH));
    assertEquals(15, cal.get(Calendar.HOUR_OF_DAY));
    assertEquals(35, cal.get(Calendar.MINUTE));
    assertEquals(42, cal.get(Calendar.SECOND));
    assertEquals(100, cal.get(Calendar.MILLISECOND));
  }

  @Test
  public void testDate() throws Exception {
    Date date = getStartCalendar().getTime();
    Date date2 = getStartCalendar().getTime();

    PGInterval pgi = new PGInterval("@ +2004 years -4 mons +20 days -15 hours +57 mins -12.1 secs");
    pgi.add(date);

    PGInterval pgi2 =
        new PGInterval("@ +2004 years -4 mons +20 days -15 hours +57 mins -12.1 secs ago");
    pgi2.add(date);

    assertEquals(date2, date);
  }

  @Test
  public void testPostgresDate() throws Exception {
    Date date = getStartCalendar().getTime();
    Date date2 = getStartCalendar().getTime();

    PGInterval pgi = new PGInterval("+2004 years -4 mons +20 days -15:57:12.1");
    pgi.add(date);

    PGInterval pgi2 = new PGInterval("-2004 years 4 mons -20 days 15:57:12.1");
    pgi2.add(date);

    assertEquals(date2, date);
  }

  @Test
  public void testISO8601() throws Exception {
    PGInterval pgi = new PGInterval("P1Y2M3DT4H5M6S");
    assertEquals(1, pgi.getYears() );
    assertEquals(2, pgi.getMonths() );
    assertEquals(3, pgi.getDays() );
    assertEquals(4, pgi.getHours() );
    assertEquals( 5, pgi.getMinutes() );
    assertEquals( 6, pgi.getSeconds(), .1 );

    pgi = new PGInterval("P-1Y2M3DT4H5M6S");
    assertEquals(-1, pgi.getYears());

    pgi = new PGInterval("P1Y2M");
    assertEquals(1,pgi.getYears());
    assertEquals(2, pgi.getMonths());
    assertEquals(0, pgi.getDays());

    pgi = new PGInterval("P3DT4H5M6S");
    assertEquals(0,pgi.getYears());

    pgi = new PGInterval("P-1Y-2M3DT-4H-5M-6S");
    assertEquals(-1, pgi.getYears());
    assertEquals(-2, pgi.getMonths());
    assertEquals(-4, pgi.getHours());

    pgi = new PGInterval("PT6.123456S");
    assertEquals(6.123456, pgi.getSeconds(), .0);
    assertEquals(6, pgi.getWholeSeconds());
    assertEquals(123456, pgi.getMicroSeconds());

    pgi = new PGInterval("PT-6.123456S");
    assertEquals(-6.123456, pgi.getSeconds(), .0);
    assertEquals(-6, pgi.getWholeSeconds());
    assertEquals(-123456, pgi.getMicroSeconds());
  }

  @Test
  public void testSmallValue() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO testinterval VALUES (?)");
    pstmt.setObject(1, new PGInterval("0.0001 seconds"));
    pstmt.executeUpdate();
    pstmt.close();

    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT v FROM testinterval");
    assertTrue(rs.next());
    PGInterval pgi = (PGInterval) rs.getObject(1);
    assertEquals(0, pgi.getYears());
    assertEquals(0, pgi.getMonths());
    assertEquals(0, pgi.getDays());
    assertEquals(0, pgi.getHours());
    assertEquals(0, pgi.getMinutes());
    assertEquals(0, pgi.getWholeSeconds());
    assertEquals(100, pgi.getMicroSeconds());
    assertFalse(rs.next());
    rs.close();
    stmt.close();
  }

  @Test
  public void testGetValueForSmallValue() throws SQLException {
    PGInterval orig = new PGInterval("0.0001 seconds");
    PGInterval copy = new PGInterval(orig.getValue());

    assertEquals(orig, copy);
  }

  @Test
  public void testGetValueForSmallValueWithCommaAsDecimalSeparatorInDefaultLocale() throws SQLException {
    Locale originalLocale = Locale.getDefault();
    Locale.setDefault(Locale.GERMANY);
    try {
      PGInterval orig = new PGInterval("0.0001 seconds");
      PGInterval copy = new PGInterval(orig.getValue());

      assertEquals(orig, copy);
    } finally {
      Locale.setDefault(originalLocale);
    }
  }

  @Test
  public void testGetSecondsForSmallValue() throws SQLException {
    PGInterval pgi = new PGInterval("0.000001 seconds");

    assertEquals(0.000001, pgi.getSeconds(), 0.000000001);
  }

  @Test
  public void testMicroSecondsAreRoundedToNearest() throws SQLException {
    PGInterval pgi = new PGInterval("0.0000007 seconds");

    assertEquals(1, pgi.getMicroSeconds());
  }

  private java.sql.Date makeDate(int y, int m, int d) {
    return new java.sql.Date(y - 1900, m - 1, d);
  }
}
