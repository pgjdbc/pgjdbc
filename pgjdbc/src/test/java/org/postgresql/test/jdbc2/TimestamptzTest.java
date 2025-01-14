/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.test.TestUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Tests both db and java side that correct type are passed.
 */
@RunWith(Parameterized.class)
public class TimestamptzTest extends BaseTest4 {

  public TimestamptzTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
    setTimestamptzAlways(TimestamptzAlways.YES);
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

      ResultSet rs = ps.executeQuery();
      if (rs.next()) {
        String resultFunction = rs.getString("res");
        //System.out.printf("result:%s \n", resultFunction);

        if (resultFunction.contains("wrongInfo")) {
          fail("choose wrong overloading testtimestamp.demoF(text, text) instead of the one testtimestamp.demoF(text, timestamptz)");
        }

      } else {
        fail("no result");
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

      ResultSet rs = ps.executeQuery();
      if (rs.next()) {
        int id = rs.getInt("id");
        Timestamp ts = rs.getTimestamp("ts");
        //System.out.printf("id:%d ts:%s \n", id, ts);

        assertEquals(1, id);

      } else {
        fail("no result");
      }
    }
  }


  /**
   * Add test for null and correct value of null parameter type.
   * The test should work with and without TimestamptzAlways
   * If correct data type is not passed in case of null postgres return: org.postgresql.util.PSQLException: ERROR: could not determine data type of parameter ...
   */
  @Test
  public void testTypeOnDbSite_select_nullability() throws SQLException {

    try (PreparedStatement ps = con.prepareStatement("select 1 where ? is null  ")) {
      ps.setTimestamp(1, null);
      ResultSet rs = ps.executeQuery();
      if (!rs.next()) {
        fail("no result");
      }
    }

    try (PreparedStatement ps = con.prepareStatement(" insert into testtimestamp.tbtesttimestamp (id, ts) values(1, '2023-03-12 10:00:00+1'::timestamptz ) ")) {
      ps.executeUpdate();
    }
    try (PreparedStatement ps = con.prepareStatement(" SELECT * from testtimestamp.tbtesttimestamp where (? is null or ts >= ?) ")) {
      ps.setTimestamp(1, null);
      ps.setTimestamp(2, null);
      ResultSet rs = ps.executeQuery();
      if (!rs.next()) {
        fail("no result");
      }
    }
  }

  @Test
  public void testTypeOnDbSite() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(" SELECT pg_typeof(?) ")) {
      ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));

      ResultSet rs = ps.executeQuery();
      if (rs.next()) {
        String typeFromDb = rs.getString(1);
        //System.out.printf("db type: %s\n",typeFromDb);
        assertTrue("timestamp with time zone".equalsIgnoreCase(typeFromDb));
      } else {
        fail("no result");
      }
    }
  }

  @Test
  public void testTypeOnJavaSite() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(" SELECT ? ")) {
      ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
      ResultSet rs = ps.executeQuery();
      ResultSetMetaData md = rs.getMetaData();
      assertEquals(java.sql.Types.TIMESTAMP_WITH_TIMEZONE, md.getColumnType(1));
    }
  }


  @Test
  public void testJavaTime_LocalTime() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(" SELECT pg_typeof(?) ")) {
      ps.setObject(1, LocalTime.now());
    }
  }

  @Test
  public void testJavaTime_LocalDateTime() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(" SELECT pg_typeof(?) ")) {
      ps.setObject(1, LocalDateTime.now());
    }
  }

}
