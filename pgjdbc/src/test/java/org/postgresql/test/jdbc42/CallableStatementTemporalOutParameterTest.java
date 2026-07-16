/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Properties;
import java.util.TimeZone;

/**
 * Repro and regression guard for F-13: a temporal OUT parameter is decoded twice. {@code
 * executeWithFlags} materializes {@code rs.getObject(col)} into {@code callResult}, and the temporal
 * getters then either render that object to a string and re-parse it ({@code getTimestamp(index,
 * cal)} does {@code toTimestamp(cal, result.toString())}) or cannot reach the codec at all ({@code
 * getObject(index, Class)}).
 *
 * <p>Both seams are observable through two invariants that the connection-level {@code getObject}
 * default preference ({@code java.sql} vs {@code java.time}) must NOT affect:</p>
 *
 * <ul>
 *   <li>a {@code java.sql} temporal getter with a {@link Calendar} returns the same concrete value;</li>
 *   <li>{@code getObject(index, Class)} decodes to the requested class either way.</li>
 * </ul>
 *
 * <p>The untyped {@code getObject(index)} is deliberately not compared across the preference: its
 * class changes by contract of the setting.</p>
 */
public class CallableStatementTemporalOutParameterTest extends BaseTest4 {

  private static final TimeZone ZONE = TimeZone.getTimeZone("GMT+05:00");

  @Override
  public void setUp() throws Exception {
    super.setUp();
    createReturningFunction("f13_date", "date", "DATE '2020-01-02'");
    createReturningFunction("f13_time", "time", "TIME '12:34:56.789'");
    createReturningFunction("f13_timetz", "timetz", "TIMETZ '12:34:56.789+03'");
    createReturningFunction("f13_ts", "timestamp", "TIMESTAMP '2020-01-02 12:34:56.789'");
    createReturningFunction("f13_tstz", "timestamptz", "TIMESTAMPTZ '2020-01-02 12:34:56.789+03'");
    createReturningFunction("f13_null_ts", "timestamp", "NULL::timestamp");
  }

  @Override
  public void tearDown() throws SQLException {
    for (String fn : new String[]{"f13_date", "f13_time", "f13_timetz", "f13_ts", "f13_tstz",
        "f13_null_ts"}) {
      TestUtil.dropFunction(con, fn, "");
    }
    super.tearDown();
  }

  private void createReturningFunction(String name, String pgType, String value) throws SQLException {
    TestUtil.execute(con, "CREATE OR REPLACE FUNCTION " + name + "() RETURNS " + pgType
        + " AS $$ SELECT " + value + " $$ LANGUAGE sql");
  }

  // ---------------------------------------------------------------------------
  // Rule 1: getDate/getTime/getTimestamp with a Calendar are preference-independent.
  // ---------------------------------------------------------------------------

  @Test
  public void getTimestampWithCalendarIsPreferenceIndependent() throws Exception {
    assumeCallableStatementsSupported();
    // '2020-01-02 12:34:56.789' read as a wall clock in GMT+05:00 is this instant. Asserting the
    // exact value (not only java.sql == java.time) stops both paths from passing while identically
    // wrong.
    Timestamp expected = new Timestamp(Instant.parse("2020-01-02T07:34:56.789Z").toEpochMilli());
    assertEquals(expected, getTimestampCal(con),
        "getTimestamp(index, cal) under the legacy java.sql preference");
    try (Connection javaTime = openJavaTimeConnection()) {
      assertEquals(expected, getTimestampCal(javaTime),
          "getTimestamp(index, cal) must not depend on the getObject java.time preference");
    }
  }

  @Test
  public void getTimeWithCalendarIsPreferenceIndependent() throws Exception {
    assumeCallableStatementsSupported();
    // '12:34:56.789' read as a wall clock in GMT+05:00 is this instant on the epoch day; the
    // milliseconds must survive (the toString round-trip drops them on the java.sql path).
    Time expected = new Time(Instant.parse("1970-01-01T07:34:56.789Z").toEpochMilli());
    assertEquals(expected, getTimeCal(con),
        "getTime(index, cal) under the legacy java.sql preference");
    try (Connection javaTime = openJavaTimeConnection()) {
      assertEquals(expected, getTimeCal(javaTime),
          "getTime(index, cal) must not depend on the getObject java.time preference");
    }
  }

