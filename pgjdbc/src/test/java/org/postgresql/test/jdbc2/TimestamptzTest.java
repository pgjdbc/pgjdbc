/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.postgresql.test.TestUtil;

/**
 * Tests both db and java side that correct type are passed.
 */
@RunWith(Parameterized.class)
public class TimestamptzTest extends BaseTest4 {

  public TimestamptzTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
//    setTimestamptzAlways(TimestamptzAlways.YES);
  }

  @Parameterized.Parameters(name = "timestamptzAlways = {1}")
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
    TestUtil.createSchema(con, "testtimestamp");
    TestUtil.createTable(con, "testtimestamp.tbtesttimestamp", "id bigint, ts timestamptz");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testtimestamp.tbtesttimestamp");
    TestUtil.dropSchema(con, "testtimestamp");
    super.tearDown();
  }

  /**
   * Aother example where overloading is necessary on sql site to choose the correct function
   */
  @Test
  public void testTypeOnDbSite_functionOverloading() throws SQLException {

    //SET time zone 'Europe/Berlin';
    try (PreparedStatement ps = con.prepareStatement("create or replace function testtimestamp.demoF(msg text, ts timestamptz) returns text language sql as $$select msg || ' at time ' || ts $$;  ")) {
      ps.executeUpdate();
    }
    try (PreparedStatement ps = con.prepareStatement("create or replace function testtimestamp.demoF(msg text, additional text) returns text language sql as $$select msg || ' wrongInfo:' || additional  $$; ")) {
      ps.executeUpdate();
    }

    GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("Europe/Berlin"));
    cal.set(2023, Calendar.MARCH, 12, 9, 30);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);

    try (PreparedStatement ps = con.prepareStatement(" SELECT  testtimestamp.demoF(?, ?) as res ")) {
      ps.setString(1, "Some data");
      ps.setTimestamp(2, new Timestamp(cal.getTimeInMillis()));

      try {
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
          String resultFunction = rs.getString("res");
          System.out.printf("result:%s \n", resultFunction);

          if (resultFunction.contains("wrongInfo")) {
            fail("choose wrong overloading");
          }

        } else {
          fail("no result");
        }
      } catch (SQLException e) {
        fail(e.getMessage());
      }
    }
  }



  /**
   * Test to demonstrate another example on additional unnecessary cast.
   * The driver know already the data type.
   */
  @Test
  public void testTypeOnDbSite_select() throws SQLException {

    //SET time zone 'Europe/Berlin';
    try (PreparedStatement ps = con.prepareStatement(" insert into testtimestamp.tbtesttimestamp (id, ts) values(1, '2023-03-12 10:00:00+1'::timestamptz ) ")) {
      ps.executeUpdate();
    }

    GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("Europe/Berlin"));
    cal.set(2023, Calendar.MARCH, 12, 9, 30);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);

    try (PreparedStatement ps = con.prepareStatement(" SELECT * from testtimestamp.tbtesttimestamp where ts < ?  +  interval '1 hour' ")) {
      ps.setTimestamp(1, new Timestamp(cal.getTimeInMillis()));

      try {
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
          System.out.printf("id:%d ts:%s \n", rs.getInt("id"), rs.getTimestamp("ts"));
        } else {
          fail("no result");
        }
      } catch (SQLException e) {
        fail(e.getMessage());
      }
    }
  }

  @Test
  public void testTypeOnDbSite() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(" SELECT pg_typeof(?) ")) {
      ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));

      try {
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
          String typeFromDb = rs.getString(1);
          System.out.printf("db type: %s\n",typeFromDb);
          assertTrue("timestamp with time zone".equalsIgnoreCase(typeFromDb));
        } else {
          fail("no result");
        }
      } catch (SQLException e) {
        fail(e.getMessage());
      }
    }
  }

  @Test
  public void testTypeOnJavaSite() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(" SELECT ? ")) {
      ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
      ResultSet rs = ps.executeQuery();
      ResultSetMetaData md = rs.getMetaData();
      assertEquals(java.sql.Types.TIMESTAMP_WITH_TIMEZONE, md.getColumnType(1)); // currently give instead TIMESTAMP
    }
  }
  
  
  
  @Test
  public void testJavaTime_ZonedDateTime() throws SQLException {
    GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("Europe/Berlin"));
    cal.set(2023, Calendar.MARCH, 12, 9, 30);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    
    //Problem 
    // java.lang.AssertionError: Can't infer the SQL type to use for an instance of java.time.ZonedDateTime. Use setObject() with an explicit Types value to specify the type to use.
    try (PreparedStatement ps = con.prepareStatement(" SELECT pg_typeof(?) ")) {
      ps.setObject(1, cal.toZonedDateTime());
    } catch (SQLException e) {
      fail(e.getMessage());
    }
    
    //Problem 
    // java.lang.AssertionError: Bad value for type timestamp/date/time: 2023-03-12T09:30+01:00[Europe/Berlin]
    try (PreparedStatement ps = con.prepareStatement(" SELECT pg_typeof(?) ")) {
      ps.setObject(1, cal.toZonedDateTime(), Types.TIMESTAMP);
    } catch (SQLException e) {
      fail(e.getMessage());
    }
  }
  
  
  @Test
  public void testJavaTime_Instant() throws SQLException {
    //Problem
    // java.lang.AssertionError: Can't infer the SQL type to use for an instance of java.time.ZonedDateTime. Use setObject() with an explicit Types value to specify the type to use.
    try (PreparedStatement ps = con.prepareStatement(" SELECT pg_typeof(?) ")) {
      ps.setObject(1, Instant.now());
    } catch (SQLException e) {
      fail(e.getMessage());
    }
    
    //Problem 
    // java.lang.AssertionError: Bad value for type timestamp/date/time: 2023-03-13T20:34:58.711330339Z
    try (PreparedStatement ps = con.prepareStatement(" SELECT pg_typeof(?) ")) {
      ps.setObject(1, Instant.now(), Types.TIMESTAMP);
    } catch (SQLException e) {
      fail(e.getMessage());
    }
  }
  
  
  @Test
  public void testJavaTime_LocalTime() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(" SELECT pg_typeof(?) ")) {
      ps.setObject(1, LocalTime.now());
    } catch (SQLException e) {
      fail(e.getMessage());
    }
  }
  
  
  @Test
  public void testJavaTime_LocalDateTime() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(" SELECT pg_typeof(?) ")) {
      ps.setObject(1, LocalDateTime.now());
    } catch (SQLException e) {
      fail(e.getMessage());
    }
  }
  
  
}
