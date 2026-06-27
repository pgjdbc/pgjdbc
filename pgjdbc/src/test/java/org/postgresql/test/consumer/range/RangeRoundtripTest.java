/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.range;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PGRange;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Timestamp;

/**
 * Live round-trip tests for {@link org.postgresql.jdbc.codec.RangeCodec}, whose
 * text decode now runs on the shared {@code LiteralCursor}.
 *
 * <p>The server emits the range literals and quotes any bound containing a space
 * or comma; these tests assert the cursor un-quotes them correctly. The range
 * subtype is loaded from {@code pg_range.rngsubtype} (C2), so bounds decode into
 * typed values: {@code int4range} yields {@code Integer}, {@code numrange} yields
 * {@code BigDecimal}, {@code tsrange} yields {@code Timestamp}. A range over a
 * custom {@code text} subtype keeps its bounds as {@code String}.</p>
 *
 * <p>Coverage: built-in ranges (int4range/numrange/tsrange), a user-defined
 * range type ({@code range-of-custom-type}), and arrays of both
 * ({@code array-of-range}), including the nested case where an array element is
 * a range whose bound is itself quoted.</p>
 */
@EnabledForServerVersionRange(gte = "9.2") // range types exist from 9.2
public class RangeRoundtripTest extends BaseTest4 {