  @Test
  public void getDateWithCalendarIsPreferenceIndependent() throws Exception {
    assumeCallableStatementsSupported();
    // Midnight of '2020-01-02' in GMT+05:00 is this instant.
    Date expected = new Date(Instant.parse("2020-01-01T19:00:00Z").toEpochMilli());
    assertEquals(expected, getDateCal(con),
        "getDate(index, cal) under the legacy java.sql preference");
    try (Connection javaTime = openJavaTimeConnection()) {
      assertEquals(expected, getDateCal(javaTime),
          "getDate(index, cal) must not depend on the getObject java.time preference");
    }
  }

  // ---------------------------------------------------------------------------
  // Rule 1 (no Calendar): the plain java.sql temporal getters are preference-independent too. Under
  // getobject*=java.time the materialized value is a java.time type, so a cast would throw; the
  // getters must decode from the snapshot instead.
  // ---------------------------------------------------------------------------

  @Test
  public void getTimestampNoCalendarIsPreferenceIndependent() throws Exception {
    assumeCallableStatementsSupported();
    Timestamp javaSql = call(con, "f13_ts", Types.TIMESTAMP, cs -> cs.getTimestamp(1));
    assertEquals(Timestamp.valueOf("2020-01-02 12:34:56.789"), javaSql);
    try (Connection javaTime = openJavaTimeConnection()) {
      assertEquals(javaSql, call(javaTime, "f13_ts", Types.TIMESTAMP, cs -> cs.getTimestamp(1)));
    }
  }

  @Test
  public void getTimeNoCalendarIsPreferenceIndependent() throws Exception {
    assumeCallableStatementsSupported();
    // Time.valueOf cannot express the .789 fraction, so pin preference-independence by equality
    // between the two connections plus a non-null result rather than a literal.
    Time javaSql = call(con, "f13_time", Types.TIME, cs -> cs.getTime(1));
    assertNotNull(javaSql);
    try (Connection javaTime = openJavaTimeConnection()) {
      assertEquals(javaSql, call(javaTime, "f13_time", Types.TIME, cs -> cs.getTime(1)));
    }
  }

  @Test
  public void getDateNoCalendarIsPreferenceIndependent() throws Exception {
    assumeCallableStatementsSupported();
    Date javaSql = call(con, "f13_date", Types.DATE, cs -> cs.getDate(1));
    assertEquals(Date.valueOf("2020-01-02"), javaSql);
    try (Connection javaTime = openJavaTimeConnection()) {
      assertEquals(javaSql, call(javaTime, "f13_date", Types.DATE, cs -> cs.getDate(1)));
    }
  }

  // ---------------------------------------------------------------------------
  // NULL propagation and wasNull() across the delegated getter families.
  // ---------------------------------------------------------------------------

  @Test
  public void nullOutParameterReportsWasNullAcrossGetterFamilies() throws Exception {
    assumeCallableStatementsSupported();
    try (CallableStatement cs = con.prepareCall("{ ? = call f13_null_ts() }")) {
      cs.registerOutParameter(1, Types.TIMESTAMP);
      cs.execute();
      assertNull(cs.getTimestamp(1));
      assertTrue(cs.wasNull(), "wasNull after getTimestamp(int)");
      assertNull(cs.getTimestamp(1, new GregorianCalendar(ZONE)));
      assertTrue(cs.wasNull(), "wasNull after getTimestamp(int, cal)");
      assertNull(cs.getObject(1, LocalDateTime.class));
      assertTrue(cs.wasNull(), "wasNull after getObject(int, Class)");
    }
  }

  // ---------------------------------------------------------------------------
  // Rule 2: getObject(index, Class) decodes to the requested class either way.
  // ---------------------------------------------------------------------------

