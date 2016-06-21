/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import java.lang.reflect.Field;
import java.sql.BatchUpdateException;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Properties;
import java.util.TimeZone;

public class TimezoneCachingTest extends BaseTest {

  /**
   * Test to check the internal calendar for timezone of a prepared statement is set/cleared as
   * expected.
   */
  public void testTimezonePreparedStatementCachedCalendarInstance() throws SQLException {
    Timestamp ts = new Timestamp(2016 - 1900, 1 - 1, 31, 0, 0, 0, 0);
    Date date = new Date(2016 - 1900, 1 - 1, 31);
    Time time = new Time(System.currentTimeMillis());
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testtz VALUES (?,?)");
      assertTrue("Cache should NOT be set", getTimeZoneCache(pstmt) == null);
      pstmt.setInt(1, 1);
      assertTrue("Cache should NOT be set", getTimeZoneCache(pstmt) == null);
      pstmt.setTimestamp(2, ts);
      assertTrue("Cache should be set", getTimeZoneCache(pstmt) != null);
      pstmt.addBatch();
      assertTrue("Cache should be set", getTimeZoneCache(pstmt) != null);
      pstmt.setInt(1, 2);
      pstmt.setNull(2, java.sql.Types.DATE);
      assertTrue("Cache should be set", getTimeZoneCache(pstmt) != null);
      pstmt.addBatch();
      assertTrue("Cache should be set", getTimeZoneCache(pstmt) != null);
      pstmt.executeBatch();
      assertTrue("Cache should NOT be set", getTimeZoneCache(pstmt) == null);
      pstmt.setInt(1, 3);
      assertTrue("Cache should NOT be set", getTimeZoneCache(pstmt) == null);
      pstmt.setInt(1, 4);
      pstmt.setNull(2, java.sql.Types.DATE);
      assertTrue("Cache should NOT be set", getTimeZoneCache(pstmt) == null);
      pstmt.setTimestamp(2, ts);
      assertTrue("Cache should be set", getTimeZoneCache(pstmt) != null);
      pstmt.clearParameters();
      assertTrue("Cache should be set", getTimeZoneCache(pstmt) != null);
      pstmt.setInt(1, 5);
      pstmt.setTimestamp(2, ts);
      pstmt.addBatch();
      pstmt.executeBatch();
      pstmt.close();
      pstmt = con.prepareStatement("UPDATE testtz SET col2 = ? WHERE col1 = 1");
      assertTrue("Cache should NOT be set", getTimeZoneCache(pstmt) == null);
      pstmt.setDate(1, date);
      assertTrue("Cache should be set", getTimeZoneCache(pstmt) != null);
      pstmt.execute();
      assertTrue("Cache should NOT be set", getTimeZoneCache(pstmt) == null);
      pstmt.setDate(1, date);
      assertTrue("Cache should be set", getTimeZoneCache(pstmt) != null);
      pstmt.executeUpdate();
      pstmt.setTime(1, time);
      assertTrue("Cache should be set", getTimeZoneCache(pstmt) != null);
      pstmt.close();
      pstmt = con.prepareStatement("SELECT * FROM testtz WHERE col2 = ?");
      assertTrue("Cache should NOT be set", getTimeZoneCache(pstmt) == null);
      pstmt.setDate(1, date);
      assertTrue("Cache should be set", getTimeZoneCache(pstmt) != null);
      pstmt.executeQuery();
      assertTrue("Cache should NOT be set", getTimeZoneCache(pstmt) == null);
    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
      con.rollback();
    }
  }

  /**
   * Test to check the internal calendar for timezone of a prepared statement is used as expected.
   */
  public void testTimezonePreparedStatementCachedCalendarUsage() throws SQLException {
    Timestamp ts = new Timestamp(2016 - 1900, 1 - 1, 31, 0, 0, 0, 0);
    Statement stmt = null;
    PreparedStatement pstmt = null;
    TimeZone tz1 = TimeZone.getTimeZone("GMT+8:00");
    TimeZone tz2 = TimeZone.getTimeZone("GMT-2:00");
    try {
      stmt = con.createStatement();
      TimeZone.setDefault(tz1);
      pstmt = con.prepareStatement("INSERT INTO testtz VALUES(1, ?)");
      pstmt.setTimestamp(1, ts);
      pstmt.executeUpdate();
      assertTrue("Timestamps not set properly", checkTimestamp(stmt, ts, tz1));
      pstmt.close();
      pstmt = con.prepareStatement("UPDATE testtz SET col2 = ? WHERE col1 = ?");
      pstmt.setTimestamp(1, ts);
      TimeZone.setDefault(tz2);
      pstmt.setInt(2, 1);
      pstmt.addBatch();
      pstmt.executeBatch();
      assertTrue("Timezone mismatch", checkTimestamp(stmt, ts, tz1));
      pstmt.setTimestamp(1, ts);
      pstmt.setInt(2, 1);
      pstmt.addBatch();
      pstmt.executeBatch();
      assertTrue("Timezone mismatch", checkTimestamp(stmt, ts, tz2));
      pstmt.setTimestamp(1, ts);
      pstmt.setInt(2, 1);
      pstmt.clearParameters();
      TimeZone.setDefault(tz1);
      pstmt.setTimestamp(1, ts);
      pstmt.setInt(2, 1);
      pstmt.executeBatch();
      assertTrue("Timezone mismatch", checkTimestamp(stmt, ts, tz2));
    } catch (BatchUpdateException ex) {
      SQLException nextException = ex.getNextException();
      nextException.printStackTrace();
    } finally {
      TimeZone.setDefault(null);
      if (null != pstmt) {
        pstmt.close();
      }
      if (null != stmt) {
        stmt.close();
      }
      con.rollback();
    }
  }

  /**
   * Test to check the internal calendar for timezone of a result set is set/cleared as expected.
   */
  public void testTimezoneResultSetCachedCalendarInstance() throws SQLException {
    Timestamp ts = new Timestamp(2016 - 1900, 1 - 1, 31, 0, 0, 0, 0);
    Statement stmt = null;
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testtz VALUES (?,?)");
      pstmt.setInt(1, 1);
      pstmt.setTimestamp(2, ts);
      pstmt.addBatch();
      pstmt.executeBatch();
      stmt = con.createStatement();
      rs = stmt.executeQuery("SELECT col1, col2 FROM testtz");
      rs.next();
      assertTrue("Cache should NOT be set", getTimeZoneCache(rs) == null);
      rs.getInt(1);
      assertTrue("Cache should NOT be set", getTimeZoneCache(rs) == null);
      rs.getTimestamp(2);
      assertTrue("Cache should be set", getTimeZoneCache(rs) != null);
      rs.close();
      rs = stmt.executeQuery("SELECT col1, col2 FROM testtz");
      rs.next();
      assertTrue("Cache should NOT be set", getTimeZoneCache(rs) == null);
      rs.getInt(1);
      assertTrue("Cache should NOT be set", getTimeZoneCache(rs) == null);
      rs.getObject(2);
      assertTrue("Cache should be set", getTimeZoneCache(rs) != null);
      rs.close();
      rs = stmt.executeQuery("SELECT col1, col2 FROM testtz");
      rs.next();
      assertTrue("Cache should NOT be set", getTimeZoneCache(rs) == null);
      rs.getInt(1);
      assertTrue("Cache should NOT be set", getTimeZoneCache(rs) == null);
      rs.getDate(2);
      assertTrue("Cache should be set", getTimeZoneCache(rs) != null);
      rs.close();
    } finally {
      if (null != rs) {
        rs.close();
      }
      if (null != pstmt) {
        pstmt.close();
      }
      if (null != stmt) {
        stmt.close();
      }
      con.rollback();
    }
  }

  /**
   * Test to check the internal calendar for timezone of a result set is used as expected.
   */
  public void testTimezoneResultSetCachedCalendarUsage() throws SQLException {
    Timestamp ts = new Timestamp(2016 - 1900, 1 - 1, 31, 0, 0, 0, 0);
    Statement stmt = null;
    PreparedStatement pstmt = null;
    ResultSet rs = null;
    TimeZone tz1 = TimeZone.getTimeZone("GMT+8:00");
    TimeZone tz2 = TimeZone.getTimeZone("GMT-2:00");
    try {
      TimeZone.setDefault(tz1);
      pstmt = con.prepareStatement("INSERT INTO testtz VALUES (?,?)");
      pstmt.setInt(1, 1);
      pstmt.setTimestamp(2, ts);
      pstmt.addBatch();
      pstmt.executeBatch();
      stmt = con.createStatement();
      rs = stmt.executeQuery("SELECT col1, col2 FROM testtz");
      rs.next();
      rs.getInt(1);
      assertTrue("Timestamps sould be in same time zone", rs.getTimestamp(2).equals(ts));
      rs.close();
      rs = stmt.executeQuery("SELECT col1, col2 FROM testtz");
      rs.next();
      rs.getInt(1);
      TimeZone.setDefault(tz2);
      assertTrue("Timestamps sould NOT be in same time zone", !rs.getTimestamp(2).equals(ts));
      TimeZone.setDefault(tz1);
      assertTrue("Timestamps sould NOT be in same time zone", !rs.getTimestamp(2).equals(ts));
      rs.close();
    } finally {
      TimeZone.setDefault(null);
      if (null != rs) {
        rs.close();
      }
      if (null != pstmt) {
        pstmt.close();
      }
      if (null != stmt) {
        stmt.close();
      }
      con.rollback();
    }
  }

  private boolean checkTimestamp(Statement stmt, Timestamp ts, TimeZone tz) throws SQLException {
    TimeZone prevTz = TimeZone.getDefault();
    TimeZone.setDefault(tz);
    ResultSet rs = stmt.executeQuery("SELECT col2 FROM testtz");
    rs.next();
    Timestamp dbTs = rs.getTimestamp(1);
    rs.close();
    TimeZone.setDefault(prevTz);
    return dbTs.equals(ts);
  }

  private TimeZone getTimeZoneCache(Object stmt) {
    try {
      Field defaultTimeZoneField = stmt.getClass().getDeclaredField("defaultTimeZone");
      defaultTimeZoneField.setAccessible(true);
      return (TimeZone) defaultTimeZoneField.get(stmt);
    } catch (Exception e) {
    }
    return null;
  }

  public TimezoneCachingTest(String name) {
    super(name);
    try {
      Class.forName("org.postgresql.Driver");
    } catch (Exception ex) {
    }
  }

  /* Set up the fixture for this test case: a connection to a database with
  a table for this test. */
  protected void setUp() throws Exception {
    super.setUp();
    Statement stmt = con.createStatement();

    /* Drop the test table if it already exists for some reason. It is
    not an error if it doesn't exist. */
    TestUtil.createTable(con, "testtz", "col1 INTEGER, col2 TIMESTAMP");

    stmt.close();

    /* Generally recommended with batch updates. By default we run all
    tests in this test case with autoCommit disabled. */
    con.setAutoCommit(false);
  }

  // Tear down the fixture for this test case.
  protected void tearDown() throws SQLException {
    con.setAutoCommit(true);

    TestUtil.dropTable(con, "testtz");
    super.tearDown();
  }

  @Override
  protected void updateProperties(Properties props) {
    props.setProperty(PGProperty.REWRITE_BATCHED_INSERTS.getName(),
        Boolean.TRUE.toString());
    props.setProperty(PGProperty.PREPARE_THRESHOLD.getName(), "1");
  }
}
