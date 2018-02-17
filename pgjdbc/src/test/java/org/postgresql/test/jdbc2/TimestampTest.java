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
 *
 */
@RunWith(Parameterized.class)
public class TimestampTest extends BaseTest4 {

  public TimestampTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

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
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, TSWTZ_TABLE);
    TestUtil.dropTable(con, TSWOTZ_TABLE);
    TestUtil.dropTable(con, DATE_TABLE);
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
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS1WTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS2WTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS3WTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS4WTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS1WTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS2WTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS3WTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS4WTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS1WTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS2WTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS3WTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE, "'" + TS4WTZ_PGFORMAT + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate1.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate2.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate3.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate4.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime1.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime2.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime3.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime4.getTime())) + "'")));


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
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS1WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS2WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS3WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS4WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS5WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS6WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS7WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS8WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS9WOTZ_PGFORMAT + "'")));

    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS1WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS2WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS3WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS4WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS5WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS6WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS7WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS8WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS9WOTZ_PGFORMAT + "'")));

    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS1WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS2WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS3WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS4WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS5WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS6WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS7WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS8WOTZ_PGFORMAT + "'")));
    assertEquals(1,
        stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE, "'" + TS9WOTZ_PGFORMAT + "'")));

    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate1WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate2WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate3WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate4WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate5WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate6WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate7WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpDate8WOTZ.getTime())) + "'")));

    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime1WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime2WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime3WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime4WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime5WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime6WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime7WOTZ.getTime())) + "'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL(TSWOTZ_TABLE,
        "'" + tsu.toString(null, new java.sql.Timestamp(tmpTime8WOTZ.getTime())) + "'")));

    // Fall through helper
    timestampTestWOTZ();

    assertEquals(43, stmt.executeUpdate("DELETE FROM " + TSWOTZ_TABLE));

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

    pstmt.setTimestamp(1, TS1WOTZ);
    assertEquals(1, pstmt.executeUpdate());

    pstmt.setTimestamp(1, TS2WOTZ);
    assertEquals(1, pstmt.executeUpdate());

    pstmt.setTimestamp(1, TS3WOTZ);
    assertEquals(1, pstmt.executeUpdate());

    pstmt.setTimestamp(1, TS4WOTZ);
    assertEquals(1, pstmt.executeUpdate());

    pstmt.setTimestamp(1, TS5WOTZ);
    assertEquals(1, pstmt.executeUpdate());

    pstmt.setTimestamp(1, TS6WOTZ);
    assertEquals(1, pstmt.executeUpdate());

    pstmt.setTimestamp(1, TS7WOTZ);
    assertEquals(1, pstmt.executeUpdate());

    pstmt.setTimestamp(1, TS8WOTZ);
    assertEquals(1, pstmt.executeUpdate());

    pstmt.setTimestamp(1, TS9WOTZ);
    assertEquals(1, pstmt.executeUpdate());

    // With java.sql.Timestamp
    pstmt.setObject(1, TS1WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS2WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS3WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS4WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS5WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS6WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS7WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS8WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS9WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());

    // With Strings
    pstmt.setObject(1, TS1WOTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS2WOTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS3WOTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS4WOTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS5WOTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS6WOTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS7WOTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS8WOTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, TS9WOTZ_PGFORMAT, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());

    // With java.sql.Date
    pstmt.setObject(1, tmpDate1WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpDate2WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpDate3WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpDate4WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpDate5WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpDate6WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpDate7WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpDate8WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());

    // With java.sql.Time
    pstmt.setObject(1, tmpTime1WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpTime2WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpTime3WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpTime4WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpTime5WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpTime6WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpTime7WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    pstmt.setObject(1, tmpTime8WOTZ, Types.TIMESTAMP);
    assertEquals(1, pstmt.executeUpdate());
    // Fall through helper
    timestampTestWOTZ();

    assertEquals(43, stmt.executeUpdate("DELETE FROM " + TSWOTZ_TABLE));

    pstmt.close();
    stmt.close();
  }

  /*
   * Helper for the TimestampTests. It tests what should be in the db
   */
  private void timestampTestWTZ() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs;
    java.sql.Timestamp t;

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
    java.sql.Timestamp t;
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
    }

    // Testing for Date
    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate1WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate2WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate3WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate4WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate5WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate6WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate7WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpDate8WOTZ.getTime(), t.getTime());

    // Testing for Time
    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime1WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime2WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime3WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime4WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime5WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime6WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime7WOTZ.getTime(), t.getTime());

    assertTrue(rs.next());
    t = rs.getTimestamp(1);
    assertNotNull(t);
    assertEquals(tmpTime8WOTZ.getTime(), t.getTime());

    assertTrue(!rs.next()); // end of table. Fail if more entries exist.

    rs.close();
    stmt.close();
  }

  private static java.sql.Timestamp getTimestamp(int y, int m, int d, int h, int mn, int se, int f,
      String tz) {
    java.sql.Timestamp l_return = null;
    java.text.DateFormat l_df;
    try {
      String l_ts;
      l_ts = TestUtil.fix(y, 4) + "-"
          + TestUtil.fix(m, 2) + "-"
          + TestUtil.fix(d, 2) + " "
          + TestUtil.fix(h, 2) + ":"
          + TestUtil.fix(mn, 2) + ":"
          + TestUtil.fix(se, 2) + " ";

      if (tz == null) {
        l_df = new SimpleDateFormat("y-M-d H:m:s");
      } else {
        l_ts = l_ts + tz;
        l_df = new SimpleDateFormat("y-M-d H:m:s z");
      }
      java.util.Date l_date = l_df.parse(l_ts);
      l_return = new java.sql.Timestamp(l_date.getTime());
      l_return.setNanos(f);
    } catch (Exception ex) {
      fail(ex.getMessage());
    }
    return l_return;
  }

  private static final java.sql.Timestamp TS1WTZ =
      getTimestamp(1950, 2, 7, 15, 0, 0, 100000000, "PST");
  private static final String TS1WTZ_PGFORMAT = "1950-02-07 15:00:00.1-08";

  private static final java.sql.Timestamp TS2WTZ =
      getTimestamp(2000, 2, 7, 15, 0, 0, 120000000, "GMT");
  private static final String TS2WTZ_PGFORMAT = "2000-02-07 15:00:00.12+00";

  private static final java.sql.Timestamp TS3WTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 123000000, "GMT");
  private static final String TS3WTZ_PGFORMAT = "2000-07-07 15:00:00.123+00";

  private static final java.sql.Timestamp TS4WTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 123456000, "GMT");
  private static final String TS4WTZ_PGFORMAT = "2000-07-07 15:00:00.123456+00";


  private static final java.sql.Timestamp TS1WOTZ =
      getTimestamp(1950, 2, 7, 15, 0, 0, 100000000, null);
  private static final String TS1WOTZ_PGFORMAT = "1950-02-07 15:00:00.1";

  private static final java.sql.Timestamp TS2WOTZ =
      getTimestamp(2000, 2, 7, 15, 0, 0, 120000000, null);
  private static final String TS2WOTZ_PGFORMAT = "2000-02-07 15:00:00.12";

  private static final java.sql.Timestamp TS3WOTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 123000000, null);
  private static final String TS3WOTZ_PGFORMAT = "2000-07-07 15:00:00.123";

  private static final java.sql.Timestamp TS4WOTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 123456000, null);
  private static final String TS4WOTZ_PGFORMAT = "2000-07-07 15:00:00.123456";

  private static final java.sql.Timestamp TS5WOTZ =
      new Timestamp(PGStatement.DATE_NEGATIVE_INFINITY);
  private static final String TS5WOTZ_PGFORMAT = "-infinity";

  private static final java.sql.Timestamp TS6WOTZ =
      new Timestamp(PGStatement.DATE_POSITIVE_INFINITY);
  private static final String TS6WOTZ_PGFORMAT = "infinity";

  private static final java.sql.Timestamp TS7WOTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 0, null);
  private static final String TS7WOTZ_PGFORMAT = "2000-07-07 15:00:00";

  private static final java.sql.Timestamp TS8WOTZ =
      getTimestamp(2000, 7, 7, 15, 0, 0, 20400000, null);
  private static final String TS8WOTZ_PGFORMAT = "2000-07-07 15:00:00.0204";

  private static final java.sql.Timestamp TS9WOTZ =
      getTimestamp(2000, 2, 7, 15, 0, 0, 789, null);
  private static final String TS9WOTZ_PGFORMAT = "2000-02-07 15:00:00.000000789";
  private static final java.sql.Timestamp TS9WOTZ_ROUNDED =
      getTimestamp(2000, 2, 7, 15, 0, 0, 1000, null);
  private static final String TS9WOTZ_ROUNDED_PGFORMAT = "2000-02-07 15:00:00.000001";

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

}