  @Test
  public void getObjectOffsetDateTimeIsPreferenceIndependent() throws Exception {
    assumeCallableStatementsSupported();
    // timestamptz reports as Types.TIMESTAMP (see PgType.getSQLType), so register that.
    Instant expected = Instant.parse("2020-01-02T09:34:56.789Z");
    assertEquals(expected, getObjectClass(con, "f13_tstz", Types.TIMESTAMP, OffsetDateTime.class).toInstant());
    try (Connection javaTime = openJavaTimeConnection()) {
      assertEquals(expected,
          getObjectClass(javaTime, "f13_tstz", Types.TIMESTAMP, OffsetDateTime.class).toInstant());
    }
  }

  @Test
  public void getObjectLocalDateTimeIsPreferenceIndependent() throws Exception {
    assumeCallableStatementsSupported();
    LocalDateTime expected = LocalDateTime.parse("2020-01-02T12:34:56.789");
    assertEquals(expected, getObjectClass(con, "f13_ts", Types.TIMESTAMP, LocalDateTime.class));
    try (Connection javaTime = openJavaTimeConnection()) {
      assertEquals(expected, getObjectClass(javaTime, "f13_ts", Types.TIMESTAMP, LocalDateTime.class));
    }
  }

  @Test
  public void getObjectLocalTimeIsPreferenceIndependent() throws Exception {
    assumeCallableStatementsSupported();
    LocalTime expected = LocalTime.parse("12:34:56.789");
    assertEquals(expected, getObjectClass(con, "f13_time", Types.TIME, LocalTime.class));
    try (Connection javaTime = openJavaTimeConnection()) {
      assertEquals(expected, getObjectClass(javaTime, "f13_time", Types.TIME, LocalTime.class));
    }
  }

  @Test
  public void getObjectOffsetTimeIsPreferenceIndependent() throws Exception {
    assumeCallableStatementsSupported();
    OffsetTime expected = OffsetTime.parse("12:34:56.789+03:00");
    assertEquals(expected, getObjectClass(con, "f13_timetz", Types.TIME, OffsetTime.class));
    try (Connection javaTime = openJavaTimeConnection()) {
      assertEquals(expected, getObjectClass(javaTime, "f13_timetz", Types.TIME, OffsetTime.class));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  private Connection openJavaTimeConnection() throws SQLException {
    Properties props = new Properties();
    PGProperty.GETOBJECT_DATE.set(props, "java.time");
    PGProperty.GETOBJECT_TIME.set(props, "java.time");
    PGProperty.GETOBJECT_TIMETZ.set(props, "java.time");
    PGProperty.GETOBJECT_TIMESTAMP.set(props, "java.time");
    PGProperty.GETOBJECT_TIMESTAMPTZ.set(props, "java.time");
    return TestUtil.openDB(props);
  }

  private interface CsRead<T> {
    T read(CallableStatement cs) throws SQLException;
  }

  private static <T> T call(Connection c, String fn, int registerType, CsRead<T> read)
      throws SQLException {
    try (CallableStatement cs = c.prepareCall("{ ? = call " + fn + "() }")) {
      cs.registerOutParameter(1, registerType);
      cs.execute();
      return read.read(cs);
    }
  }

  private static Timestamp getTimestampCal(Connection c) throws SQLException {
    try (CallableStatement cs = c.prepareCall("{ ? = call f13_ts() }")) {
      cs.registerOutParameter(1, Types.TIMESTAMP);
      cs.execute();
      return cs.getTimestamp(1, new GregorianCalendar(ZONE));
    }
  }

  private static Time getTimeCal(Connection c) throws SQLException {
    try (CallableStatement cs = c.prepareCall("{ ? = call f13_time() }")) {
      cs.registerOutParameter(1, Types.TIME);
      cs.execute();
      return cs.getTime(1, new GregorianCalendar(ZONE));
    }
  }

  private static Date getDateCal(Connection c) throws SQLException {
    try (CallableStatement cs = c.prepareCall("{ ? = call f13_date() }")) {
      cs.registerOutParameter(1, Types.DATE);
      cs.execute();
      return cs.getDate(1, new GregorianCalendar(ZONE));
    }
  }

  private static <T> T getObjectClass(Connection c, String fn, int registerType, Class<T> target)
      throws SQLException {
    try (CallableStatement cs = c.prepareCall("{ ? = call " + fn + "() }")) {
      cs.registerOutParameter(1, registerType);
      cs.execute();
      return cs.getObject(1, target);
    }
  }
}
