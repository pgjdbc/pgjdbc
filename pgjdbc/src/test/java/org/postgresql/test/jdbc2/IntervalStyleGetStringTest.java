/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * {@code getString} on a binary {@code interval} must be independent of the wire format: it has to
 * produce the same text the server would have sent, for every {@code IntervalStyle}.
 *
 * <p>Each row selects the same interval twice &mdash; once cast to {@code text} (always sent text, so
 * it is the server's own rendering under the current style) and once raw (sent binary, so the codec
 * renders it). Comparing the two columns of one row makes the oracle immune to how the input literal
 * would be parsed: both columns derive from a single stored value. Values are built with
 * {@code make_interval} so each component's sign is explicit and unambiguous. A divergence in any
 * cell fails the test; this is the differential oracle for {@code IntervalCodec}'s style-aware render.
 */
@Isolated("sets IntervalStyle on the session")
class IntervalStyleGetStringTest {
  private static final String[] STYLES = {
      "postgres", "postgres_verbose", "sql_standard", "iso_8601",
  };

  /**
   * Interval-valued expressions covering sign carry, mixed day/time and year/month signs, mixed
   * classes, fractional seconds and large hours &mdash; the cases where the four styles diverge.
   */
  private static final String[] EXPRS = {
      "make_interval()",
      "make_interval(days => 1, hours => 2, mins => 3, secs => 4)",
      "make_interval(years => 1, months => 2, days => 3, hours => 4, mins => 5, secs => 6)",
      "make_interval(months => 1)",
      "make_interval(years => 1)",
      "make_interval(years => 1, months => 2)",
      "make_interval(days => -1)",
      "make_interval(days => 1, hours => -2, mins => -3, secs => -4)",
      "make_interval(days => -1, hours => 2, mins => 3, secs => 4)",
      "make_interval(days => -1, hours => -2, mins => -3, secs => -4)",
      "make_interval(years => -1, months => -2, days => 3, hours => -4, mins => -5, secs => -6)",
      "make_interval(days => 1, secs => 0.5)",
      "make_interval(secs => 0.123456)",
      "make_interval(secs => -0.5)",
      "make_interval(secs => -0.000001)",
      // Exactly one whole second: postgres_verbose singularises the unit ("@ 1 sec", "@ 1 sec ago"),
      // where two or a fraction keep the plural.
      "make_interval(secs => 1)",
      "make_interval(secs => -1)",
      "make_interval(hours => 100000)",
      // Long.MAX_VALUE microseconds: ~2562047788 hours, past PGInterval's int hour field. getString
      // must still render it (make_interval's hours is an int, so use the literal directly).
      "interval '2562047788:00:54.775807'",
      "make_interval(years => 1, days => -1, hours => 2)",
      "make_interval(months => -1, days => 2)",
      "make_interval(months => 2)",
      "make_interval(months => -21)",
      "make_interval(months => 31)",
      "make_interval(months => 14)",
      "make_interval(hours => 2, mins => 3, secs => 4)",
      "make_interval(hours => -2, mins => -3, secs => -4)",
      "make_interval(years => 5)",
      "make_interval(months => 1, hours => 2)",
      "make_interval(months => -1, hours => -2)",
      "make_interval(days => 2, hours => -3)",
  };

  private Connection binary;

  @BeforeEach
  void setUp() throws Exception {
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER.set(props, "true");
    // Force a server-prepared statement so the raw interval column actually arrives in binary.
    PGProperty.PREPARE_THRESHOLD.set(props, "-1");
    binary = TestUtil.openDB(props);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (binary != null) {
      TestUtil.execute(binary, "RESET IntervalStyle");
      TestUtil.closeDB(binary);
    }
  }

  @Test
  void binaryGetStringMatchesServerTextForEveryStyle() throws SQLException {
    List<String> mismatches = new ArrayList<>();
    for (String style : STYLES) {
      TestUtil.execute(binary, "SET IntervalStyle = '" + style + "'");
      for (String expr : EXPRS) {
        try (PreparedStatement ps =
                 binary.prepareStatement("SELECT (" + expr + ")::text, (" + expr + ")");
             ResultSet rs = ps.executeQuery()) {
          rs.next();
          String serverText = rs.getString(1); // text wire: the server's own rendering
          String codecText = rs.getString(2); // binary wire: the codec's rendering
          if (!Objects.equals(serverText, codecText)) {
            mismatches.add(style + " / " + expr + ": server=<" + serverText
                + "> codec=<" + codecText + ">");
          }
        }
      }
    }
    if (!mismatches.isEmpty()) {
      fail("Binary interval getString diverged from the server text form in "
          + mismatches.size() + " case(s):\n  " + String.join("\n  ", mismatches));
    }
  }

  /**
   * getObject on a max-range binary interval must refuse with a checked SQLException, not leak the
   * unchecked exception the overflow once produced. The ~2562047788-hour value does not fit
   * PGInterval's int hour field, so the codec reports NUMERIC_CONSTANT_OUT_OF_RANGE (42820), matching
   * the released baseline's checked failure.
   */
  @Test
  void binaryGetObjectOnMaxRangeIntervalRefusesRatherThanLeak() throws SQLException {
    try (PreparedStatement ps =
             binary.prepareStatement("SELECT interval '2562047788:00:54.775807'");
         ResultSet rs = ps.executeQuery()) {
      rs.next();
      PSQLException ex = assertThrows(PSQLException.class, () -> rs.getObject(1));
      assertEquals(PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE.getState(), ex.getSQLState());
    }
  }
}
