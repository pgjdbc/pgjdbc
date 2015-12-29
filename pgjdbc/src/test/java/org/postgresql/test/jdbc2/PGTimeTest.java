/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGTime;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Tests {@link PGTime} in various scenarios including setTime, setObject for both <code>time with
 * time zone</code> and <code>time without time zone</code> data types.
 */
public class PGTimeTest extends TestCase {
  /**
   * The name of the test table.
   */
  private static final String TEST_TABLE = "testtime";

  /**
   * The database connection.
   */
  private Connection con;

  public PGTimeTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTempTable(con, TEST_TABLE, "tm time, tz time with time zone");
  }

  protected void tearDown() throws Exception {
    TestUtil.dropTable(con, TEST_TABLE);
    TestUtil.closeDB(con);
  }

  /**
   * Tests that adding a <code>PGInterval</code> object to a <code>PGTime</code> object when
   * performed as a casted string and object.
   *
   * @throws SQLException if a JDBC or database problem occurs.
   */
  public void testTimeWithInterval() throws SQLException {
    Calendar cal = Calendar.getInstance();
    cal.set(1970, 0, 1);

    final long now = cal.getTimeInMillis();
    verifyTimeWithInterval(new PGTime(now), new PGInterval(0, 0, 0, 1, 2, 3.14), true);
    verifyTimeWithInterval(new PGTime(now), new PGInterval(0, 0, 0, 1, 2, 3.14), false);

    verifyTimeWithInterval(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT"))),
        new PGInterval(0, 0, 0, 1, 2, 3.14), true);
    verifyTimeWithInterval(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT"))),
        new PGInterval(0, 0, 0, 1, 2, 3.14), false);

    verifyTimeWithInterval(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT+01:00"))),
        new PGInterval(0, 0, 0, 1, 2, 3.456), true);
    verifyTimeWithInterval(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT+01:00"))),
        new PGInterval(0, 0, 0, 1, 2, 3.456), false);
  }

  /**
   * Verifies that adding the given <code>PGInterval</code> object to a <code>PGTime</code> produces
   * the correct results when performed as a casted string and object.
   *
   * @param time         the time to test.
   * @param interval     the time interval.
   * @param useSetObject <code>true</code> if the setObject method should be used instead of
   *                     setTime.
   * @throws SQLException if a JDBC or database problem occurs.
   */
  private void verifyTimeWithInterval(PGTime time, PGInterval interval, boolean useSetObject)
      throws SQLException {
    // Construct the SQL query.
    String sql;
    if (time.getCalendar() != null) {
      sql = "SELECT ?::time with time zone + ?";
    } else {
      sql = "SELECT ?::time + ?";
    }

    SimpleDateFormat sdf = createSimpleDateFormat(time);

    // Execute a query using a casted time string + PGInterval.
    PreparedStatement stmt = con.prepareStatement(sql);
    stmt.setString(1, sdf.format(time));
    stmt.setObject(2, interval);

    ResultSet rs = stmt.executeQuery();
    assertTrue(rs.next());

    Time result1 = rs.getTime(1);
    //System.out.println(stmt + " = " + sdf.format(result1));
    stmt.close();

    // Execute a query using with PGTime + PGInterval.
    stmt = con.prepareStatement("SELECT ? + ?");
    if (useSetObject) {
      stmt.setObject(1, time);
    } else {
      stmt.setTime(1, time);
    }
    stmt.setObject(2, interval);

    rs = stmt.executeQuery();
    assertTrue(rs.next());

    Time result2 = rs.getTime(1);
    //System.out.println(stmt + " = " + sdf.format(result2));
    assertEquals(result1, result2);
    stmt.close();
  }

  /**
   * Tests inserting and selecting <code>PGTime</code> objects with <code>time</code> and <code>time
   * with time zone</code> columns.
   *
   * @throws SQLException if a JDBC or database problem occurs.
   */
  public void testTimeInsertAndSelect() throws SQLException {
    Calendar cal = Calendar.getInstance();
    cal.set(1970, 0, 1);

    final long now = cal.getTimeInMillis();
    verifyInsertAndSelect(new PGTime(now), true);
    verifyInsertAndSelect(new PGTime(now), false);

    verifyInsertAndSelect(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT"))), true);
    verifyInsertAndSelect(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT"))),
        false);

    verifyInsertAndSelect(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT+01:00"))),
        true);
    verifyInsertAndSelect(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT+01:00"))),
        false);
  }

  /**
   * Verifies that inserting the given <code>PGTime</code> as a time string and an object produces
   * the same results.
   *
   * @param time         the time to test.
   * @param useSetObject <code>true</code> if the setObject method should be used instead of
   *                     setTime.
   * @throws SQLException if a JDBC or database problem occurs.
   */
  private void verifyInsertAndSelect(PGTime time, boolean useSetObject) throws SQLException {
    // Construct the INSERT statement of a casted time string.
    String sql;
    if (time.getCalendar() != null) {
      sql =
          "INSERT INTO " + TEST_TABLE + " VALUES (?::time with time zone, ?::time with time zone)";
    } else {
      sql = "INSERT INTO " + TEST_TABLE + " VALUES (?::time, ?::time)";
    }

    SimpleDateFormat sdf = createSimpleDateFormat(time);

    // Insert the times as casted strings.
    PreparedStatement pstmt1 = con.prepareStatement(sql);
    pstmt1.setString(1, sdf.format(time));
    pstmt1.setString(2, sdf.format(time));
    assertEquals(1, pstmt1.executeUpdate());

    // Insert the times as PGTime objects.
    PreparedStatement pstmt2 = con.prepareStatement("INSERT INTO " + TEST_TABLE + " VALUES (?, ?)");

    if (useSetObject) {
      pstmt2.setObject(1, time);
      pstmt2.setObject(2, time);
    } else {
      pstmt2.setTime(1, time);
      pstmt2.setTime(2, time);
    }

    assertEquals(1, pstmt2.executeUpdate());

    // Query the values back out.
    Statement stmt = con.createStatement();

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL(TEST_TABLE, "tm,tz"));
    assertNotNull(rs);

    // Read the casted string values.
    assertTrue(rs.next());

    Time tm1 = rs.getTime(1);
    Time tz1 = rs.getTime(2);

    //System.out.println(pstmt1 + " -> " + tm1 + ", " + sdf.format(tz1));

    // Read the PGTime values.
    assertTrue(rs.next());

    Time tm2 = rs.getTime(1);
    Time tz2 = rs.getTime(2);

    //System.out.println(pstmt2 + " -> " + tm2 + ", " + sdf.format(tz2));

    // Verify that the first and second versions match.
    assertEquals(tm1, tm2);
    assertEquals(tz1, tz2);

    // Clean up.
    assertEquals(2, stmt.executeUpdate("DELETE FROM " + TEST_TABLE));
    stmt.close();
    pstmt2.close();
    pstmt1.close();
  }

  /**
   * Creates a <code>SimpleDateFormat</code> that is appropriate for the given time.
   *
   * @param time the time object.
   * @return the new format instance.
   */
  private SimpleDateFormat createSimpleDateFormat(PGTime time) {
    String pattern = "HH:mm:ss.SSS";
    if (time.getCalendar() != null) {
      pattern += " Z";
    }

    SimpleDateFormat sdf = new SimpleDateFormat(pattern);
    if (time.getCalendar() != null) {
      sdf.setTimeZone(time.getCalendar().getTimeZone());
    }
    return sdf;
  }
}