  @BeforeAll
  static void createTypes() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.execute(con, "DROP TYPE IF EXISTS consumer_textrange CASCADE");
      TestUtil.execute(con,
          "CREATE TYPE consumer_textrange AS RANGE (subtype = text, collation = \"C\")");
      TestUtil.execute(con, "DROP TYPE IF EXISTS consumer_range_holder CASCADE");
      TestUtil.execute(con, "CREATE TYPE consumer_range_holder AS (id int, span int4range)");
    }
  }

  @AfterAll
  static void dropTypes() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropType(con, "consumer_range_holder");
      TestUtil.dropType(con, "consumer_textrange");
    }
  }

  private PGRange<?> selectRange(String sql) throws SQLException {
    try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      assertTrue(rs.next());
      Object value = rs.getObject(1);
      assertInstanceOf(PGRange.class, value, () -> "getObject should return a PGRange, got " + value);
      return (PGRange<?>) value;
    }
  }

  private Object[] selectRangeArray(String sql) throws SQLException {
    try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      assertTrue(rs.next());
      Array arr = rs.getArray(1);
      return (Object[]) arr.getArray();
    }
  }

  @Test
  public void int4range_inclusiveExclusive() throws SQLException {
    PGRange<?> r = selectRange("SELECT '[1,10)'::int4range");
    assertFalse(r.isEmpty());
    assertTrue(r.isLowerInclusive());
    assertFalse(r.isUpperInclusive());
    assertEquals(1, r.getLower());
    assertEquals(10, r.getUpper());
  }

  @Test
  public void numrange_continuousBounds() throws SQLException {
    PGRange<?> r = selectRange("SELECT '[1.5,2.5)'::numrange");
    assertEquals(new BigDecimal("1.5"), r.getLower());
    assertEquals(new BigDecimal("2.5"), r.getUpper());
  }

  @Test
  public void numrange_infiniteLowerBound() throws SQLException {
    PGRange<?> r = selectRange("SELECT '(,5]'::numrange");
    assertFalse(r.hasLowerBound());
    assertNull(r.getLower());
    assertTrue(r.isUpperInclusive());
    assertEquals(new BigDecimal("5"), r.getUpper());
  }

  @Test
  public void numrange_infiniteUpperBound() throws SQLException {
    PGRange<?> r = selectRange("SELECT '[10,)'::numrange");
    assertEquals(new BigDecimal("10"), r.getLower());
    assertFalse(r.hasUpperBound());
    assertNull(r.getUpper());
  }

  @Test
  public void emptyRange() throws SQLException {
    assertTrue(selectRange("SELECT 'empty'::int4range").isEmpty());
  }

  @Test
  public void tsrange_boundsAreQuotedThenUnquoted() throws SQLException {
    // Timestamps contain a space, so the server quotes each bound; the cursor must
    // strip those quotes before the timestamp subtype codec parses them. A correctly
    // parsed Timestamp value proves the un-quoting happened.
    PGRange<?> r = selectRange(
        "SELECT '[2020-01-01 00:00:00,2020-02-01 00:00:00)'::tsrange");
    assertInstanceOf(Timestamp.class, r.getLower(), () -> "lower should be a Timestamp: " + r.getLower());
    assertEquals(Timestamp.valueOf("2020-01-01 00:00:00"), r.getLower());
    assertEquals(Timestamp.valueOf("2020-02-01 00:00:00"), r.getUpper());
  }

  @Test
  public void rangeOfCustomType() throws SQLException {
    PGRange<?> r = selectRange("SELECT '[apple,orange)'::consumer_textrange");
    assertTrue(r.isLowerInclusive());
    assertFalse(r.isUpperInclusive());
    assertEquals("apple", r.getLower());
    assertEquals("orange", r.getUpper());
  }

  @Test
  public void rangeOfCustomType_quotedBoundWithComma() throws SQLException {
    // The lower bound contains the ',' separator, so the server quotes it.
    PGRange<?> r = selectRange("SELECT '[\"a,b\",c)'::consumer_textrange");
    assertEquals("a,b", r.getLower());
    assertEquals("c", r.getUpper());
  }

  @Test
  public void arrayOfRange_int4range() throws SQLException {
    Object[] elems = selectRangeArray(
        "SELECT ARRAY['[1,10)'::int4range, '[20,30)'::int4range]");
    assertEquals(2, elems.length);
    assertInstanceOf(PGRange.class, elems[0]);
    PGRange<?> r0 = (PGRange<?>) elems[0];
    assertEquals(1, r0.getLower());
    assertEquals(10, r0.getUpper());
    PGRange<?> r1 = (PGRange<?>) elems[1];
    assertEquals(20, r1.getLower());
    assertEquals(30, r1.getUpper());
  }

  @Test
  public void arrayOfCustomRange_textrange() throws SQLException {
    Object[] elems = selectRangeArray("SELECT ARRAY['[apple,orange)'::consumer_textrange]");
    assertEquals(1, elems.length);
    PGRange<?> r0 = (PGRange<?>) elems[0];
    assertEquals("apple", r0.getLower());
    assertEquals("orange", r0.getUpper());
  }

  @Test
  public void compositeWithRangeField_decodesRangeViaSlice() throws SQLException {
    // The composite codec peels the (quoted) range field and hands its borrowed
    // char slice to RangeCodec.decodeText(char[], off, len) — the slice form.
    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery(
             "SELECT ROW(1, '[1,10)'::int4range)::consumer_range_holder")) {
      assertTrue(rs.next());
      Object value = rs.getObject(1);
      assertInstanceOf(Struct.class, value);
      Object[] attrs = ((Struct) value).getAttributes();
      assertEquals(2, attrs.length);
      assertInstanceOf(PGRange.class, attrs[1]);
      PGRange<?> span = (PGRange<?>) attrs[1];
      assertEquals(1, span.getLower());
      assertEquals(10, span.getUpper());
    }
  }

  @Test
  public void arrayOfCustomRange_withQuotedBound_unwindsBothLevels() throws SQLException {
    // The array level quotes/escapes the range element, and the range level
    // quotes the comma-containing bound. Decoding must peel one level per layer:
    // array element -> [ "a,b" , c ) -> bound "a,b".
    Object[] elems = selectRangeArray("SELECT ARRAY['[\"a,b\",c)'::consumer_textrange]");
    assertEquals(1, elems.length);
    PGRange<?> r0 = (PGRange<?>) elems[0];
    assertEquals("a,b", r0.getLower());
    assertEquals("c", r0.getUpper());
  }
}
