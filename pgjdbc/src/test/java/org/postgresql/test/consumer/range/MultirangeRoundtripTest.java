/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.range;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PGRange;
import org.postgresql.util.PGmultirange;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Live round-trip tests for {@link org.postgresql.jdbc.codec.MultirangeCodec}, which composes on top
 * of {@code RangeCodec}.
 *
 * <p>Multiranges exist from PostgreSQL 14, so the class is gated on the server version. The codec
 * resolves the companion range type from {@code pg_range} and decodes each range into a typed
 * {@link PGRange}; the bounds then carry the range subtype's Java type ({@code int4multirange} →
 * {@code Integer}, {@code int8multirange} → {@code Long}, {@code nummultirange} → {@code BigDecimal})
 * and a custom {@code text} subtype keeps its bounds as {@code String}.</p>
 *
 * <p>The server normalises a multirange — sorting the ranges, merging adjacent ones, and dropping
 * empty ones — so some cases assert the normalised result rather than the literal sent.</p>
 */
@EnabledForServerVersionRange(gte = "14") // multirange types exist from 14
public class MultirangeRoundtripTest extends BaseTest4 {

  @BeforeAll
  static void createTypes() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.execute(con, "DROP TYPE IF EXISTS consumer_mr_textrange CASCADE");
      TestUtil.execute(con,
          "CREATE TYPE consumer_mr_textrange AS RANGE (subtype = text, collation = \"C\","
              + " multirange_type_name = consumer_mr_textmultirange)");
    }
  }

  @AfterAll
  static void dropTypes() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropType(con, "consumer_mr_textrange");
    }
  }

  private PGmultirange<?> selectMultirange(String sql) throws SQLException {
    try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      assertTrue(rs.next());
      Object value = rs.getObject(1);
      assertInstanceOf(PGmultirange.class, value,
          () -> "getObject should return a PGmultirange, got " + value);
      return (PGmultirange<?>) value;
    }
  }

  private Object[] selectMultirangeArray(String sql) throws SQLException {
    try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      assertTrue(rs.next());
      Array arr = rs.getArray(1);
      return (Object[]) arr.getArray();
    }
  }

  @Test
  public void int4multirange_twoRanges() throws SQLException {
    PGmultirange<?> mr = selectMultirange("SELECT '{[1,5),[10,20)}'::int4multirange");
    List<? extends PGRange<?>> ranges = mr.getRanges();
    assertEquals(2, ranges.size());
    assertEquals(1, ranges.get(0).getLower());
    assertEquals(5, ranges.get(0).getUpper());
    assertTrue(ranges.get(0).isLowerInclusive());
    assertFalse(ranges.get(0).isUpperInclusive());
    assertEquals(10, ranges.get(1).getLower());
    assertEquals(20, ranges.get(1).getUpper());
  }

  @Test
  public void int4multirange_single() throws SQLException {
    PGmultirange<?> mr = selectMultirange("SELECT '{[1,5)}'::int4multirange");
    assertEquals(1, mr.getRanges().size());
    assertInstanceOf(Integer.class, mr.getRanges().get(0).getLower());
    assertEquals(1, mr.getRanges().get(0).getLower());
  }

  @Test
  public void emptyMultirange() throws SQLException {
    PGmultirange<?> mr = selectMultirange("SELECT '{}'::int4multirange");
    assertTrue(mr.getRanges().isEmpty());
  }

  @Test
  public void int8multirange_boundsAreLong() throws SQLException {
    PGmultirange<?> mr = selectMultirange("SELECT '{[1,5)}'::int8multirange");
    assertInstanceOf(Long.class, mr.getRanges().get(0).getLower());
    assertEquals(1L, mr.getRanges().get(0).getLower());
    assertEquals(5L, mr.getRanges().get(0).getUpper());
  }

  @Test
  public void nummultirange_boundsAreBigDecimal() throws SQLException {
    PGmultirange<?> mr = selectMultirange("SELECT '{[1.5,2.5)}'::nummultirange");
    assertEquals(new BigDecimal("1.5"), mr.getRanges().get(0).getLower());
    assertEquals(new BigDecimal("2.5"), mr.getRanges().get(0).getUpper());
  }

  @Test
  public void multirange_adjacentRangesAreMerged() throws SQLException {
    // The server normalises a multirange: [1,3) and [2,5) overlap, so they merge into one range.
    PGmultirange<?> mr = selectMultirange("SELECT '{[1,3),[2,5)}'::int4multirange");
    assertEquals(1, mr.getRanges().size());
    assertEquals(1, mr.getRanges().get(0).getLower());
    assertEquals(5, mr.getRanges().get(0).getUpper());
  }

  @Test
  public void customSubtype_quotedBoundWithComma() throws SQLException {
    // The lower bound contains the ',' separator, so the server quotes it at the range level; the
    // multirange parser must hand the whole range literal to the range codec untouched.
    PGmultirange<?> mr = selectMultirange("SELECT '{[\"a,b\",c)}'::consumer_mr_textmultirange");
    assertEquals(1, mr.getRanges().size());
    assertEquals("a,b", mr.getRanges().get(0).getLower());
    assertEquals("c", mr.getRanges().get(0).getUpper());
  }

  @Test
  public void arrayOfMultirange() throws SQLException {
    Object[] elems = selectMultirangeArray(
        "SELECT ARRAY['{[1,5)}'::int4multirange, '{[10,20),[30,40)}'::int4multirange]");
    assertEquals(2, elems.length);
    PGmultirange<?> first = assertInstanceOf(PGmultirange.class, elems[0]);
    assertEquals(1, first.getRanges().size());
    assertEquals(1, first.getRanges().get(0).getLower());
    PGmultirange<?> second = assertInstanceOf(PGmultirange.class, elems[1]);
    assertEquals(2, second.getRanges().size());
    assertEquals(30, second.getRanges().get(1).getLower());
  }
}
