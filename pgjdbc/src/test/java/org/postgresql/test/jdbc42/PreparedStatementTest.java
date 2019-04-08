/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

@RunWith(Parameterized.class)
public class PreparedStatementTest extends BaseTest4 {

  @Parameters(name = "Force Binary: {0}")
  public static Collection<? extends Object> data() {
    return Arrays.asList(Boolean.FALSE, Boolean.TRUE);
  }

  @Parameter
  public boolean binary;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "timestamptztable", "tstz timestamptz");
    TestUtil.createTable(con, "timetztable", "ttz timetz");
    TestUtil.createTable(con, "timetable", "id serial, tt time");
    ((PgConnection)con).setForceBinary(binary);
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "timestamptztable");
    TestUtil.dropTable(con, "timetztable");
    TestUtil.dropTable(con, "timetable");
    super.tearDown();
  }

  @Test
  public void testTimestampTzSetNull() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO timestamptztable (tstz) VALUES (?)");

    // valid: fully qualified type to setNull()
    pstmt.setNull(1, Types.TIMESTAMP_WITH_TIMEZONE);
    pstmt.executeUpdate();

    // valid: fully qualified type to setObject()
    pstmt.setObject(1, null, Types.TIMESTAMP_WITH_TIMEZONE);
    pstmt.executeUpdate();

    pstmt.close();
  }

  @Test
  public void testTimeTzSetNull() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO timetztable (ttz) VALUES (?)");

    // valid: fully qualified type to setNull()
    pstmt.setNull(1, Types.TIME_WITH_TIMEZONE);
    pstmt.executeUpdate();

    // valid: fully qualified type to setObject()
    pstmt.setObject(1, null, Types.TIME_WITH_TIMEZONE);
    pstmt.executeUpdate();

    pstmt.close();
  }

  @Test
  public void testLocalTimeMax() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO timetable (tt) VALUES (?)");

    pstmt.setObject(1, LocalTime.MAX);
    pstmt.executeUpdate();

    pstmt.setObject(1, LocalTime.MIN);
    pstmt.executeUpdate();

    ResultSet rs = con.createStatement().executeQuery("select tt from timetable order by id asc");
    Assert.assertTrue(rs.next());

    LocalTime localTime = (LocalTime)rs.getObject(1, LocalTime.class);

    //postgres only has microsecond precision
    // Assert.assertEquals(LocalTime.parse("23:59:59.999999"), localTime);
    Assert.assertEquals(LocalTime.MAX, localTime);

    Time defTime = rs.getTime(1);
    Assert.assertNotNull(defTime);

    final Instant localTimeDefInstant = ZonedDateTime.of(LocalDate.of(1970, 1, 1), localTime, ZoneId.systemDefault()).toInstant();

    //assert that LocalTime and java.sql.Time have consistent representation
    Assert.assertEquals(toMillis(localTimeDefInstant), defTime.getTime());

    final Calendar gmtCal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

    Time gmtTime = rs.getTime(1, gmtCal);

    Instant localTimeGMTInstant = ZonedDateTime.of(LocalDate.of(1970, 1, 1), localTime, ZoneId.of("UTC")).toInstant();

    Assert.assertEquals(toMillis(localTimeGMTInstant), gmtTime.getTime());

    final Calendar cstCal = Calendar.getInstance(TimeZone.getTimeZone("America/Chicago"));

    Time cstTime = rs.getTime(1, cstCal);

    Instant localTimeCSTInstant = ZonedDateTime.of(LocalDate.of(1970, 1, 1), localTime, ZoneId.of("America/Chicago")).toInstant();

    Assert.assertEquals(toMillis(localTimeCSTInstant), cstTime.getTime());

    Assert.assertTrue(rs.next());

    localTime = (LocalTime)rs.getObject(1, LocalTime.class);

    Assert.assertEquals(LocalTime.MIN, localTime);
    gmtTime = rs.getTime(1, gmtCal);

    localTimeGMTInstant = ZonedDateTime.of(LocalDate.of(1970, 1, 1), localTime, ZoneId.of("UTC")).toInstant();

    Assert.assertEquals(toMillis(localTimeGMTInstant), gmtTime.getTime());

    cstTime = rs.getTime(1, cstCal);

    localTimeCSTInstant = ZonedDateTime.of(LocalDate.of(1970, 1, 1), localTime, ZoneId.of("America/Chicago")).toInstant();

    Assert.assertEquals(toMillis(localTimeCSTInstant), cstTime.getTime());
  }

  @Test
  public void test2400() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO timetable (tt) VALUES (?::time)");

    pstmt.setString(1, "24:00:00.000");
    pstmt.executeUpdate();

    pstmt = con.prepareStatement("INSERT INTO timetable (tt) VALUES (?)");

    final Calendar gmtCal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

    gmtCal.set(1970, 0, 2, 0, 0, 0);
    gmtCal.set(Calendar.MILLISECOND, 0);

    final long gmtPoint = gmtCal.getTimeInMillis();

    pstmt.setTime(1, new Time(gmtPoint), gmtCal);
    pstmt.executeUpdate();

    final Calendar cstCal = Calendar.getInstance(TimeZone.getTimeZone("America/Chicago"));

    cstCal.set(1970, 0, 2, 0, 0, 0);
    cstCal.set(Calendar.MILLISECOND, 0);

    final long cstPoint = cstCal.getTimeInMillis();

    pstmt.setTime(1, new Time(cstPoint), cstCal);
    pstmt.executeUpdate();

    ResultSet rs = con.createStatement().executeQuery("select tt from timetable order by id asc");
    //24:00:00
    Assert.assertTrue(rs.next());

    LocalTime localTime = (LocalTime)rs.getObject(1, LocalTime.class);

    //TODO: should this be MAX
    Assert.assertEquals(LocalTime.MAX, localTime);
    Assert.assertEquals(gmtPoint, rs.getTime(1, gmtCal).getTime());
    Assert.assertEquals(cstPoint, rs.getTime(1, cstCal).getTime());

    //setTime results in 00:00
    Assert.assertTrue(rs.next());

    localTime = (LocalTime)rs.getObject(1, LocalTime.class);

    //TODO: why is this different than the above assertion where 24:00:00 set explicitly?
    Assert.assertEquals(LocalTime.MIN, localTime);
    Assert.assertEquals(gmtPoint - TimeUnit.DAYS.toMillis(1), rs.getTime(1, gmtCal).getTime());
    Assert.assertEquals(cstPoint - TimeUnit.DAYS.toMillis(1), rs.getTime(1, cstCal).getTime());

    Assert.assertTrue(rs.next());

    localTime = (LocalTime)rs.getObject(1, LocalTime.class);

    //TODO: why is this different than the above assertion where 24:00:00 set explicitly?
    Assert.assertEquals(LocalTime.MIN, localTime);
    Assert.assertEquals(gmtPoint - TimeUnit.DAYS.toMillis(1), rs.getTime(1, gmtCal).getTime());
    Assert.assertEquals(cstPoint - TimeUnit.DAYS.toMillis(1), rs.getTime(1, cstCal).getTime());
  }

  private static long toMillis(Instant instant) {
    return (instant.getEpochSecond() * 1000) + instant.getNano() / 1000000;
  }
}
