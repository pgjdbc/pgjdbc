/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.postgresql.PGStatement;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.TimestampUtils;
import org.postgresql.test.TestUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/*
 * Test get/setTimestamp for both timestamp with time zone and timestamp without time zone datatypes
 * TODO: refactor to a property-based testing or paremeterized testing somehow so adding new times
 *  don't require to add constants and setters/getters. JUnit 5 would probably help here.
 */
@RunWith(Parameterized.class)
public class TimestampTest extends BaseTest4 {

  public TimestampTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  private TimeZone currentTZ;

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, TSWTZ_TABLE, "ts timestamp with time zone");
    TestUtil.createTable(con, TSWOTZ_TABLE, "ts timestamp without time zone");
    TestUtil.createTable(con, DATE_TABLE, "ts date");
    currentTZ = TimeZone.getDefault();
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, TSWTZ_TABLE);
    TestUtil.dropTable(con, TSWOTZ_TABLE);
    TestUtil.dropTable(con, DATE_TABLE);
    TimeZone.setDefault(currentTZ);
    super.tearDown();
  }

  /**
   * Ensure the driver doesn't modify a Calendar that is passed in.
   */
  @Test
  public void testCalendarModification() throws SQLException {
    Calendar cal = Calendar.getInstance();
    Calendar origCal = (Calendar) cal.clone();
    PreparedStatement ps = con.prepareStatement("INSERT INTO " + TSWOTZ_TABLE + " VALUES (?)");

    ps.setDate(1, new Date(0), cal);
    ps.executeUpdate();
    assertEquals(origCal, cal);

    ps.setTimestamp(1, new Timestamp(0), cal);
    ps.executeUpdate();
    assertEquals(origCal, cal);

    ps.setTime(1, new Time(0), cal);
    // Can't actually execute this one because of type mismatch,
    // but all we're really concerned about is the set call.
    // ps.executeUpdate();
    assertEquals(origCal, cal);

    ps.close();
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT ts FROM " + TSWOTZ_TABLE);
    assertTrue(rs.next());

    rs.getDate(1, cal);
    assertEquals(origCal, cal);

    rs.getTimestamp(1, cal);
    assertEquals(origCal, cal);

    rs.getTime(1, cal);
    assertEquals(origCal, cal);

    rs.close();
    stmt.close();
  }

  @Test
  public void testInfinity() throws SQLException {
    runInfinityTests(TSWTZ_TABLE, PGStatement.DATE_POSITIVE_INFINITY);
    runInfinityTests(TSWTZ_TABLE, PGStatement.DATE_NEGATIVE_INFINITY);
    runInfinityTests(TSWOTZ_TABLE, PGStatement.DATE_POSITIVE_INFINITY);
    runInfinityTests(TSWOTZ_TABLE, PGStatement.DATE_NEGATIVE_INFINITY);
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4)) {
      runInfinityTests(DATE_TABLE, PGStatement.DATE_POSITIVE_INFINITY);
      runInfinityTests(DATE_TABLE, PGStatement.DATE_NEGATIVE_INFINITY);
    }
  }

  private void runInfinityTests(String table, long value) throws SQLException {
    GregorianCalendar cal = new GregorianCalendar();
    // Pick some random timezone that is hopefully different than ours
    // and exists in this JVM.
    cal.setTimeZone(TimeZone.getTimeZone("Europe/Warsaw"));

    String strValue;
    if (value == PGStatement.DATE_POSITIVE_INFINITY) {
      strValue = "infinity";
    } else {
      strValue = "-infinity";
    }

    Statement stmt = con.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL(table, "'" + strValue + "'"));
    stmt.close();

    PreparedStatement ps = con.prepareStatement(TestUtil.insertSQL(table, "?"));
    ps.setTimestamp(1, new Timestamp(value));
    ps.executeUpdate();
    ps.setTimestamp(1, new Timestamp(value), cal);
    ps.executeUpdate();
    ps.close();

    stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select ts from " + table);
    while (rs.next()) {
      assertEquals(strValue, rs.getString(1));

      Timestamp ts = rs.getTimestamp(1);
      assertEquals(value, ts.getTime());

      Date d = rs.getDate(1);
      assertEquals(value, d.getTime());

      Timestamp tscal = rs.getTimestamp(1, cal);
      assertEquals(value, tscal.getTime());
    }
    rs.close();

    assertEquals(3, stmt.executeUpdate("DELETE FROM " + table));
    stmt.close();
  }

  /*
   * Tests the timestamp methods in ResultSet on timestamp with time zone we insert a known string
   * value (don't use setTimestamp) then see that we get back the same value from getTimestamp
   */
  @Test
  public void testGetTimestampWTZ() throws SQLException {
    assumeTrue(TestUtil.haveIntegerDateTimes(con));

    Statement stmt = con.createStatement();
    TimestampUtils tsu = ((BaseConnection) con).getTimestampUtils();

    // Insert the three timestamp values in raw pg format
    for (int i = 0; i < 3; i++) {
      assertEquals(1,
          stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS1WTZ_PGFORMAT + "'")));
      assertEquals(1,
          stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS2WTZ_PGFORMAT + "'")));
      assertEquals(1,
          stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS3WTZ_PGFORMAT + "'")));
      assertEquals(1,
          stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS4WTZ_PGFORMAT + "'")));
    }
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new Timestamp(tmpDate1.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new Timestamp(tmpDate2.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new Timestamp(tmpDate3.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new Timestamp(tmpDate4.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new Timestamp(tmpTime1.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new Timestamp(tmpTime2.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new Timestamp(tmpTime3.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new Timestamp(tmpTime4.getTime())) + "'")));

    // Fall through helper
    timestampTestWTZ();

    assertEquals(20, stmt.executeUpdate("DELETE FROM " + TSWTZ_TABLE));

    stmt.close();
  }

  /*
   * Tests the timestamp methods in PreparedStatement on timestamp with time zone we insert a value
   * using setTimestamp then see that we get back the same value from getTimestamp (which we know
   * works as it was tested independently of setTimestamp
   */
  @Test
  public void testSetTimestampWTZ() throws SQLException {
    assumeTrue(TestUtil.haveIntegerDateTimes(con));

    Statement stmt = con.createStatement();
    PreparedStatement pstmt = con.prepareStatement(TestUtil.insertSQL(TSWTZ_TABLE, "?"));

    pstmt.setTimestamp(1, TS1WTZ);
    assertEquals(1, pstmt.executeUpdate());

    pstmt.setTimestamp(1, TS2WTZ);
    assertEquals(1, pstmt.executeUpdate());

    pstmt.setTimestamp(1, TS3WTZ);
    assertEquals(1, pstmt.executeUpdate());

    pstmt.setTimestamp(1, TS4WTZ);
    assertEquals(1, pstmt.executeUpdate());

    // With java.sql.Timestamp
    pstmt.setObject(1, TS1WTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS2WTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS3WTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS4WTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());

    // With Strings
    pstmt.setObject(1, TS1WTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS2WTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS3WTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS4WTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());

    // With java.sql.Date
    pstmt.setObject(1, tmpDate1, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpDate2, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpDate3, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpDate4, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());

    // With java.sql.Time
    pstmt.setObject(1, tmpTime1, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpTime2, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpTime3, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpTime4, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    // Fall through helper
    timestampTestWTZ();

    assertEquals(20, stmt.executeUpdate("DELETE FROM " + TSWTZ_TABLE));

    pstmt.close();
    stmt.close();
  }

  /*
   * Tests the timestamp methods in ResultSet on timestamp without time zone we insert a known
   * string value (don't use setTimestamp) then see that we get back the same value from
   * getTimestamp
   */
  @Test
  public void testGetTimestampWOTZ() throws SQLException {
    assumeTrue(TestUtil.haveIntegerDateTimes(con));
    //Refer to #896
    assumeMinimumServerVersion(ServerVersion.v8_4);

    Statement stmt = con.createStatement();
    TimestampUtils tsu = ((BaseConnection) con).getTimestampUtils();

    // Insert the three timestamp values in raw pg format
    for (int i = 0; i < 3; i++) {
      for (String value : TS__WOTZ_PGFORMAT) {
        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + value + "'")));
      }
    }

    for (java.util.Date date : TEST_DATE_TIMES) {
      String stringValue = "'" + tsu.toString(null, new Timestamp(date.getTime())) + "'";
      assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, stringValue)));
    }

    // Fall through helper
    timestampTestWOTZ();

    assertEquals(50, stmt.executeUpdate("DELETE FROM " + TSWOTZ_TABLE));

    stmt.close();
  }

  /*
   * Tests the timestamp methods in PreparedStatement on timestamp without time zone we insert a
   * value using setTimestamp then see that we get back the same value from getTimestamp (which we
   * know works as it was tested independently of setTimestamp
   */
  @Test
  public void testSetTimestampWOTZ() throws SQLException {
    assumeTrue(TestUtil.haveIntegerDateTimes(con));
    //Refer to #896
    assumeMinimumServerVersion(ServerVersion.v8_4);

    Statement stmt = con.createStatement();
    PreparedStatement pstmt = con.prepareStatement(TestUtil.insertSQL(TSWOTZ_TABLE, "?"));

    for (Timestamp timestamp : TS__WOTZ) {
      pstmt.setTimestamp(1, timestamp);
      assertEquals(1, pstmt.executeUpdate());
    }

    // With java.sql.Timestamp
    for (Timestamp timestamp : TS__WOTZ) {
      pstmt.setObject(1, timestamp, Types.TIMESTAMP);
      assertEquals(1, pstmt.executeUpdate());
    }

    // With Strings
    for (String value : TS__WOTZ_PGFORMAT) {
      pstmt.setObject(1, value, Types.TIMESTAMP);
      assertEquals(1, pstmt.executeUpdate());
    }

    // With java.sql.Date, java.sql.Time
    for (java.util.Date date : TEST_DATE_TIMES) {
      pstmt.setObject(1, date, Types.TIMESTAMP);
      assertEquals("insert into TSWOTZ_TABLE via setObject(1, " + date
          + ", Types.TIMESTAMP) -> expecting one row inserted", 1, pstmt.executeUpdate());
    }

    // Fall through helper
    timestampTestWOTZ();

    assertEquals(50, stmt.executeUpdate("DELETE FROM " + TSWOTZ_TABLE));

    pstmt.close();
    stmt.close();
  }

  /*
   * Helper for the TimestampTests. It tests what should be in the db
   */
  private void timestampTestWTZ() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs;
    Timestamp t;

    rs = stmt.executeQuery("select ts from " + TSWTZ_TABLE); // removed the order by ts
    assertNotNull(rs);

    for (int i = 0; i < 3; i++) {
      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS1WTZ, t);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS2WTZ, t);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS3WTZ, t);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS4WTZ, t);
    }

    // Testing for Date
    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate1.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate2.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate3.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate4.getTime(), t.getTime());

    // Testing for Time
    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime1.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime2.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime3.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime4.getTime(), t.getTime());

    assertTrue(!rs.next()); // end of table. Fail if more entries exist.

    rs.close();
    stmt.close();
  }

  /*
   * Helper for the TimestampTests. It tests what should be in the db
   */
  private void timestampTestWOTZ() throws SQLException {
    Statement stmt = con.createStatement();
    Timestamp t;
    String tString;

    ResultSet rs = stmt.executeQuery("select ts from " + TSWOTZ_TABLE); // removed the order by ts
    assertNotNull(rs);

    for (int i = 0; i < 3; i++) {
      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS1WOTZ, t);

      tString = rs.getString(1);
      assertNotNull(tString);
      assertEquals(TS1WOTZ_PGFORMAT, tString);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS2WOTZ, t);

      tString = rs.getString(1);
      assertNotNull(tString);
      assertEquals(TS2WOTZ_PGFORMAT, tString);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS3WOTZ, t);

      tString = rs.getString(1);
      assertNotNull(tString);
      assertEquals(TS3WOTZ_PGFORMAT, tString);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS4WOTZ, t);

      tString = rs.getString(1);
      assertNotNull(tString);
      assertEquals(TS4WOTZ_PGFORMAT, tString);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS5WOTZ, t);

      tString = rs.getString(1);
      assertNotNull(tString);
      assertEquals(TS5WOTZ_PGFORMAT, tString);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS6WOTZ, t);

      tString = rs.getString(1);
      assertNotNull(tString);
      assertEquals(TS6WOTZ_PGFORMAT, tString);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS7WOTZ, t);

      tString = rs.getString(1);
      assertNotNull(tString);
      assertEquals(TS7WOTZ_PGFORMAT, tString);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS8WOTZ, t);

      tString = rs.getString(1);
      assertNotNull(tString);
      assertEquals(TS8WOTZ_PGFORMAT, tString);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS9WOTZ_ROUNDED, t);

      tString = rs.getString(1);
      assertNotNull(tString);
      assertEquals(TS9WOTZ_ROUNDED_PGFORMAT, tString);

      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals(TS10WOTZ_ROUNDED, t);

      tString = rs.getString(1);
      assertNotNull(tString);
      assertEquals(TS10WOTZ_ROUNDED_PGFORMAT, tString);
    }

    // Testing for Date
    for (java.util.Date expected : TEST_DATE_TIMES) {
      assertTrue(rs.next());
      t = rs.getTimestamp(1);
      assertNotNull(t);
      assertEquals("rs.getTimestamp(1).getTime()", expected.getTime(), t.getTime());
    }

    assertTrue(!rs.next()); // end of table. Fail if more entries exist.

    rs.close();
    stmt.close();
  }

  @Test
  public void testJavaTimestampFromSQLTime() throws SQLException {
    Statement st = con.createStatement();
    ResultSet rs = st.executeQuery("SELECT '00:00:05.123456'::time as t, '1970-01-01 00:00:05.123456'::timestamp as ts, "
        + "'00:00:05.123456 +0300'::time with time zone as tz, '1970-01-01 00:00:05.123456 +0300'::timestamp with time zone as tstz ");
    rs.next();
    Timestamp t = rs.getTimestamp("t");
    Timestamp ts = rs.getTimestamp("ts");
    Timestamp tz = rs.getTimestamp("tz");

    Timestamp tstz = rs.getTimestamp("tstz");

    Integer desiredNanos = 123456000;
    Integer tNanos = t.getNanos();
    Integer tzNanos = tz.getNanos();

    assertEquals("Time should be microsecond-accurate", desiredNanos, tNanos);
    assertEquals("Time with time zone should be microsecond-accurate", desiredNanos, tzNanos);
    assertEquals("Unix epoch timestamp and Time should match", ts, t);
    assertEquals("Unix epoch timestamp with time zone and time with time zone should match", tstz, tz);
  }

  private static Timestamp getTimestamp(int y, int m, int d, int h, int mn, int se, int f,
      String tz) {
    Timestamp result = null;
    java.text.DateFormat dateFormat;
    try {
      String ts;
      ts = TestUtil.fix(y, 4) + "-"
          + TestUtil.fix(m, 2) + "-"
          + TestUtil.fix(d, 2) + " "
          + TestUtil.fix(h, 2) + ":"
          + TestUtil.fix(mn, 2) + ":"
          + TestUtil.fix(se, 2) + " ";

      if (tz == null) {
        dateFormat = new SimpleDateFormat("y-M-d H:m:s");
      } else {
        ts = ts + tz;
        dateFormat = new SimpleDateFormat("y-M-d H:m:s z");
      }
      java.util.Date date = dateFormat.parse(ts);
      result = new Timestamp(date.getTime());
      result.setNanos(f);
    } catch (Exception ex) {
      fail(ex.getMessage());
    }
    return result;
  }

  private static final Timestamp TS1WTZ =
      getTimestamp(1950, 2, 7, 15, 0, 0, 100000000, "PST");
  private static final String TS1WTZ_PGFORMAT = "1950-02-07 15:00:00.1-08";

  private static final Timestamp TS2WTZ =
      getTimestamp(2000, 2, 7, 15, 0, 0, 120000000, "GMT");
  private static final String TS2WTZ_PGFORMAT = "2000-02-07 15:00:00.12+00";

  private static final Timestamp TS3WTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 123000000, "GMT");
  private static final String TS3WTZ_PGFORMAT = "2000-07-07 15:00:00.123+00";

  private static final Timestamp TS4WTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 123456000, "GMT");
  private static final String TS4WTZ_PGFORMAT = "2000-07-07 15:00:00.123456+00";

  private static final Timestamp TS1WOTZ =
      getTimestamp(1950, 2, 7, 15, 0, 0, 100000000, null);
  private static final String TS1WOTZ_PGFORMAT = "1950-02-07 15:00:00.1";

  private static final Timestamp TS2WOTZ =
      getTimestamp(2000, 2, 7, 15, 0, 0, 120000000, null);
  private static final String TS2WOTZ_PGFORMAT = "2000-02-07 15:00:00.12";

  private static final Timestamp TS3WOTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 123000000, null);
  private static final String TS3WOTZ_PGFORMAT = "2000-07-07 15:00:00.123";

  private static final Timestamp TS4WOTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 123456000, null);
  private static final String TS4WOTZ_PGFORMAT = "2000-07-07 15:00:00.123456";

  private static final Timestamp TS5WOTZ =
      new Timestamp(PGStatement.DATE_NEGATIVE_INFINITY);
  private static final String TS5WOTZ_PGFORMAT = "-infinity";

  private static final Timestamp TS6WOTZ =
      new Timestamp(PGStatement.DATE_POSITIVE_INFINITY);
  private static final String TS6WOTZ_PGFORMAT = "infinity";

  private static final Timestamp TS7WOTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 0, null);
  private static final String TS7WOTZ_PGFORMAT = "2000-07-07 15:00:00";

  private static final Timestamp TS8WOTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 20400000, null);
  private static final String TS8WOTZ_PGFORMAT = "2000-07-07 15:00:00.0204";

  private static final Timestamp TS9WOTZ =
      getTimestamp(2000, 2, 7, 15, 0, 0, 789, null);
  private static final String TS9WOTZ_PGFORMAT = "2000-02-07 15:00:00.000000789";
  private static final Timestamp TS9WOTZ_ROUNDED =
      getTimestamp(2000, 2, 7, 15, 0, 0, 1000, null);
  private static final String TS9WOTZ_ROUNDED_PGFORMAT = "2000-02-07 15:00:00.000001";

  private static final Timestamp TS10WOTZ =
      getTimestamp(2018, 12, 31, 23, 59, 59, 999999500, null);
  private static final String TS10WOTZ_PGFORMAT = "2018-12-31 23:59:59.999999500";
  private static final Timestamp TS10WOTZ_ROUNDED =
      getTimestamp(2019, 1, 1, 0, 0, 0, 0, null);
  private static final String TS10WOTZ_ROUNDED_PGFORMAT = "2019-01-01 00:00:00";

  private static final Timestamp[] TS__WOTZ = {
    TS1WOTZ, TS2WOTZ, TS3WOTZ, TS4WOTZ, TS5WOTZ,
    TS6WOTZ, TS7WOTZ, TS8WOTZ, TS9WOTZ, TS10WOTZ,
  };

  private static final String[] TS__WOTZ_PGFORMAT = {
    TS1WOTZ_PGFORMAT, TS2WOTZ_PGFORMAT, TS3WOTZ_PGFORMAT, TS4WOTZ_PGFORMAT, TS5WOTZ_PGFORMAT,
    TS6WOTZ_PGFORMAT, TS7WOTZ_PGFORMAT, TS8WOTZ_PGFORMAT, TS9WOTZ_PGFORMAT, TS10WOTZ_PGFORMAT,
  };

  private static final String TSWTZ_TABLE = "testtimestampwtz";
  private static final String TSWOTZ_TABLE = "testtimestampwotz";
  private static final String DATE_TABLE = "testtimestampdate";

  private static final java.sql.Date tmpDate1 = new java.sql.Date(TS1WTZ.getTime());
  private static final java.sql.Time tmpTime1 = new java.sql.Time(TS1WTZ.getTime());
  private static final java.sql.Date tmpDate2 = new java.sql.Date(TS2WTZ.getTime());
  private static final java.sql.Time tmpTime2 = new java.sql.Time(TS2WTZ.getTime());
  private static final java.sql.Date tmpDate3 = new java.sql.Date(TS3WTZ.getTime());
  private static final java.sql.Time tmpTime3 = new java.sql.Time(TS3WTZ.getTime());
  private static final java.sql.Date tmpDate4 = new java.sql.Date(TS4WTZ.getTime());
  private static final java.sql.Time tmpTime4 = new java.sql.Time(TS4WTZ.getTime());

  private static final java.sql.Date tmpDate1WOTZ = new java.sql.Date(TS1WOTZ.getTime());
  private static final java.sql.Time tmpTime1WOTZ = new java.sql.Time(TS1WOTZ.getTime());
  private static final java.sql.Date tmpDate2WOTZ = new java.sql.Date(TS2WOTZ.getTime());
  private static final java.sql.Time tmpTime2WOTZ = new java.sql.Time(TS2WOTZ.getTime());
  private static final java.sql.Date tmpDate3WOTZ = new java.sql.Date(TS3WOTZ.getTime());
  private static final java.sql.Time tmpTime3WOTZ = new java.sql.Time(TS3WOTZ.getTime());
  private static final java.sql.Date tmpDate4WOTZ = new java.sql.Date(TS4WOTZ.getTime());
  private static final java.sql.Time tmpTime4WOTZ = new java.sql.Time(TS4WOTZ.getTime());
  private static final java.sql.Date tmpDate5WOTZ = new java.sql.Date(TS5WOTZ.getTime());
  private static final java.sql.Date tmpTime5WOTZ = new java.sql.Date(TS5WOTZ.getTime());
  private static final java.sql.Date tmpDate6WOTZ = new java.sql.Date(TS6WOTZ.getTime());
  private static final java.sql.Date tmpTime6WOTZ = new java.sql.Date(TS6WOTZ.getTime());
  private static final java.sql.Date tmpDate7WOTZ = new java.sql.Date(TS7WOTZ.getTime());
  private static final java.sql.Time tmpTime7WOTZ = new java.sql.Time(TS7WOTZ.getTime());
  private static final java.sql.Date tmpDate8WOTZ = new java.sql.Date(TS8WOTZ.getTime());
  private static final java.sql.Time tmpTime8WOTZ = new java.sql.Time(TS8WOTZ.getTime());
  private static final java.sql.Date tmpDate9WOTZ = new java.sql.Date(TS9WOTZ.getTime());
  private static final java.sql.Time tmpTime9WOTZ = new java.sql.Time(TS9WOTZ.getTime());
  private static final java.sql.Date tmpDate10WOTZ = new java.sql.Date(TS10WOTZ.getTime());
  private static final java.sql.Time tmpTime10WOTZ = new java.sql.Time(TS10WOTZ.getTime());

  private static final java.util.Date[] TEST_DATE_TIMES = {
      tmpDate1WOTZ, tmpDate2WOTZ, tmpDate3WOTZ, tmpDate4WOTZ, tmpDate5WOTZ,
      tmpDate6WOTZ, tmpDate7WOTZ, tmpDate8WOTZ, tmpDate9WOTZ, tmpDate10WOTZ,
      tmpTime1WOTZ, tmpTime2WOTZ, tmpTime3WOTZ, tmpTime4WOTZ, tmpTime5WOTZ,
      tmpTime6WOTZ, tmpTime7WOTZ, tmpTime8WOTZ, tmpTime9WOTZ, tmpTime10WOTZ,
  };
}
