/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.chrono.IsoEra;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.TimeZone;

@RunWith(Parameterized.class)
public class SetObject310InfinityTests extends BaseTest4 {
  private static final TimeZone saveTZ = TimeZone.getDefault();

  public SetObject310InfinityTests(BinaryMode binaryMode, String timeZone) {
    setBinaryMode(binaryMode);
    TimeZone.setDefault(TimeZone.getTimeZone(timeZone));
  }

  @Parameterized.Parameters(name = "binary = {0}, timeZone = {1}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>(2);
    for (BaseTest4.BinaryMode binaryMode : BaseTest4.BinaryMode.values()) {
      for (String timeZone : Arrays.asList("America/New_York", "Europe/Moscow", "UTC")) {
        ids.add(new Object[]{binaryMode, timeZone});
      }
    }
    return ids;
  }

  @BeforeClass
  public static void createTables() throws Exception {
    try (Connection con = TestUtil.openDB();) {
      Assume.assumeTrue("PostgreSQL 8.3 does not support 'infinity' for 'date'",
          TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4));
      TestUtil.createTable(con, "table1", "timestamp_without_time_zone_column timestamp without "
          + "time zone,"
          + "timestamp_with_time_zone_column timestamp with time zone,"
          + "date_column date"
      );
    }
  }

  @AfterClass
  public static void dropTables() throws Exception {
    try (Connection con = TestUtil.openDB();) {
      TestUtil.dropTable(con, "table1");
    }
    TimeZone.setDefault(saveTZ);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.execute(con, "delete from table1");
  }

  @Test
  public void testTimestamptz() throws SQLException {
    runTestforType(OffsetDateTime.MAX, OffsetDateTime.MIN, "timestamp_without_time_zone_column",
        null);
  }

  @Test
  public void timestampTzExceedingMaxAfterRound() throws SQLException {
    assumeMinimumServerVersion(ServerVersion.v9_6);
    OffsetDateTime date =
        LocalDateTime.of(294276, Month.DECEMBER, 31, 23, 59, 59, 999999500)
            .atOffset(ZoneOffset.UTC);
    assertInsertThrowsDatetimeOverflow(date, "timestamp_with_time_zone_column");
  }

  @Test
  public void timestampTzExceedingMax() throws SQLException {
    assumeMinimumServerVersion(ServerVersion.v9_6);
    OffsetDateTime date =
        LocalDateTime.of(294277, Month.JANUARY, 1, 0, 0)
            .atOffset(ZoneOffset.UTC);
    assertInsertThrowsDatetimeOverflow(date, "timestamp_with_time_zone_column");
  }

  @Test
  public void timestampTzExceedingMin() {
    OffsetDateTime date = LocalDateTime.of(4714, Month.JANUARY, 1, 0, 0)
        .with(ChronoField.ERA, IsoEra.BCE.getValue())
        .atOffset(ZoneOffset.UTC);
    assertInsertThrowsDatetimeOverflow(date, "timestamp_with_time_zone_column");
  }

  @Test
  public void testTimestamp() throws SQLException {
    runTestforType(LocalDateTime.MAX, LocalDateTime.MIN, "timestamp_without_time_zone_column", null);
  }

  @Test
  public void timestampExceedingMaxAfterRound() throws SQLException {
    assumeMinimumServerVersion(ServerVersion.v9_6);
    LocalDateTime date = LocalDateTime.of(294276, Month.DECEMBER, 31, 23, 59, 59, 999999500);
    assertInsertThrowsDatetimeOverflow(date, "timestamp_without_time_zone_column");
  }

  @Test
  public void timestampExceedingMax() throws SQLException {
    assumeMinimumServerVersion(ServerVersion.v9_6);
    LocalDateTime date = LocalDateTime.of(294277, Month.JANUARY, 1, 0, 0);
    assertInsertThrowsDatetimeOverflow(date, "timestamp_without_time_zone_column");
  }

  @Test
  public void timestampExceedingMin() {
    // PostgresSQL 14.7
    //   accepts 4714-11-23 23:59:59.999999500 BC
    //   rejects 4714-11-23 23:59:59.999999499 BC
    //   documents 4713 BC as the low value for timestamp
    // We do not add tests around 4714-11-23 23 since it is not clear if that is an intended
    // database behaviour.
    LocalDateTime date = LocalDateTime.of(4714, Month.JANUARY, 1, 0, 0)
        .with(ChronoField.ERA, IsoEra.BCE.getValue());
    assertInsertThrowsDatetimeOverflow(date, "timestamp_without_time_zone_column");
  }

  @Test
  public void testDate() throws SQLException {
    runTestforType(LocalDate.MAX, LocalDate.MIN, "date_column", null);
  }

  @Test
  public void dateExceedingMax() {
    LocalDate date = LocalDate.of(5874898, Month.JANUARY, 1);
    assertInsertThrowsDatetimeOverflow(date, "date_column");
  }

  @Test
  public void dateExceedingMin() {
    LocalDate date = LocalDate.of(4714, Month.JANUARY, 1)
        .with(ChronoField.ERA, IsoEra.BCE.getValue());
    assertInsertThrowsDatetimeOverflow(date, "date_column");
  }

  private void assertInsertThrowsDatetimeOverflow(Object date, String columnName) {
    String message = "insert " + date + " into " + columnName
        + " without type should throw DATETIME_OVERFLOW";
    PSQLException e = assertThrows(PSQLException.class, () -> {
          insert(date, columnName);
          String value = readString(columnName);
          fail("Expected exception <<" + message + ">>, however, the value got inserted"
              + " successfully somehow, and the DB returns it as (getString) " + value);
        }
    );
    assertEquals(
        message + ", got " + e.getMessage(),
        e.getSQLState(),
        PSQLState.DATETIME_OVERFLOW.getState()
    );
  }

  private void runTestforType(Object max, Object min, String columnName, Integer type) throws SQLException {
    insert(max, columnName, type);
    String readback = readString(columnName);
    assertEquals("infinity", readback);
    delete();

    insert(min, columnName, type);
    readback = readString(columnName);
    assertEquals("-infinity", readback);
    delete();
  }

  private void insert(Object data, String columnName) throws SQLException {
    insert(data, columnName, null);
  }

  private void insert(Object data, String columnName, Integer type) throws SQLException {
    try (PreparedStatement ps =
             con.prepareStatement(TestUtil.insertSQL("table1", columnName, "?"))) {
      if (type != null) {
        ps.setObject(1, data, type);
      } else {
        ps.setObject(1, data);
      }
      assertEquals(1, ps.executeUpdate());
    }
  }

  private String readString(String columnName) throws SQLException {
    try (Statement st = con.createStatement()) {
      try (ResultSet rs = st.executeQuery(TestUtil.selectSQL("table1", columnName))) {
        assertTrue(rs.next());
        return rs.getString(1);
      }
    }
  }

  private <T> T readObject(String columnName, Class<T> klass) throws SQLException {
    try (Statement st = con.createStatement()) {
      try (ResultSet rs = st.executeQuery(TestUtil.selectSQL("table1", columnName))) {
        assertTrue(rs.next());
        return rs.getObject(1, klass);
      }
    }
  }

  private void delete() throws SQLException {
    try (Statement st = con.createStatement()) {
      st.execute("DELETE FROM table1");
    }
  }

}
