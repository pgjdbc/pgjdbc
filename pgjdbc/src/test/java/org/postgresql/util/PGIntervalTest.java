/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class PGIntervalTest {

  // ==================== valid literals still parse (F4 must not break) ====================

  @Test
  void parsesPostgresOutputFormat() throws SQLException {
    PGInterval interval = new PGInterval("1 year 2 mons 3 days 04:05:06");
    assertEquals(1, interval.getYears());
    assertEquals(2, interval.getMonths());
    assertEquals(3, interval.getDays());
    assertEquals(4, interval.getHours());
    assertEquals(5, interval.getMinutes());
    assertEquals(6.0, interval.getSeconds(), 0.0001);
  }

  @Test
  void parsesIso8601Format() throws SQLException {
    PGInterval interval = new PGInterval("P1Y2M3DT4H5M6S");
    assertEquals(1, interval.getYears());
    assertEquals(2, interval.getMonths());
    assertEquals(3, interval.getDays());
    assertEquals(4, interval.getHours());
    assertEquals(5, interval.getMinutes());
    assertEquals(6.0, interval.getSeconds(), 0.0001);
  }

  @Test
  void parsesNegativeTime() throws SQLException {
    PGInterval interval = new PGInterval("-01:02:03");
    assertEquals(-1, interval.getHours());
    assertEquals(-2, interval.getMinutes());
    assertEquals(-3.0, interval.getSeconds(), 0.0001);
  }

  @Test
  void parsesVerboseFormatAgo() throws SQLException {
    PGInterval interval = new PGInterval("@ 1 year ago");
    assertEquals(-1, interval.getYears());
  }

  // ==================== malformed literals refuse cleanly (F4) ====================

  // A malformed interval literal parses numbers with Integer.parseInt / Double.parseDouble and slices
  // tokens with substring, so it can leak a NumberFormatException or a StringIndexOutOfBoundsException.
  // Both paths must instead refuse with a clean PSQLException carrying the server's state for a bad
  // interval literal (invalid_datetime_format, 22007).

  @Test
  void rejectsNonNumericField() {
    // "bad" tokenises to a value token with no unit; a numeric field such as "xx years" leaks NFE.
    assertRejected("xx years");
  }

  @Test
  void rejectsNonNumericIso8601Field() {
    // The ISO-8601 branch (value starts with 'P') is parsed outside the legacy try/catch before the
    // fix, so a non-numeric field there leaked NumberFormatException.
    assertRejected("P1ZY");
  }

  @Test
  void rejectsNonNumericIso8601TimeField() {
    assertRejected("PT1ZH");
  }

  @Test
  void rejectsMalformedTimeToken() {
    // A truncated time token drives token.substring(endHours + 1, endHours + 3) out of range, a
    // StringIndexOutOfBoundsException before the fix.
    assertRejected("1 day 1:x");
  }

  private static void assertRejected(String literal) {
    PSQLException e = assertThrows(PSQLException.class, () -> new PGInterval(literal),
        () -> "interval literal '" + literal + "' should be rejected");
    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), e.getSQLState(),
        () -> "SQLState for rejected interval literal '" + literal + "'");
  }
}
