package org.postgresql.test.jdbc42;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public class GetObject310Test extends TestCase {

  private static final ZoneOffset UTC = ZoneOffset.UTC; // +0000 always
  private static final ZoneOffset GMT03 = ZoneOffset.of("+03:00"); // +0300 always
  private static final ZoneOffset GMT05 = ZoneOffset.of("-05:00"); // -0500 always
  private static final ZoneOffset GMT13 = ZoneOffset.of("+13:00"); // +1300 always

  private Connection _conn;

  public GetObject310Test(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    _conn = TestUtil.openDB();
    TestUtil.createTable(_conn, "table1", "timestamp_without_time_zone_column timestamp without time zone,"
            + "timestamp_with_time_zone_column timestamp with time zone,"
            + "date_column date,"
            + "time_without_time_zone_column time without time zone,"
            + "time_with_time_zone_column time with time zone"
    );
  }

  protected void tearDown() throws SQLException {
    TestUtil.dropTable(_conn, "table1");
    TestUtil.closeDB( _conn );
  }

  /**
   * Test the behavior getObject for date columns.
   */
  public void testGetLocalDate() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","date_column","DATE '1999-01-08'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "date_column"));
    try {
      assertTrue(rs.next());
      LocalDate localDate = LocalDate.of(1999, 1, 8);
      assertEquals(localDate, rs.getObject("date_column", LocalDate.class));
      assertEquals(localDate, rs.getObject(1, LocalDate.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for time columns.
   */
  public void testGetLocalTime() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","time_without_time_zone_column","TIME '04:05:06'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "time_without_time_zone_column"));
    try {
      assertTrue(rs.next());
      LocalTime localTime = LocalTime.of(4, 5, 6);
      assertEquals(localTime, rs.getObject("time_without_time_zone_column", LocalTime.class));
      assertEquals(localTime, rs.getObject(1, LocalTime.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for timestamp columns.
   */
  public void testGetLocalDateTime() throws SQLException {
    Statement stmt = _conn.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("table1","timestamp_without_time_zone_column","TIMESTAMP '2004-10-19 10:23:54'"));

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "timestamp_without_time_zone_column"));
    try {
      assertTrue(rs.next());
      LocalDateTime localDateTime = LocalDateTime.of(2004, 10, 19, 10, 23, 54);
      assertEquals(localDateTime, rs.getObject("timestamp_without_time_zone_column", LocalDateTime.class));
      assertEquals(localDateTime, rs.getObject(1, LocalDateTime.class));
    } finally {
      rs.close();
    }
  }

  /**
   * Test the behavior getObject for timestamp with time zone columns.
   */
  public void testGetTimestampWithTimeZone() throws SQLException {
    runGetOffsetDateTime(UTC);
    runGetOffsetDateTime(GMT03);
    runGetOffsetDateTime(GMT05);
    runGetOffsetDateTime(GMT13);
  }

  private void runGetOffsetDateTime(ZoneOffset offset) throws SQLException {
    Statement stmt = _conn.createStatement();
    try {
      stmt.executeUpdate(TestUtil.insertSQL("table1","timestamp_with_time_zone_column","TIMESTAMP WITH TIME ZONE '2004-10-19 10:23:54" + offset.toString() + "'"));

      ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "timestamp_with_time_zone_column"));
      try {
        assertTrue(rs.next());
        LocalDateTime localDateTime = LocalDateTime.of(2004, 10, 19, 10, 23, 54);

        OffsetDateTime offsetDateTime = localDateTime.atOffset(offset).withOffsetSameInstant(ZoneOffset.UTC);
        assertEquals(offsetDateTime, rs.getObject("timestamp_with_time_zone_column", OffsetDateTime.class));
        assertEquals(offsetDateTime, rs.getObject(1, OffsetDateTime.class));
      } finally {
        rs.close();
      }
      stmt.executeUpdate("DELETE FROM table1");
    } finally {
      stmt.close();
    }
  }

}
