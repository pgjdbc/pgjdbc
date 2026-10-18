/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Tests that an array of {@link LocalDate}, {@link LocalDateTime} or {@link OffsetDateTime}
 * elements carries the same values as scalar parameters of those types.
 *
 * <p>A scalar parameter is formatted by {@code TimestampUtils}, which writes the BC era, years
 * above 9999, the {@code infinity} sentinels and the rounding to microseconds. Array elements are
 * formatted by {@code ArrayEncoding}, and issue #4399 reports that they fall back to
 * {@link Object#toString()}: a leading {@code -} or {@code +} year is then read as a time zone, and
 * the server rejects the array with {@code time zone displacement out of range}.
 *
 * <p>The expected text always comes from the server rendering an array it builds from the same
 * values bound as scalars, and never from wrapping the scalar text in braces: PostgreSQL quotes
 * array elements that contain spaces, as BC dates and timestamps do.
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

  @Test
  public void dateElementsMatchScalarParameters() throws SQLException {
    LocalDate ordinary = LocalDate.of(2024, 11, 11);
    LocalDate bc = LocalDate.of(-500, 1, 1); // 0501-01-01 BC
    LocalDate year10000 = LocalDate.of(10000, 1, 1); // five digit year

    assertSameValuesAsScalars("date", new LocalDate[]{ordinary, bc, year10000});
    assertSameValuesAsScalars("date", new Object[]{ordinary, bc, year10000});
  }

  @Test
  public void timestampElementsMatchScalarParameters() throws SQLException {
    LocalDateTime ordinary = LocalDateTime.of(2024, 11, 11, 1, 2, 3, 4000);
    LocalDateTime bc = LocalDateTime.of(-500, 1, 1, 0, 0); // 0501-01-01 00:00:00 BC
    LocalDateTime year10000 = LocalDateTime.of(10000, 1, 1, 2, 3, 4); // five digit year
    LocalDateTime halfMicrosecond = LocalDateTime.of(2024, 11, 11, 1, 2, 3, 500);
    LocalDateTime roundsIntoNextDay = LocalDateTime.of(2024, 11, 11, 23, 59, 59, 999_999_500);

    assertSameValuesAsScalars("timestamp",
        new LocalDateTime[]{ordinary, bc, year10000, halfMicrosecond, roundsIntoNextDay});
    assertSameValuesAsScalars("timestamp", new Object[]{bc, year10000, halfMicrosecond});
  }

  @Test
  public void timestamptzElementsMatchScalarParameters() throws SQLException {
    OffsetDateTime ordinary =
        OffsetDateTime.of(2024, 11, 11, 1, 2, 3, 4000, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime bc = OffsetDateTime.of(-500, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(2));
    OffsetDateTime year10000 = OffsetDateTime.of(10000, 1, 1, 2, 3, 4, 0, ZoneOffset.ofHours(-8));

    assertSameValuesAsScalars("timestamptz", new OffsetDateTime[]{ordinary, bc, year10000});
    assertSameValuesAsScalars("timestamptz", new Object[]{bc, year10000});
  }

  /**
   * Values at the ends of the supported range are sent as the {@code infinity} sentinels by the
   * scalar path. Which range the driver turns into a sentinel is not pinned here; the array path
   * only has to carry whatever the scalar path carries.
   */
  @Test
  public void valuesOutsideTheSupportedRangeMatchScalarParameters() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4),
        "'infinity' for 'date' requires PostgreSQL 8.4+");

    assertSameValuesAsScalars("date", new LocalDate[]{LocalDate.MIN, LocalDate.MAX});
    assertSameValuesAsScalars("timestamp",
        new LocalDateTime[]{LocalDateTime.MIN, LocalDateTime.MAX});
    assertSameValuesAsScalars("timestamptz",
        new OffsetDateTime[]{OffsetDateTime.MIN, OffsetDateTime.MAX});
  }

  @Test
  public void nullElementsMatchScalarParameters() throws SQLException {
    LocalDate ordinary = LocalDate.of(2024, 11, 11);
    LocalDate bc = LocalDate.of(-500, 1, 1);
    OffsetDateTime bcTimestamp = OffsetDateTime.of(-500, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    // The nulls sit between elements that are rendered differently from each other, so a lost
    // null shows up as a shifted element and a null that swallows an element shows up as a
    // length mismatch.
    assertSameValuesAsScalars("date", new LocalDate[]{ordinary, null, bc});
    assertSameValuesAsScalars("date", new Object[]{null, bc, null});
    assertSameValuesAsScalars("timestamptz", new OffsetDateTime[]{null, bcTimestamp, null});
  }

  @Test
  public void nestedArrayElementsMatchScalarParameters() throws SQLException {
    LocalDate[][] values = {
        {LocalDate.of(2024, 11, 11), null},
        {LocalDate.of(-500, 1, 1), LocalDate.of(10000, 1, 1)},
    };

    assertSameValuesAsScalars("date", values);
    assertSameValuesAsScalars("date", new LocalDate[][][]{values, values});
  }

  /**
   * Elements of other types are a control: they keep the rendering they had before, so a fix for
   * the {@code java.time} elements cannot change how quotes, backslashes and array-like text in
   * other elements are escaped.
   */
  @Test
  public void otherElementsKeepTheirEscaping() throws SQLException {
    Object[] values = {
        "a\"b", "c\\d", "{\"2024-11-11\"}", "0501-01-01 BC", new BigDecimal("1.5"), null};

    assertSameValuesAsScalars("text", values);
  }

  /**
   * Asserts that {@code createArrayOf(pgType, values)} carries the same values as binding every
   * element as a {@code pgType} scalar with {@code setObject}.
   */
  private void assertSameValuesAsScalars(String pgType, Object[] values) throws SQLException {
    assertEquals(scalarBuiltArray(pgType, values), arrayText(pgType, values),
        "createArrayOf(\"" + pgType + "\", " + Arrays.deepToString(values)
            + ") should carry the same values as setObject does");
  }

  /** Renders the values the way the driver sends them through {@code createArrayOf}. */
  private String arrayText(String pgType, Object[] values) throws SQLException {
    Array array = con.createArrayOf(pgType, values);
    try (PreparedStatement ps = con.prepareStatement("SELECT CAST(? AS " + pgType + "[])::text")) {
      ps.setArray(1, array);
      return singleString(ps);
    } finally {
      array.free();
    }
  }

  /** Renders the values as an array the server builds and quotes from scalar parameters. */
  private String scalarBuiltArray(String pgType, Object[] values) throws SQLException {
    List<Object> elements = new ArrayList<>();
    String literal = arrayLiteral(pgType, values, elements);
    try (PreparedStatement ps = con.prepareStatement("SELECT " + literal + "::text")) {
      int index = 1;
      for (Object element : elements) {
        if (element == null) {
          ps.setNull(index++, Types.OTHER);
        } else {
          ps.setObject(index++, element);
        }
      }
      return singleString(ps);
    }
  }

  private static String arrayLiteral(String pgType, Object[] values, List<Object> elements) {
    StringBuilder literal = new StringBuilder("ARRAY[");
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        literal.append(',');
      }
      if (values[i] instanceof Object[]) {
        literal.append(arrayLiteral(pgType, (Object[]) values[i], elements));
      } else {
        elements.add(values[i]);
        literal.append("CAST(? AS ").append(pgType).append(')');
      }
    }
    return literal.append(']').toString();
  }

  private static String singleString(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next(), "query should return one row");
      return rs.getString(1);
    }
  }
}
