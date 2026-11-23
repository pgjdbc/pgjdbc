/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Array;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TimeZone;

/**
 * Regression coverage for era (BC) preservation of java.sql.Date / java.sql.Timestamp elements when
 * bound as array parameters via {@link java.sql.Connection#createArrayOf(String, Object[])}.
 *
 * <p>Array elements are expected to keep the same era marker as the driver's own scalar
 * {@code setObject} path produces for the same value. Controls (scalar paths, AD values, text[] of
 * java.sql values, java.sql.Time rendering) must pass regardless of any array-era change.</p>
 */
@ParameterizedClass
@MethodSource("data")
@Isolated("Uses TimeZone.setDefault")
public class SqlDateArrayEraTest extends BaseTest4 {

  private static final String SESSION_ZONE = "Europe/Paris";

  private final String zoneId;

  private TimeZone savedDefaultZone;

  public SqlDateArrayEraTest(String zoneId, BinaryMode binaryMode) {
    this.zoneId = zoneId;
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (String zoneId : Arrays.asList("UTC", "America/Los_Angeles")) {
      for (BinaryMode binaryMode : BinaryMode.values()) {
        ids.add(new Object[]{zoneId, binaryMode});
      }
    }
    return ids;
  }

  @Override
  protected void setUp() throws Exception {
    savedDefaultZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
    super.setUp();
  }

  @Override
  public void tearDown() throws SQLException {
    try {
      super.tearDown();
    } finally {
      TimeZone.setDefault(savedDefaultZone);
    }
  }

  @Test
  public void dateArrayKeepsBcEra() throws SQLException {
    Date bc501 = Date.valueOf(LocalDate.of(-500, 1, 1));
    Date bc1 = Date.valueOf(LocalDate.of(0, 1, 1));
    Date ad1 = Date.valueOf(LocalDate.of(1, 1, 1));
    Date ad2024 = Date.valueOf(LocalDate.of(2024, 2, 29));

    List<String> values = arrayElements("date", "date[]", new Date[]{bc501, null, bc1, ad1, ad2024});

    // Real scalar SQL result text establishes the expected era rendering.
    assertEquals("0501-01-01 BC", scalarText("date", bc501));
    assertEquals("0001-01-01 BC", scalarText("date", bc1));
    assertEquals(
        Arrays.asList("0501-01-01 BC", null, "0001-01-01 BC", "0001-01-01", "2024-02-29"),
        values);
  }

  @Test
  public void timestampArrayKeepsBcEraAndFractions() throws SQLException {
    Timestamp bc501 = Timestamp.valueOf(LocalDateTime.of(-500, 1, 1, 0, 0));
    Timestamp bcFrac =
        Timestamp.valueOf(LocalDateTime.of(-1, 12, 31, 23, 59, 59, 123_456_000));
    Timestamp bc1 = Timestamp.valueOf(LocalDateTime.of(0, 1, 1, 12, 34, 56, 500_000_000));
    Timestamp ad1 = Timestamp.valueOf(LocalDateTime.of(1, 1, 1, 0, 0, 0, 0));
    Timestamp ad2024 = Timestamp.valueOf(LocalDateTime.of(2024, 2, 29, 1, 2, 3, 7_000_000));

    List<String> values = arrayElements("timestamp", "timestamp[]",
        new Timestamp[]{bc501, null, bcFrac, bc1, ad1, ad2024});

    assertEquals("0501-01-01 00:00:00 BC", scalarText("timestamp", bc501));
    assertEquals("0002-12-31 23:59:59.123456 BC", scalarText("timestamp", bcFrac));
    assertEquals(
        Arrays.asList(
            "0501-01-01 00:00:00 BC",
            null,
            "0002-12-31 23:59:59.123456 BC",
            "0001-01-01 12:34:56.5 BC",
            "0001-01-01 00:00:00",
            "2024-02-29 01:02:03.007"),
        values);
  }

