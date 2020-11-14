/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.chrono.IsoChronology;
import java.time.chrono.IsoEra;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

@RunWith(Parameterized.class)
public class SetObject310Test extends BaseTest4 {
  private static final TimeZone saveTZ = TimeZone.getDefault();

  public static final DateTimeFormatter LOCAL_TIME_FORMATTER =
      new DateTimeFormatterBuilder()
          .parseCaseInsensitive()
          .appendValue(ChronoField.YEAR_OF_ERA, 4, 10, SignStyle.EXCEEDS_PAD)
          .appendLiteral('-')
          .appendValue(ChronoField.MONTH_OF_YEAR, 2)
          .appendLiteral('-')
          .appendValue(ChronoField.DAY_OF_MONTH, 2)
          .appendLiteral(' ')
          .append(DateTimeFormatter.ISO_LOCAL_TIME)
          .optionalStart()
          .appendOffset("+HH:mm", "+00")
          .optionalEnd()
          .optionalStart()
          .appendLiteral(' ')
          .appendPattern("GG")
          .toFormatter(Locale.ROOT)
          .withResolverStyle(ResolverStyle.LENIENT)
          .withChronology(IsoChronology.INSTANCE);

  public SetObject310Test(BaseTest4.BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BaseTest4.BinaryMode binaryMode : BaseTest4.BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Before
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "table1", "timestamp_without_time_zone_column timestamp without time zone,"
            + "timestamp_with_time_zone_column timestamp with time zone,"
            + "date_column date,"
            + "time_without_time_zone_column time without time zone,"
            + "time_with_time_zone_column time with time zone"
    );
  }

  @After
  public void tearDown() throws SQLException {
    TimeZone.setDefault(saveTZ);
    TestUtil.dropTable(con, "table1");
    super.tearDown();
  }

  private void insert(Object data, String columnName, Integer type) throws SQLException {
    PreparedStatement ps = con.prepareStatement(TestUtil.insertSQL("table1", columnName, "?"));
    try {
      if (type != null) {
        ps.setObject(1, data, type);
      } else {
        ps.setObject(1, data);
      }
      assertEquals(1, ps.executeUpdate());
    } finally {
      ps.close();
    }
  }

  private String readString(String columnName) throws SQLException {
    Statement st = con.createStatement();
    try {
      ResultSet rs = st.executeQuery(TestUtil.selectSQL("table1", columnName));
      try {
        assertNotNull(rs);
        assertTrue(rs.next());
        return rs.getString(1);
      } finally {
        rs.close();
      }
    } finally {
      st.close();
    }
  }

  private String insertThenReadStringWithoutType(LocalDateTime data, String columnName) throws SQLException {
    insert(data, columnName, null);
    return readString(columnName);
  }

  private String insertThenReadStringWithType(LocalDateTime data, String columnName) throws SQLException {
    insert(data, columnName, Types.TIMESTAMP);
    return readString(columnName);
  }

  private void insertWithoutType(Object data, String columnName) throws SQLException {
    insert(data, columnName, null);
  }

  private <T> T insertThenReadWithoutType(Object data, String columnName, Class<T> expectedType) throws SQLException {
    PreparedStatement ps = con.prepareStatement(TestUtil.insertSQL("table1", columnName, "?"));
    try {
      ps.setObject(1, data);
      assertEquals(1, ps.executeUpdate());
    } finally {
      ps.close();
    }

    Statement st = con.createStatement();
    try {
      ResultSet rs = st.executeQuery(TestUtil.selectSQL("table1", columnName));
      try {
        assertNotNull(rs);

        assertTrue(rs.next());
        return expectedType.cast(rs.getObject(1));
      } finally {
        rs.close();
      }
    } finally {
      st.close();
    }
  }

  private <T> T insertThenReadWithType(Object data, int sqlType, String columnName, Class<T> expectedType) throws SQLException {
    PreparedStatement ps = con.prepareStatement(TestUtil.insertSQL("table1", columnName, "?"));
    try {
      ps.setObject(1, data, sqlType);
      assertEquals(1, ps.executeUpdate());
    } finally {
      ps.close();
    }

    Statement st = con.createStatement();
    try {
      ResultSet rs = st.executeQuery(TestUtil.selectSQL("table1", columnName));
      try {
        assertNotNull(rs);

        assertTrue(rs.next());
        return expectedType.cast(rs.getObject(1));
      } finally {
        rs.close();
      }
    } finally {
      st.close();
    }
  }

  private void deleteRows() throws SQLException {
    Statement st = con.createStatement();
    try {
      st.executeUpdate("DELETE FROM table1");
    } finally {
      st.close();
    }
  }

  /**
   * Test the behavior of setObject for timestamp columns.
   */
  @Test
  public void testSetLocalDateTime() throws SQLException {
    List<String> zoneIdsToTest = getZoneIdsToTest();
    List<String> datesToTest = getDatesToTest();

    for (String zoneId : zoneIdsToTest) {
      ZoneId zone = ZoneId.of(zoneId);
      for (String date : datesToTest) {
        LocalDateTime localDateTime = LocalDateTime.parse(date);
        String expected = localDateTime.atZone(zone)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .replace('T', ' ');
        localTimestamps(zone, localDateTime, expected);
      }
    }
  }

  /**
   * Test the behavior of setObject for timestamp columns.
   */
  @Test
  public void testSetOffsetDateTime() throws SQLException {
    List<String> zoneIdsToTest = getZoneIdsToTest();
    List<TimeZone> storeZones = new ArrayList<TimeZone>();
    for (String zoneId : zoneIdsToTest) {
      storeZones.add(TimeZone.getTimeZone(zoneId));
    }
    List<String> datesToTest = getDatesToTest();

    for (TimeZone timeZone : storeZones) {
      ZoneId zoneId = timeZone.toZoneId();
      for (String date : datesToTest) {
        LocalDateTime localDateTime = LocalDateTime.parse(date);
        String expected = date.replace('T', ' ');
        offsetTimestamps(zoneId, localDateTime, expected, storeZones);
      }
    }
  }

  private List<String> getDatesToTest() {
    return Arrays.asList("2015-09-03T12:00:00", "2015-06-30T23:59:58",
            "1997-06-30T23:59:59", "1997-07-01T00:00:00", "2012-06-30T23:59:59", "2012-07-01T00:00:00",
            "2015-06-30T23:59:59", "2015-07-01T00:00:00", "2005-12-31T23:59:59", "2006-01-01T00:00:00",
            "2008-12-31T23:59:59", "2009-01-01T00:00:00", /* "2015-06-30T23:59:60", */ "2015-07-31T00:00:00",
            "2015-07-31T00:00:01", "2015-07-31T00:00:00.000001",

            // On 2000-03-26 02:00:00 Moscow went to DST, thus local time became 03:00:00
            "2000-03-26T01:59:59", "2000-03-26T02:00:00", "2000-03-26T02:00:01", "2000-03-26T02:59:59",
            "2000-03-26T03:00:00", "2000-03-26T03:00:01", "2000-03-26T03:59:59", "2000-03-26T04:00:00",
            "2000-03-26T04:00:01", "2000-03-26T04:00:00.000001",

            // This is a pre-1970 date, so check if it is rounded properly
            "1950-07-20T02:00:00",

            // Ensure the calendar is proleptic
            "1582-09-30T00:00:00", "1582-10-16T00:00:00",

            // On 2000-10-29 03:00:00 Moscow went to regular time, thus local time became 02:00:00
            "2000-10-29T01:59:59", "2000-10-29T02:00:00", "2000-10-29T02:00:01", "2000-10-29T02:59:59",
            "2000-10-29T03:00:00", "2000-10-29T03:00:01", "2000-10-29T03:59:59", "2000-10-29T04:00:00",
            "2000-10-29T04:00:01", "2000-10-29T04:00:00.000001");
  }

  private List<String> getZoneIdsToTest() {
    List<String> zoneIdsToTest = new ArrayList<String>();
    zoneIdsToTest.add("Africa/Casablanca"); // It is something like GMT+0..GMT+1
    zoneIdsToTest.add("America/Adak"); // It is something like GMT-10..GMT-9
    zoneIdsToTest.add("Atlantic/Azores"); // It is something like GMT-1..GMT+0
    zoneIdsToTest.add("Europe/Moscow"); // It is something like GMT+3..GMT+4 for 2000s
    zoneIdsToTest.add("Pacific/Apia"); // It is something like GMT+13..GMT+14
    zoneIdsToTest.add("Pacific/Niue"); // It is something like GMT-11..GMT-11
    for (int i = -12; i <= 13; i++) {
      zoneIdsToTest.add(String.format("GMT%+02d", i));
    }
    return zoneIdsToTest;
  }

  private void localTimestamps(ZoneId zoneId, LocalDateTime localDateTime, String expected) throws SQLException {
    TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
    String readBack = insertThenReadStringWithoutType(localDateTime, "timestamp_without_time_zone_column");
    assertEquals(
        "LocalDateTime=" + localDateTime + ", with TimeZone.default=" + zoneId + ", setObject(int, Object)",
        expected, readBack);
    deleteRows();

    readBack = insertThenReadStringWithType(localDateTime, "timestamp_without_time_zone_column");
    assertEquals(
        "LocalDateTime=" + localDateTime + ", with TimeZone.default=" + zoneId + ", setObject(int, Object, TIMESTAMP)",
        expected, readBack);
    deleteRows();
  }

  private void offsetTimestamps(ZoneId dataZone, LocalDateTime localDateTime, String expected, List<TimeZone> storeZones) throws SQLException {
    OffsetDateTime data = localDateTime.atZone(dataZone).toOffsetDateTime();
    try (PreparedStatement ps = con.prepareStatement(
        "select ?::timestamp with time zone, ?::timestamp with time zone")) {
      for (TimeZone storeZone : storeZones) {
        TimeZone.setDefault(storeZone);
        ps.setObject(1, data);
        ps.setObject(2, data, Types.TIMESTAMP_WITH_TIMEZONE);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          String noType = rs.getString(1);
          OffsetDateTime noTypeRes = OffsetDateTime.parse(noType.replace(' ', 'T') + ":00");
          assertEquals(
              "OffsetDateTime=" + data + " (with ZoneId=" + dataZone + "), with TimeZone.default="
                  + storeZone + ", setObject(int, Object)", data.toInstant(),
              noTypeRes.toInstant());
          String withType = rs.getString(2);
          OffsetDateTime withTypeRes = OffsetDateTime.parse(withType.replace(' ', 'T') + ":00");
          assertEquals(
              "OffsetDateTime=" + data + " (with ZoneId=" + dataZone + "), with TimeZone.default="
                  + storeZone + ", setObject(int, Object, TIMESTAMP_WITH_TIMEZONE)",
              data.toInstant(), withTypeRes.toInstant());
        }
      }
    }
  }

  @Test
  public void testLocalDateTimeRounding() throws SQLException {
    LocalDateTime dateTime = LocalDateTime.parse("2018-12-31T23:59:59.999999500");
    localTimestamps(ZoneOffset.UTC, dateTime, "2019-01-01 00:00:00");
  }

  @Test
  public void testTimeStampRounding() throws SQLException {
    // TODO: fix for binary
    assumeBinaryModeRegular();
    LocalTime time = LocalTime.parse("23:59:59.999999500");
    Time actual = insertThenReadWithoutType(time, "time_without_time_zone_column", Time.class);
    assertEquals(Time.valueOf("24:00:00"), actual);
  }

  @Test
  public void testTimeStampRoundingWithType() throws SQLException {
    // TODO: fix for binary
    assumeBinaryModeRegular();
    LocalTime time = LocalTime.parse("23:59:59.999999500");
    Time actual =
        insertThenReadWithType(time, Types.TIME, "time_without_time_zone_column", Time.class);
    assertEquals(Time.valueOf("24:00:00"), actual);
  }

  /**
   * Test the behavior of setObject for timestamp columns.
   */
  @Test
  public void testSetLocalDateTimeBc() throws SQLException {
    assumeTrue(TestUtil.haveIntegerDateTimes(con));

    // use BC for funsies
    List<LocalDateTime> bcDates = new ArrayList<LocalDateTime>();
    bcDates.add(LocalDateTime.parse("1997-06-30T23:59:59.999999").with(ChronoField.ERA, IsoEra.BCE.getValue()));
    bcDates.add(LocalDateTime.parse("0997-06-30T23:59:59.999999").with(ChronoField.ERA, IsoEra.BCE.getValue()));

    for (LocalDateTime bcDate : bcDates) {
      String expected = LOCAL_TIME_FORMATTER.format(bcDate);
      localTimestamps(ZoneOffset.UTC, bcDate, expected);
    }
  }

  /**
   * Test the behavior setObject for date columns.
   */
  @Test
  public void testSetLocalDateWithType() throws SQLException {
    LocalDate data = LocalDate.parse("1971-12-15");
    java.sql.Date actual = insertThenReadWithType(data, Types.DATE, "date_column", java.sql.Date.class);
    java.sql.Date expected = java.sql.Date.valueOf("1971-12-15");
    assertEquals(expected, actual);
  }

  /**
   * Test the behavior setObject for date columns.
   */
  @Test
  public void testSetLocalDateWithoutType() throws SQLException {
    LocalDate data = LocalDate.parse("1971-12-15");
    java.sql.Date actual = insertThenReadWithoutType(data, "date_column", java.sql.Date.class);
    java.sql.Date expected = java.sql.Date.valueOf("1971-12-15");
    assertEquals(expected, actual);
  }

  /**
   * Test the behavior setObject for time columns.
   */
  @Test
  public void testSetLocalTimeAndReadBack() throws SQLException {
    // TODO: fix for binary mode.
    //  Avoid micros truncation in org.postgresql.jdbc.PgResultSet#internalGetObject
    assumeBinaryModeRegular();
    LocalTime data = LocalTime.parse("16:21:51.123456");

    insertWithoutType(data, "time_without_time_zone_column");

    String readBack = readString("time_without_time_zone_column");
    assertEquals("16:21:51.123456", readBack);
  }

  /**
   * Test the behavior setObject for time columns.
   */
  @Test
  public void testSetLocalTimeWithType() throws SQLException {
    LocalTime data = LocalTime.parse("16:21:51");
    Time actual = insertThenReadWithType(data, Types.TIME, "time_without_time_zone_column", Time.class);
    Time expected = Time.valueOf("16:21:51");
    assertEquals(expected, actual);
  }

  /**
   * Test the behavior setObject for time columns.
   */
  @Test
  public void testSetLocalTimeWithoutType() throws SQLException {
    LocalTime data = LocalTime.parse("16:21:51");
    Time actual = insertThenReadWithoutType(data, "time_without_time_zone_column", Time.class);
    Time expected = Time.valueOf("16:21:51");
    assertEquals(expected, actual);
  }

}
