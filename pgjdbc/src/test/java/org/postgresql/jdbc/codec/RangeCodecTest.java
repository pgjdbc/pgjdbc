/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PGRange;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

/**
 * Unit tests for {@link RangeCodec}'s text decode path, now driven by the shared
 * {@link LiteralCursor}. The synthetic range type carries {@code typelem == 0}
 * (range subtypes live in {@code pg_range}, not yet loaded), so bounds come back
 * as their raw strings — the same behaviour the previous {@code PGRange.parse}
 * path produced. Bracket inclusivity, {@code empty}, infinite bounds and quoting
 * are exercised here without a live connection.
 */
class RangeCodecTest {

  private static final PgType INT4RANGE = new PgType(
      new ObjectName("pg_catalog", "int4range"),
      "int4range",
      3904,
      'r',   // typtype = range
      'R',   // typcategory = range
      -1,    // typmod
      0,     // typelem (0 for ranges)
      0,     // arrayOid
      0);    // typbasetype

  private static PGRange<?> decode(String literal) throws SQLException {
    return (PGRange<?>) RangeCodec.INSTANCE.decodeText(literal, INT4RANGE, null);
  }

  @Test
  void inclusiveLower_exclusiveUpper() throws SQLException {
    PGRange<?> r = decode("[1,10)");
    assertEquals("1", r.getLower());
    assertEquals("10", r.getUpper());
    assertTrue(r.isLowerInclusive());
    assertFalse(r.isUpperInclusive());
    assertFalse(r.isEmpty());
  }

  @Test
  void exclusiveLower_inclusiveUpper() throws SQLException {
    PGRange<?> r = decode("(1,10]");
    assertFalse(r.isLowerInclusive());
    assertTrue(r.isUpperInclusive());
  }

  @Test
  void emptyRange() throws SQLException {
    assertTrue(decode("empty").isEmpty());
    assertTrue(decode("EMPTY").isEmpty());
  }

  @Test
  void infiniteLowerBound() throws SQLException {
    PGRange<?> r = decode("[,10)");
    assertFalse(r.hasLowerBound());
    assertNull(r.getLower());
    assertEquals("10", r.getUpper());
  }

  @Test
  void infiniteUpperBound() throws SQLException {
    PGRange<?> r = decode("[1,)");
    assertEquals("1", r.getLower());
    assertFalse(r.hasUpperBound());
    assertNull(r.getUpper());
  }

  @Test
  void bothBoundsInfinite() throws SQLException {
    PGRange<?> r = decode("(,)");
    assertFalse(r.hasLowerBound());
    assertFalse(r.hasUpperBound());
  }

  @Test
  void quotedBoundWithComma() throws SQLException {
    // A bound containing the ',' separator is quoted; the comma must stay inside it.
    PGRange<?> r = decode("[\"a,b\",\"c\")");
    assertEquals("a,b", r.getLower());
    assertEquals("c", r.getUpper());
  }

  @Test
  void quotedEmptyBoundIsEmptyStringNotInfinite() throws SQLException {
    // "" is an empty-string bound, distinct from an unquoted-empty infinite bound.
    PGRange<?> r = decode("[\"\",5)");
    assertTrue(r.hasLowerBound());
    assertEquals("", r.getLower());
    assertEquals("5", r.getUpper());
  }

  @Test
  void quotedBoundWithDoubledQuoteAndBackslash() throws SQLException {
    // range_out doubles " as "" and \ as \\; the cursor unwinds both.
    PGRange<?> r = decode("[\"a\"\"b\",\"c\\\\d\")");
    assertEquals("a\"b", r.getLower());
    assertEquals("c\\d", r.getUpper());
  }

  @Test
  void rejectsMissingBrackets() {
    assertThrows(SQLException.class, () -> decode("1,10"));
  }

  @Test
  void rejectsMissingClosingBracket() {
    assertThrows(SQLException.class, () -> decode("[1,10"));
  }

  // ---------------- slice form (zero-copy, used for ranges nested in composites) ----------------

  private static PGRange<?> decodeSlice(char[] buf, int offset, int length) throws SQLException {
    return (PGRange<?>) RangeCodec.INSTANCE.decodeText(buf, offset, length, INT4RANGE, null);
  }

  @Test
  void slicePath_parsesEmbeddedRange() throws SQLException {
    // "[1,10)" sits at offset 2, length 6 inside a larger buffer.
    PGRange<?> r = decodeSlice("##[1,10)##".toCharArray(), 2, 6);
    assertEquals("1", r.getLower());
    assertEquals("10", r.getUpper());
    assertTrue(r.isLowerInclusive());
    assertFalse(r.isUpperInclusive());
  }

  @Test
  void slicePath_empty() throws SQLException {
    assertTrue(decodeSlice("..empty..".toCharArray(), 2, 5).isEmpty());
  }

  @Test
  void slicePath_quotedBoundWithComma() throws SQLException {
    String inner = "[\"a,b\",c)";
    PGRange<?> r = decodeSlice(("xx" + inner + "yy").toCharArray(), 2, inner.length());
    assertEquals("a,b", r.getLower());
    assertEquals("c", r.getUpper());
  }
}
