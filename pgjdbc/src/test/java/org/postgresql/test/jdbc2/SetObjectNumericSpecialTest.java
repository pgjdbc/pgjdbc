/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.ServerVersion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;

/**
 * {@code setObject(i, x, Types.NUMERIC)} must reach the server for {@code NaN} and {@code ±Infinity},
 * not just finite values. PostgreSQL {@code numeric} carries those, but {@link java.math.BigDecimal}
 * cannot represent them, so a coercion that always routes through {@code BigDecimal} throws before the
 * value is bound. The bind is asserted through a server-side {@code ::text} cast so the check isolates
 * the write path from the numeric read codec.
 */
@ParameterizedClass
@MethodSource("data")
public class SetObjectNumericSpecialTest extends BaseTest4 {

  public SetObjectNumericSpecialTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>(2);
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Test
  public void nan() throws SQLException {
    assertBoundNumeric(Double.NaN, "NaN");
  }

  @Test
  public void positiveInfinity() throws SQLException {
    assumeMinimumServerVersion("numeric infinity arrived in v14", ServerVersion.v14);
    assertBoundNumeric(Double.POSITIVE_INFINITY, "Infinity");
  }

  @Test
  public void negativeInfinity() throws SQLException {
    assumeMinimumServerVersion("numeric infinity arrived in v14", ServerVersion.v14);
    assertBoundNumeric(Double.NEGATIVE_INFINITY, "-Infinity");
  }

  @Test
  public void finite() throws SQLException {
    assertBoundNumeric(12.5d, "12.5");
  }

  private void assertBoundNumeric(double value, String expected) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("SELECT cast(cast(? as numeric) as text)")) {
      ps.setObject(1, value, Types.NUMERIC);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(expected, rs.getString(1));
      }
    }
  }
}
