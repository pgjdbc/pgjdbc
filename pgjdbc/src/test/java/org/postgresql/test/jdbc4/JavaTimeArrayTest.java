/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Stream;

/**
 * Checks java.time array values against literal PostgreSQL output, independently of the scalar
 * formatter. UTC makes timestamptz output deterministic. Legacy java.sql values are compatibility
 * controls and retain their existing array rendering.
 */
@ParameterizedClass
@MethodSource("data")
public class JavaTimeArrayTest extends BaseTest4 {

  public JavaTimeArrayTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @BeforeEach
  void useUtcSessionTimeZone() throws SQLException {
    try (Statement st = con.createStatement()) {
      st.execute("SET TIME ZONE 'UTC'");
    }
  }

  public static Stream<Arguments> elements() {
    LocalDate ordinary = LocalDate.of(2024, 11, 11);
    LocalDate bc = LocalDate.of(-500, 1, 1); // 0501-01-01 BC
    LocalDate year10000 = LocalDate.of(10000, 1, 1); // five digits, so no fixed width promise
    LocalDate[][] twoDimensions = {
        {ordinary, null},
        {bc, year10000},
    };
    OffsetDateTime bcTimestamp = OffsetDateTime.of(-500, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    return Stream.of(
        // date
        Arguments.of("date, ordinary day", "date",
            new LocalDate[]{ordinary}, "{2024-11-11}"),
        Arguments.of("date, BC era", "date",
            new LocalDate[]{bc}, "{\"0501-01-01 BC\"}"),
        Arguments.of("date, five digit year", "date",
            new LocalDate[]{year10000}, "{10000-01-01}"),
        Arguments.of("date, ends of the supported range", "date",
            new LocalDate[]{LocalDate.MIN, LocalDate.MAX}, "{-infinity,infinity}"),
        Arguments.of("date, nulls between elements", "date",
            new LocalDate[]{ordinary, null, bc}, "{2024-11-11,NULL,\"0501-01-01 BC\"}"),

        // time
        Arguments.of("time, whole seconds", "time",
            new LocalTime[]{LocalTime.of(10, 15, 30)}, "{10:15:30}"),
        Arguments.of("time, microseconds", "time",
            new LocalTime[]{LocalTime.of(10, 15, 30, 123_456_000)}, "{10:15:30.123456}"),
        Arguments.of("time, half a microsecond rounds up", "time",
            new LocalTime[]{LocalTime.of(1, 2, 3, 500)}, "{01:02:03.000001}"),
        Arguments.of("time, rounds past the end of the day", "time",
            new LocalTime[]{LocalTime.of(23, 59, 59, 999_999_600)}, "{24:00:00}"),

        // timetz
        Arguments.of("timetz, offset travels with the element", "timetz",
            new OffsetTime[]{OffsetTime.of(10, 15, 30, 0, ZoneOffset.ofHoursMinutes(5, 30))},
            "{10:15:30+05:30}"),
        Arguments.of("timetz, UTC offset prints as +00", "timetz",
            new OffsetTime[]{OffsetTime.of(10, 15, 30, 0, ZoneOffset.UTC)}, "{10:15:30+00}"),
        Arguments.of("timetz, half a microsecond is dropped, not rounded", "timetz",
            new OffsetTime[]{OffsetTime.of(1, 2, 3, 500, ZoneOffset.ofHoursMinutes(5, 30))},
            "{01:02:03+05:30}"),
        Arguments.of("timetz, sub-microsecond part is truncated", "timetz",
            new OffsetTime[]{
                OffsetTime.of(10, 15, 30, 123_456_700, ZoneOffset.ofHoursMinutes(5, 30))},
            "{10:15:30.123456+05:30}"),

        // timestamp
        Arguments.of("timestamp, ordinary value", "timestamp",
            new LocalDateTime[]{LocalDateTime.of(2024, 11, 11, 1, 2, 3, 4000)},
            "{\"2024-11-11 01:02:03.000004\"}"),
        Arguments.of("timestamp, half a microsecond rounds up", "timestamp",
            new LocalDateTime[]{LocalDateTime.of(2024, 11, 11, 1, 2, 3, 500)},
            "{\"2024-11-11 01:02:03.000001\"}"),
        Arguments.of("timestamp, BC era", "timestamp",
            new LocalDateTime[]{LocalDateTime.of(-500, 1, 1, 0, 0)},
            "{\"0501-01-01 00:00:00 BC\"}"),
        Arguments.of("timestamp, five digit year", "timestamp",
            new LocalDateTime[]{LocalDateTime.of(10000, 1, 1, 2, 3, 4)},
            "{\"10000-01-01 02:03:04\"}"),
        Arguments.of("timestamp, rounds into the next day (control, unchanged)", "timestamp",
            new LocalDateTime[]{LocalDateTime.of(2024, 11, 11, 23, 59, 59, 999_999_500)},
            "{\"2024-11-12 00:00:00\"}"),
        Arguments.of("timestamp, ends of the supported range", "timestamp",
            new LocalDateTime[]{LocalDateTime.MIN, LocalDateTime.MAX}, "{-infinity,infinity}"),

        // timestamptz
        Arguments.of("timestamptz, offset travels to the session zone", "timestamptz",
            new OffsetDateTime[]{
                OffsetDateTime.of(2024, 11, 11, 1, 2, 3, 4000, ZoneOffset.ofHoursMinutes(5, 30))},
            "{\"2024-11-10 19:32:03.000004+00\"}"),
        Arguments.of("timestamptz, half a microsecond rounds up", "timestamptz",
            new OffsetDateTime[]{OffsetDateTime.of(2024, 11, 11, 1, 2, 3, 500, ZoneOffset.UTC)},
            "{\"2024-11-11 01:02:03.000001+00\"}"),
        Arguments.of("timestamptz, BC era", "timestamptz",
            new OffsetDateTime[]{bcTimestamp}, "{\"0501-01-01 00:00:00+00 BC\"}"),
        Arguments.of("timestamptz, ends of the supported range", "timestamptz",
            new OffsetDateTime[]{OffsetDateTime.MIN, OffsetDateTime.MAX}, "{-infinity,infinity}"),
        Arguments.of("timestamptz, nulls between elements", "timestamptz",
            new OffsetDateTime[]{null, bcTimestamp, null},
            "{NULL,\"0501-01-01 00:00:00+00 BC\",NULL}"),

        // nested arrays
        // A null sits inside a sub-array: the server refuses a null next to a sub-array of a
        // different dimension.
        Arguments.of("Object[] whose elements are arrays", "date",
            new Object[]{new LocalDate[]{bc, null}, new LocalDate[]{null, ordinary}},
            "{{\"0501-01-01 BC\",NULL},{NULL,2024-11-11}}"),
        Arguments.of("Object[][] rows reach the element encoder", "date",
            new Object[][]{{new LocalDate[]{bc}}, {new LocalDate[]{ordinary}}},
            "{{{\"0501-01-01 BC\"}},{{2024-11-11}}}"),
        Arguments.of("two dimensions", "date", twoDimensions,
            "{{2024-11-11,NULL},{\"0501-01-01 BC\",10000-01-01}}"),
        Arguments.of("three dimensions", "date", new LocalDate[][][]{twoDimensions, twoDimensions},
            "{{{2024-11-11,NULL},{\"0501-01-01 BC\",10000-01-01}},"
                + "{{2024-11-11,NULL},{\"0501-01-01 BC\",10000-01-01}}}"),

        // controls: elements the java.time formatting must not touch
        Arguments.of("control, text elements keep their escaping", "text",
            new String[]{"a\"b", "c\\d", "{\"2024-11-11\"}", "0501-01-01 BC", null},
            "{\"a\\\"b\",\"c\\\\d\",\"{\\\"2024-11-11\\\"}\",\"0501-01-01 BC\",NULL}"),
        Arguments.of("control, int elements", "int4", new int[]{1, 2, 3}, "{1,2,3}"),
        Arguments.of("control, Long elements", "int8", new Long[]{1L, null, 3L}, "{1,NULL,3}"),
        Arguments.of("control, java.sql.Date keeps the rendering it had before",
            "date", new java.sql.Date[]{java.sql.Date.valueOf(bc)}, "{0501-01-01}"),
        Arguments.of("control, java.sql.Timestamp keeps the rendering it had before",
            "timestamp", new Timestamp[]{Timestamp.valueOf(LocalDateTime.of(-500, 1, 1, 0, 0))},
            "{\"0501-01-01 00:00:00\"}"));
  }

  /**
   * Asserts that the server sees {@code expected} when the driver sends <i>elements</i> as
   * <i>pgType</i>[].
   *
   * <p>The array is sent through {@code createArrayOf}, which is the path that encodes the
   * elements as text; no binary element encoder exists for {@code java.time} values, so both
   * binary and text mode use that path.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("elements")
  void elementsCarryTheSameValueAsScalarParameters(String caseName, String pgType,
      Object elements, String expected) throws SQLException {
    // PgConnection takes the elements as Object, so a primitive array such as int[] is allowed.
    Array array = ((PgConnection) con).createArrayOf(pgType, elements);
    try (PreparedStatement ps = con.prepareStatement("SELECT CAST(? AS " + pgType + "[])::text")) {
      ps.setArray(1, array);
      assertEquals(expected, singleString(ps), caseName + " as " + pgType + "[]");
    } finally {
      array.free();
    }
  }

  private static String singleString(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next(), "query should return one row");
      return rs.getString(1);
    }
  }
}
