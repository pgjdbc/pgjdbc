/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.postgresql.jdbc.TimestampUtils.createProlepticGregorianCalendar;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PGInterval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ThreadLocalRandom;

@Isolated("Uses Locale.setDefault")
class IntervalTest {
  private Connection conn;

  @BeforeEach
  void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.createTable(conn, "testinterval", "v interval");
    TestUtil.createTable(conn, "testdate", "v date");
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.dropTable(conn, "testinterval");
    TestUtil.dropTable(conn, "testdate");

    TestUtil.closeDB(conn);
  }

  @Test
  void onlineTests() throws SQLException {
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
    assertFalse(rs.next());
    rs.close();
    stmt.close();
  }

  @Test
  void stringToIntervalCoercion() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2010-01-01'"));
    stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2010-01-02'"));
    stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2010-01-04'"));
    stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2010-01-05'"));
    stmt.close();

    PreparedStatement pstmt = conn.prepareStatement(
        "SELECT v FROM testdate WHERE v < (?::timestamp with time zone + ? * ?::interval) ORDER BY v");
    pstmt.setObject(1, makeDate(2010, 1, 1));
    pstmt.setObject(2, 2);
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
  void intervalToStringCoercion() throws SQLException {
    PGInterval interval = new PGInterval("1 year 3 months");
    String coercedStringValue = interval.toString();

    assertEquals("1 years 3 mons", coercedStringValue);
  }

  @Test
  void checkCapitalization() throws Exception {
    PGInterval pgi = new PGInterval("1 year 3 months 4 days 5 hours 6 minutes");
    PGInterval yCapital = new PGInterval("1 Year 3 months 4 days 5 hours 6 minutes");
    PGInterval mCapital = new PGInterval("1 year 3 Months 4 days 5 hours 6 minutes");
    PGInterval dCapital = new PGInterval("1 year 3 months 4 Days 5 hours 6 minutes");
    PGInterval hCapital = new PGInterval("1 year 3 months 4 days 5 Hours 6 minutes");
    PGInterval minCapital = new PGInterval("1 year 3 months 4 days 5 hours 6 Minutes");

    assertEquals(pgi, yCapital);
    assertEquals(pgi, mCapital);
    assertEquals(pgi, dCapital);
    assertEquals(pgi, hCapital);
    assertEquals(pgi, minCapital);
  }

  @Test
  void daysHours() throws SQLException {
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
  void addRounding() {
    PGInterval pgi = new PGInterval(0, 0, 0, 0, 0, 0.6006);
    Calendar cal = createProlepticGregorianCalendar(TimeZone.getDefault());
    long origTime = cal.getTime().getTime();
    pgi.add(cal);
    long newTime = cal.getTime().getTime();
    assertEquals(601, newTime - origTime);
    pgi.setSeconds(-0.6006);
    pgi.add(cal);
    assertEquals(origTime, cal.getTime().getTime());
  }

  @Test
  void offlineTests() throws Exception {
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

  private static Calendar getStartCalendar() {
    Calendar cal = createProlepticGregorianCalendar(TimeZone.getDefault());
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
  void calendar() throws Exception {
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
  void date() throws Exception {
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
  void dateYear1000() throws Exception {
    final Calendar calYear1000 = createProlepticGregorianCalendar(TimeZone.getDefault());
    calYear1000.clear();
    calYear1000.set(1000, Calendar.JANUARY, 1);

    final Calendar calYear2000 = createProlepticGregorianCalendar(TimeZone.getDefault());
    calYear2000.clear();
    calYear2000.set(2000, Calendar.JANUARY, 1);

    final Date date = calYear1000.getTime();
    final Date dateYear2000 = calYear2000.getTime();

    PGInterval pgi = new PGInterval("@ +1000 years");
    pgi.add(date);

    assertEquals(dateYear2000, date);
  }

  @Test
  void postgresDate() throws Exception {
    Date date = getStartCalendar().getTime();
    Date date2 = getStartCalendar().getTime();

    PGInterval pgi = new PGInterval("+2004 years -4 mons +20 days -15:57:12.1");
    pgi.add(date);

    PGInterval pgi2 = new PGInterval("-2004 years 4 mons -20 days 15:57:12.1");
    pgi2.add(date);

    assertEquals(date2, date);
  }

  @Test
  void iSO8601() throws Exception {
    PGInterval pgi = new PGInterval("P1Y2M3DT4H5M6S");
    assertEquals(1, pgi.getYears());
    assertEquals(2, pgi.getMonths());
    assertEquals(3, pgi.getDays());
    assertEquals(4, pgi.getHours());
    assertEquals(5, pgi.getMinutes());
    assertEquals(6, pgi.getSeconds(), .1);

    pgi = new PGInterval("P-1Y2M3DT4H5M6S");
    assertEquals(-1, pgi.getYears());

    pgi = new PGInterval("P1Y2M");
    assertEquals(1, pgi.getYears());
    assertEquals(2, pgi.getMonths());
    assertEquals(0, pgi.getDays());

    pgi = new PGInterval("P3DT4H5M6S");
    assertEquals(0, pgi.getYears());

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
  void smallValue() throws SQLException {
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
  void getValueForSmallValue() throws SQLException {
    PGInterval orig = new PGInterval("0.0001 seconds");
    PGInterval copy = new PGInterval(orig.getValue());

    assertEquals(orig, copy);
  }

  @Test
  void getValueForSmallValueWithCommaAsDecimalSeparatorInDefaultLocale() throws SQLException {
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
  void getSecondsForSmallValue() throws SQLException {
    PGInterval pgi = new PGInterval("0.000001 seconds");

    assertEquals(0.000001, pgi.getSeconds(), 0.000000001);
  }

  @Test
  void randomIntervalsRoundtripTest() throws SQLException {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    for (int i = 0; i < 1000000; i++) {
      // PostgreSQL interval limits are -178000000..178000000 years
      int years = random.nextInt(-177000000, 177000000);
      int months = random.nextInt(-10000, 10000);
      int days = random.nextInt(-10000, 10000);
      int hours = random.nextInt(-10000, 10000);
      int minutes = random.nextInt(-10000, 10000);
      double seconds = random.nextDouble(-100000, 100000);
      try {
        assertIntervalGetValue(years, months, days, hours, minutes, seconds);
      } catch (AssertionError e) {
        throw e;
      } catch (Throwable t) {
        throw new AssertionError(
            "Failed to test interval " + years + " years " + months + " months " + days + " days "
                + hours + " hours " + minutes + " minutes " + seconds + " seconds",
            t);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      doubles = {
          1.9999998,
          1.9999997,
          1.9999996,
          1.9999995,
          1.9999994
      }
  )
  void edgeCaseSecondsTest(double seconds) throws SQLException {
    assertIntervalGetValue(0, 0, 0, 0, 0, seconds);
    assertIntervalGetValue(0, 0, 0, 0, 0, -seconds);
  }

  private static void assertIntervalGetValue(int years, int months, int days, int hours, int minutes, double seconds) throws SQLException {
    PGInterval original = new PGInterval(years, months, days, hours, minutes, seconds);
    assertPGIntervalSeconds(original, seconds);
    PGInterval copy = new PGInterval(original.getValue());
    assertEquals(original, copy,
        () -> "years: " + years + ", months: " + months + ", days: " + days
            + ", hours: " + hours + ", minutes: " + minutes + ", seconds: " + seconds
            + "; Copy: years: " + copy.getYears() + ", months: " + copy.getMonths() + ", days: " + copy.getDays()
            + ", hours: " + copy.getHours() + ", minutes: " + copy.getMinutes() + ", seconds: " + copy.getSeconds());
  }

  private static void assertPGIntervalSeconds(PGInterval original, double seconds) {
    assertEquals(original.getSeconds(), seconds, 0.00000051, () -> "PGInterval(seconds= " + seconds + ").getSeconds()");
  }

  @Test
  void secondEdgeCasesTest() {
    for (int prefix = 0; prefix < 6; prefix++) {
      for (int suffix = 0; suffix < 6 - prefix; suffix++) {
        for (int wholeSeconds = 0; wholeSeconds < 2; wholeSeconds++) {
          for (int sign = -1; sign <= 1; sign += 2) {
            String microsPart = "123456".substring(0, 6 - prefix - suffix);
            int micros = Integer.parseInt(microsPart + "000000".substring(0, suffix));
            double seconds = (wholeSeconds + micros / 1_000_000.0) * sign;
            PGInterval interval = new PGInterval(0, 0, 0, 0, 0, seconds);
            assertPGIntervalSeconds(interval, seconds);
            String result = interval.getValue();

            String expectedValue =
                (sign == -1 ? "-" : "") + wholeSeconds + "." + "000000".substring(0, prefix) + microsPart + " secs";
            assertEquals(expectedValue, result, () -> "Input seconds: " + seconds);
          }
        }
      }
    }
  }

  @Test
  void microSecondsAreRoundedToNearest() throws SQLException {
    PGInterval pgi = new PGInterval("0.0000007 seconds");

    assertEquals(1, pgi.getMicroSeconds());
  }

  private static java.sql.Date makeDate(int year, int month, int day) {
    Calendar cal = createProlepticGregorianCalendar(TimeZone.getDefault());
    cal.clear();
    // Note that Calendar.MONTH is zero based
    cal.set(year, month - 1, day);

    return new java.sql.Date(cal.getTimeInMillis());
  }

}