  @Test
  public void nestedDateArraysKeepBcEraWhenUnnested() throws SQLException {
    Date bc501 = Date.valueOf(LocalDate.of(-500, 1, 1));
    Date bc1 = Date.valueOf(LocalDate.of(0, 6, 15));
    Date ad2024 = Date.valueOf(LocalDate.of(2024, 2, 29));

    Date[][] nested = new Date[][]{{bc501, null}, {bc1, ad2024}};

    Array sqlArray = null;
    try {
      sqlArray = con.createArrayOf("date", nested);
      List<String> rows = new ArrayList<>();
      try (PreparedStatement ps = con.prepareStatement(
          "SELECT unnest(CAST(? AS date[][]))::text")) {
        ps.setArray(1, sqlArray);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            rows.add(rs.getString(1));
          }
        }
      }
      assertEquals(Arrays.asList("0501-01-01 BC", null, "0001-06-15 BC", "2024-02-29"), rows);
    } finally {
      if (sqlArray != null) {
        sqlArray.free();
      }
    }
  }

  @Test
  public void objectArrayOfDatesKeepsBcEraWhenUnnested() throws SQLException {
    Date bc1 = Date.valueOf(LocalDate.of(0, 1, 1));
    Date adDate = Date.valueOf(LocalDate.of(2024, 2, 29));

    List<String> values =
        arrayElements("date", "date[]", new Object[]{bc1, null, adDate, "2024-02-29"});

    assertEquals(
        Arrays.asList("0001-01-01 BC", null, "2024-02-29", "2024-02-29"), values);
  }

  @Test
  public void scalarDateAndTimestampKeepBcEra() throws SQLException {
    Date date = Date.valueOf(LocalDate.of(-500, 1, 1));
    Timestamp ts = Timestamp.valueOf(LocalDateTime.of(-500, 1, 1, 0, 0));

    assertEquals("0501-01-01 BC", scalarText("date", date));
    assertEquals("0501-01-01 00:00:00 BC", scalarText("timestamp", ts));
  }

  @Test
  public void adTimestampArrayBoundAsTimestamptzComparesInstant() throws SQLException {
    Timestamp ad = Timestamp.valueOf(LocalDateTime.of(2024, 3, 10, 12, 0, 0, 0));

    execute("SET TIME ZONE '" + SESSION_ZONE + "'");

    Array sqlArray = null;
    try {
      sqlArray = con.createArrayOf("timestamptz", new Timestamp[]{ad});
      try (PreparedStatement ps = con.prepareStatement(
          "SELECT (CAST(? AS timestamptz[]))[1] = TIMESTAMPTZ '2024-03-10 12:00:00 Europe/Paris'")) {
        ps.setArray(1, sqlArray);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertTrue(rs.getBoolean(1));
        }
      }
    } finally {
      if (sqlArray != null) {
        sqlArray.free();
      }
    }
  }

  @Test
  public void textArrayOfAdDateAndTimestampRetainsValues() throws SQLException {
    Date adDate = Date.valueOf(LocalDate.of(2024, 2, 29));
    Timestamp adTs = Timestamp.valueOf(LocalDateTime.of(2024, 2, 29, 1, 2, 3, 123_456_789));

    List<String> values = textElements(new Object[]{adDate, adTs});

    assertEquals(
        Arrays.asList("2024-02-29", "2024-02-29 01:02:03.123456789"), values);
  }

  @Test
  public void textArrayOfTimeRetainsSecondOnlyRendering() throws SQLException {
    Time time = new Time(Time.valueOf("12:34:56").getTime() + 4);

    List<String> values = textElements(new Time[]{time});

    assertEquals(Arrays.asList("12:34:56"), values);
  }

  @Test
  public void dateSubclassWithBcMarkerIsNotDuplicated() throws SQLException {
    final long millis = Date.valueOf(LocalDate.of(-500, 1, 1)).getTime();
    Date bcDate = new Date(millis) {
      @Override
      public String toString() {
        return "0501-01-01 BC";
      }
    };

    List<String> values = arrayElements("date", "date[]", new Date[]{bcDate});

    assertEquals(Arrays.asList("0501-01-01 BC"), values);
  }

  @Test
  public void bcTimestampArrayUsesSessionTimeZone() throws SQLException {
    Timestamp bcTs = Timestamp.valueOf(LocalDateTime.of(-500, 1, 1, 12, 34, 56, 123_456_000));

    execute("SET TIME ZONE '" + SESSION_ZONE + "'");

    Array sqlArray = null;
    try {
      sqlArray = con.createArrayOf("timestamptz", new Timestamp[]{bcTs});
      try (PreparedStatement ps = con.prepareStatement(
          "SELECT (CAST(? AS timestamptz[]))[1] = "
              + "(TIMESTAMP '0501-01-01 12:34:56.123456 BC' AT TIME ZONE '" + SESSION_ZONE
              + "')")) {
        ps.setArray(1, sqlArray);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertTrue(rs.getBoolean(1));
        }
      }
    } finally {
      if (sqlArray != null) {
        sqlArray.free();
      }
    }
  }

  @Test
  public void timeArrayKeepsSecondOnlyRendering() throws SQLException {
    Time time = new Time(Time.valueOf("12:34:56").getTime() + 4);

    List<String> values = arrayElements("time", "time[]", new Time[]{time});

    assertEquals(Arrays.asList("12:34:56"), values);
  }

  @Test
  public void scalarTimestampValueWithBcEraRendersFraction() throws SQLException {
    Timestamp bc1 = Timestamp.valueOf(LocalDateTime.of(0, 1, 1, 12, 34, 56, 500_000_000));

    assertEquals("0001-01-01 12:34:56.5 BC", scalarText("timestamp", bc1));
  }

  private void execute(String sql) throws SQLException {
    try (java.sql.Statement st = con.createStatement()) {
      st.execute(sql);
    }
  }

  private String scalarText(String type, Object value) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("SELECT CAST(? AS " + type + ")::text")) {
      ps.setObject(1, value);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        return rs.getString(1);
      }
    }
  }

  private List<String> arrayElements(String arrayType, String castType, Object[] value)
      throws SQLException {
    Array sqlArray = null;
    try {
      sqlArray = con.createArrayOf(arrayType, value);
      List<String> result = new ArrayList<>();
      try (PreparedStatement ps = con.prepareStatement(
          "SELECT unnest(CAST(? AS " + castType + "))::text")) {
        ps.setArray(1, sqlArray);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            result.add(rs.getString(1));
          }
        }
      }
      return result;
    } finally {
      if (sqlArray != null) {
        sqlArray.free();
      }
    }
  }

  private List<String> textElements(Object[] value) throws SQLException {
    Array sqlArray = null;
    try {
      sqlArray = con.createArrayOf("text", value);
      List<String> result = new ArrayList<>();
      try (PreparedStatement ps = con.prepareStatement(
          "SELECT unnest(CAST(? AS text[]))")) {
        ps.setArray(1, sqlArray);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            result.add(rs.getString(1));
          }
        }
      }
      return result;
    } finally {
      if (sqlArray != null) {
        sqlArray.free();
      }
    }
  }
}
