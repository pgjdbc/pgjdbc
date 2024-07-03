/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.postgresql.util.PGtstzrange.OFFSET_DATE_TIME_FORMATTER;

import org.postgresql.PGConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PGdaterange;
import org.postgresql.util.PGint4range;
import org.postgresql.util.PGint8range;
import org.postgresql.util.PGnumrange;
import org.postgresql.util.PGtsrange;
import org.postgresql.util.PGtstzrange;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class RangeTypeTest extends BaseTest4 {

  private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

  public RangeTypeTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();

    assumeMinimumServerVersion("range types requires PostgreSQL 9.2+", ServerVersion.v9_2);
    TestUtil.createTable(con, "table1", "int4range_column int4range, int8range_column int8range, numrange_column numrange, tsrange_column tsrange, tstzrange_column tstzrange, daterange_column daterange");
  }

  @Override
  public void tearDown() throws SQLException {

    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2)) {
      TestUtil.dropTable(con, "table1");
    }
    super.tearDown();
  }

  @Test
  public void int4RangeEquals() throws SQLException {
    PGint4range range = new PGint4range(1, 3);
    assertEquals(new PGint4range(null, null), new PGint4range(null, null));
    assertEquals(range, range);
    assertNotEquals(range, null);
    assertNotEquals(range, new PGint8range(1L, 3L));
    assertEquals(range, new PGint4range(1, 3));
    assertNotEquals(range, new PGint4range(1, null));
    assertNotEquals(range, new PGint4range(null, 3));
    assertEquals(range, new PGint4range("[1,3)"));
    assertEquals(range, new PGint4range(1, true, 3, false));
    assertNotEquals(range, new PGint4range(1, true, 3, true));
    assertNotEquals(range, new PGint4range(1, false, 3, true));
    assertNotEquals(range, new PGint4range(1, false, 3, false));
    assertNotEquals(range, new PGint4range(0, false, 2, true));
    assertEquals(range.hashCode(), new PGint4range(1, 3).hashCode());
  }

  @Test
  public void int8RangeEquals() throws SQLException {
    PGint8range range = new PGint8range(1L, 3L);
    assertEquals(range, range);
    assertEquals(new PGint8range(null, null), new PGint8range(null, null));
    assertNotEquals(range, null);
    assertNotEquals(range, new PGint4range(1, 3));
    assertEquals(range, new PGint8range(1L, 3L));
    assertNotEquals(range, new PGint8range(1L, null));
    assertNotEquals(range, new PGint8range(null, 3L));
    assertEquals(range, new PGint8range("[1,3)"));
    assertEquals(range, new PGint8range(1L, true, 3L, false));
    assertNotEquals(range, new PGint8range(1L, true, 3L, true));
    assertNotEquals(range, new PGint8range(1L, false, 3L, true));
    assertNotEquals(range, new PGint8range(1L, false, 3L, false));
    assertNotEquals(range, new PGint8range(0L, false, 2L, true));
    assertEquals(range.hashCode(), new PGint8range(1L, 3L).hashCode());
  }

  @Test
  public void numRangeEquals() throws SQLException {
    PGnumrange range = new PGnumrange(BigDecimal.valueOf(1), BigDecimal.valueOf(3));
    assertEquals(range, range);
    assertEquals(new PGnumrange(null, null), new PGnumrange(null, null));
    assertNotEquals(range, null);
    assertNotEquals(range, new PGint4range(1, 3));
    assertEquals(range, new PGnumrange(BigDecimal.valueOf(1), BigDecimal.valueOf(3)));
    assertNotEquals(range, new PGnumrange(BigDecimal.valueOf(1), null));
    assertNotEquals(range, new PGnumrange(null, BigDecimal.valueOf(3)));
    assertEquals(range, new PGnumrange("[1,3)"));
    assertEquals(range, new PGnumrange("[1.0,3.0)"));
    assertEquals(range, new PGnumrange(BigDecimal.valueOf(1), true, BigDecimal.valueOf(3), false));
    assertNotEquals(range, new PGnumrange(BigDecimal.valueOf(1), true, BigDecimal.valueOf(3),
        true));
    assertNotEquals(range, new PGnumrange(BigDecimal.valueOf(1), false, BigDecimal.valueOf(3),
        true));
    assertNotEquals(range, new PGnumrange(BigDecimal.valueOf(1), false, BigDecimal.valueOf(3),
        false));
    assertNotEquals(range, new PGnumrange(BigDecimal.valueOf(0), false, BigDecimal.valueOf(2),
        true));
    assertEquals(range.hashCode(),
        new PGnumrange(BigDecimal.valueOf(1), BigDecimal.valueOf(3)).hashCode());
  }

  @Test
  public void dateRangeEquals() throws SQLException {
    LocalDate start = LocalDate.of(2020, 12, 31);
    LocalDate end = LocalDate.of(2021, 6, 30);
    PGdaterange range = new PGdaterange(start, end);
    assertEquals(new PGdaterange(null, null), new PGdaterange(null, null));
    assertEquals(range, range);
    assertNotEquals(range, null);
    assertEquals(range, new PGdaterange(LocalDate.of(2020, 12, 31), LocalDate.of(2021, 6, 30)));
    assertNotEquals(range, new PGdaterange(start, null));
    assertNotEquals(range, new PGdaterange(null, end));
    assertEquals(range, new PGdaterange("[2020-12-31,2021-06-30)"));
    assertEquals(range, new PGdaterange(start, true, end, false));
    assertNotEquals(range, new PGdaterange(start, true, end, true));
    assertNotEquals(range, new PGdaterange(start, false, end, true));
    assertNotEquals(range, new PGdaterange(start, false, end, false));
    assertNotEquals(range, new PGdaterange(start.minusDays(1), false, end.minusDays(1), true));
    assertEquals(range.hashCode(), new PGdaterange(start, end).hashCode());
  }

  @Test
  public void tsRangeEquals() throws SQLException {
    LocalDateTime start = LocalDate.of(2020, 12, 31).atStartOfDay();
    LocalDateTime end = LocalDate.of(2021, 6, 30).atStartOfDay();
    PGtsrange range = new PGtsrange(start, end);
    assertEquals(new PGtsrange(null, null), new PGtsrange(null, null));
    assertEquals(range, range);
    assertNotEquals(range, null);
    assertEquals(range, new PGtsrange(LocalDate.of(2020, 12, 31).atStartOfDay(),
        LocalDate.of(2021, 6, 30).atStartOfDay()));
    assertNotEquals(range, new PGtsrange(start, null));
    assertNotEquals(range, new PGtsrange(null, end));
    assertEquals(range, new PGtsrange("[\"2020-12-31 00:00:00\",\"2021-06-30 00:00:00\")"));
    assertEquals(range, new PGtsrange(start, true, end, false));
    assertNotEquals(range, new PGtsrange(start, true, end, true));
    assertNotEquals(range, new PGtsrange(start, false, end, true));
    assertNotEquals(range, new PGtsrange(start, false, end, false));
    assertNotEquals(range, new PGtsrange(start.minusDays(1), false, end.minusDays(1), true));
    assertEquals(range.hashCode(), new PGtsrange(start, end).hashCode());
  }

  @Test
  public void tstzRangeEquals() throws SQLException {
    ZoneOffset offset = ZoneOffset.UTC;
    OffsetDateTime start = LocalDate.of(2020, 12, 31).atStartOfDay().atOffset(offset);
    OffsetDateTime end = LocalDate.of(2021, 6, 30).atStartOfDay().atOffset(offset);
    PGtstzrange range = new PGtstzrange(start, end);
    assertEquals(new PGtstzrange(null, null), new PGtstzrange(null, null));
    assertEquals(range, range);
    assertNotEquals(range, null);
    assertEquals(range,
        new PGtstzrange(LocalDate.of(2020, 12, 31).atStartOfDay().atOffset(offset),
            LocalDate.of(2021, 6, 30).atStartOfDay().atOffset(offset)));
    assertNotEquals(range, new PGtstzrange(start, null));
    assertNotEquals(range, new PGtstzrange(null, end));
    assertEquals(range, new PGtstzrange("[\"2020-12-31 00:00:00+00\",\"2021-06-30 00:00:00+00\")"));
    assertEquals(range, new PGtstzrange(start, true, end, false));
    assertNotEquals(range, new PGtstzrange(start, true, end, true));
    assertNotEquals(range, new PGtstzrange(start, false, end, true));
    assertNotEquals(range, new PGtstzrange(start, false, end, false));
    assertNotEquals(range, new PGtstzrange(start.minusDays(1), false, end.minusDays(1), true));
    assertEquals(range.hashCode(), new PGtstzrange(start, end).hashCode());
  }

  @Test
  public void selectInt4Range() throws SQLException {
    try (Statement stmt = con.createStatement()) {

      stmt.executeUpdate(TestUtil.insertSQL("table1", "int4range_column", "'(,)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "int4range_column", "'[-2147483648,2147483647)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "int4range_column", "'(,3)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "int4range_column", "'[4,20)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "int4range_column", "'[50,)'"));

      try (ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "int4range_column", null, "int4range_column"))) {

        assertTrue(rs.next());
        PGint4range range = rs.getObject("int4range_column", PGint4range.class);
        // (,)
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("int4range_column", PGint4range.class);
        // [-2147483648,2147483647)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), range.getLowerBound());
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("int4range_column", PGint4range.class);
        // (,3)
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertEquals(Integer.valueOf(3), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("int4range_column", PGint4range.class);
        // [4,20)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(Integer.valueOf(4), range.getLowerBound());
        assertEquals(Integer.valueOf(20), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("int4range_column", PGint4range.class);
        // [50,)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(Integer.valueOf(50), range.getLowerBound());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertFalse(rs.next());
      }
    }

  }

  @Test
  public void insertInt4Range() throws SQLException {
    try (PreparedStatement insert =
             con.prepareStatement("INSERT INTO table1 (int4range_column) VALUES (?)")) {
      assertInt4RangeInsert(insert, new PGint4range("[1,1)"), "empty");
      assertInt4RangeInsert(insert, new PGint4range("(,)"), "(,)");

      assertInt4RangeInsert(insert, new PGint4range("[1,)"), "[1,)");
      assertInt4RangeInsert(insert, new PGint4range("(0,]"), "[1,)");

      assertInt4RangeInsert(insert, new PGint4range("[,3)"), "(,3)");
      assertInt4RangeInsert(insert, new PGint4range("[,2]"), "(,3)");

      assertInt4RangeInsert(insert, new PGint4range("[1,2)"), "[1,2)");
      assertInt4RangeInsert(insert, new PGint4range("[1,3)"), "[1,3)");
      assertInt4RangeInsert(insert, new PGint4range("(0,2]"), "[1,3)");
      assertInt4RangeInsert(insert, new PGint4range(1, 3), "[1,3)");
      assertInt4RangeInsert(insert, new PGint4range(0, false, 2, true), "[1,3)");
    }
  }

  private void assertInt4RangeInsert(PreparedStatement insert, PGint4range int4range,
      String expected) throws SQLException {
    if (((PGConnection) con).getPreferQueryMode() == PreferQueryMode.SIMPLE) {
      try (Statement stmt = con.createStatement()) {
        // simple mode supports no bind parameter
        stmt.executeUpdate("INSERT INTO table1 (int4range_column) VALUES ('" + int4range + "')");
      }
    } else {
      insert.setObject(1, int4range);
      insert.executeUpdate();
    }

    try (Statement stmt = con.createStatement(); ResultSet rs =
        stmt.executeQuery(TestUtil.selectSQL("table1", "int4range_column"))) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject(1, PGint4range.class).getValue());
      assertFalse(rs.next());
      stmt.executeUpdate("DELETE FROM table1");
    }
  }

  @Test
  public void selectInt8Range() throws SQLException {
    try (Statement stmt = con.createStatement()) {

      stmt.executeUpdate(TestUtil.insertSQL("table1", "int8range_column", "'(,)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "int8range_column",
          "'[-9223372036854775808,9223372036854775807)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "int8range_column", "'(,3)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "int8range_column", "'[4,20)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "int8range_column", "'[50,)'"));

      try (ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "int8range_column", null, "int8range_column"))) {

        assertTrue(rs.next());
        PGint8range range = rs.getObject("int8range_column", PGint8range.class);
        // (,)
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("int8range_column", PGint8range.class);
        // [-9223372036854775808,9223372036854775807)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(Long.valueOf(Long.MIN_VALUE), range.getLowerBound());
        assertEquals(Long.valueOf(Long.MAX_VALUE), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("int8range_column", PGint8range.class);
        // (,3)
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertEquals(Long.valueOf(3), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("int8range_column", PGint8range.class);
        // [4,20)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(Long.valueOf(4), range.getLowerBound());
        assertEquals(Long.valueOf(20), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("int8range_column", PGint8range.class);
        // [50,)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(Long.valueOf(50), range.getLowerBound());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertFalse(rs.next());

        assertFalse(rs.next());
      }
    }

  }

  @Test
  public void insertInt8Range() throws SQLException {
    try (PreparedStatement insert =
             con.prepareStatement("INSERT INTO table1 (int8range_column) VALUES (?)")) {
      assertInt8RangeInsert(insert, new PGint8range("[1,1)"), "empty");
      assertInt8RangeInsert(insert, new PGint8range("(,)"), "(,)");

      assertInt8RangeInsert(insert, new PGint8range("[1,)"), "[1,)");
      assertInt8RangeInsert(insert, new PGint8range("(0,]"), "[1,)");

      assertInt8RangeInsert(insert, new PGint8range("[,3)"), "(,3)");
      assertInt8RangeInsert(insert, new PGint8range("[,2]"), "(,3)");

      assertInt8RangeInsert(insert, new PGint8range("[1,2)"), "[1,2)");
      assertInt8RangeInsert(insert, new PGint8range("[1,3)"), "[1,3)");
      assertInt8RangeInsert(insert, new PGint8range("(0,2]"), "[1,3)");
      assertInt8RangeInsert(insert, new PGint8range(1L, 3L), "[1,3)");
      assertInt8RangeInsert(insert, new PGint8range(0L, false, 2L, true), "[1,3)");
    }
  }

  private void assertInt8RangeInsert(PreparedStatement insert, PGint8range int8range,
      String expected) throws SQLException {
    if (((PGConnection) con).getPreferQueryMode() == PreferQueryMode.SIMPLE) {
      try (Statement stmt = con.createStatement()) {
        // simple mode supports no bind parameter
        stmt.executeUpdate("INSERT INTO table1 (int8range_column) VALUES ('" + int8range + "')");
      }
    } else {
      insert.setObject(1, int8range);
      insert.executeUpdate();
    }

    try (Statement stmt = con.createStatement(); ResultSet rs =
        stmt.executeQuery(TestUtil.selectSQL("table1", "int8range_column"))) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject(1, PGint8range.class).getValue());
      assertFalse(rs.next());
      stmt.executeUpdate("DELETE FROM table1");
    }
  }

  @Test
  public void selecNumRange() throws SQLException {
    try (Statement stmt = con.createStatement()) {

      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column", "'(,)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column",
          "'[-9223372036854775808.8,9223372036854775807.7)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column", "'(,3.3)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column", "'[4.4,20.20)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column", "'(4.4,20.20]'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column", "'[50.50,)'"));

      try (ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "numrange_column", null,
          "numrange_column"))) {

        assertTrue(rs.next());
        assertEquals("(,)", rs.getString("numrange_column"));
        PGnumrange range = rs.getObject("numrange_column", PGnumrange.class);
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertTrue(rs.next());
        assertEquals("[-9223372036854775808.8,9223372036854775807.7)", rs.getString(
            "numrange_column"));
        range = rs.getObject("numrange_column", PGnumrange.class);
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertNotNull(range.getLowerBound());
        assertNotNull(range.getUpperBound());
        assertEquals(0, range.getLowerBound().compareTo(new BigDecimal("-9223372036854775808.8")));
        assertEquals(0, range.getUpperBound().compareTo(new BigDecimal("9223372036854775807.7")));
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        assertEquals("(,3.3)", rs.getString("numrange_column"));
        range = rs.getObject("numrange_column", PGnumrange.class);
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertNotNull(range.getUpperBound());
        assertEquals(0, range.getUpperBound().compareTo(new BigDecimal("3.3")));
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        assertEquals("[4.4,20.20)", rs.getString("numrange_column"));
        range = rs.getObject("numrange_column", PGnumrange.class);
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertNotNull(range.getLowerBound());
        assertNotNull(range.getUpperBound());
        assertEquals(0, range.getLowerBound().compareTo(new BigDecimal("4.4")));
        assertEquals(0, range.getUpperBound().compareTo(new BigDecimal("20.20")));
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        assertEquals("(4.4,20.20]", rs.getString("numrange_column"));
        range = rs.getObject("numrange_column", PGnumrange.class);
        assertFalse(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertNotNull(range.getLowerBound());
        assertNotNull(range.getUpperBound());
        assertEquals(0, range.getLowerBound().compareTo(new BigDecimal("4.4")));
        assertEquals(0, range.getUpperBound().compareTo(new BigDecimal("20.20")));
        assertTrue(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        assertEquals("[50.50,)", rs.getString("numrange_column"));
        range = rs.getObject("numrange_column", PGnumrange.class);
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertNotNull(range.getLowerBound());
        assertEquals(0, range.getLowerBound().compareTo(new BigDecimal("50.50")));
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertFalse(rs.next());

        assertFalse(rs.next());
      }
    }

  }

  @Test
  public void insertNumRange() throws SQLException {
    try (PreparedStatement insert =
             con.prepareStatement("INSERT INTO table1 (numrange_column) VALUES (?)")) {
      assertNumRangeInsert(insert, new PGnumrange("[1.1,1.1)"), "empty");
      assertNumRangeInsert(insert, new PGnumrange("(,)"), "(,)");

      assertNumRangeInsert(insert, new PGnumrange("[1.1,)"), "[1.1,)");
      assertNumRangeInsert(insert, new PGnumrange("(1.1,]"), "(1.1,)");

      assertNumRangeInsert(insert, new PGnumrange("[,3.3)"), "(,3.3)");
      assertNumRangeInsert(insert, new PGnumrange("[,2.2]"), "(,2.2]");

      assertNumRangeInsert(insert, new PGnumrange("[1.1,2.2)"), "[1.1,2.2)");
      assertNumRangeInsert(insert, new PGnumrange("[1.1,3.3)"), "[1.1,3.3)");
      assertNumRangeInsert(insert, new PGnumrange("(1.1,2.2]"), "(1.1,2.2]");
      assertNumRangeInsert(insert, new PGnumrange(new BigDecimal("1.1"), new BigDecimal("3.3")), "[1.1,3.3)");
      assertNumRangeInsert(insert, new PGnumrange(new BigDecimal("1.1"), false, new BigDecimal("3.3"), true), "(1.1,3.3]");
    }
  }

  private void assertNumRangeInsert(PreparedStatement insert, PGnumrange numrange,
      String expected) throws SQLException {
    insert.setObject(1, numrange);
    insert.executeUpdate();

    try (Statement stmt = con.createStatement(); ResultSet rs =
        stmt.executeQuery(TestUtil.selectSQL("table1", "numrange_column"))) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject(1, PGnumrange.class).getValue());
      assertFalse(rs.next());
      stmt.executeUpdate("DELETE FROM table1");
    }
  }

  @Test
  public void selectDateRange() throws SQLException {
    try (Statement stmt = con.createStatement()) {

      stmt.executeUpdate(TestUtil.insertSQL("table1", "daterange_column", "'(,)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "daterange_column", "'[1970-01-01,9999-12-31)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "daterange_column", "'(,2020-12-21)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "daterange_column", "'[1337-04-20,2069-04-20)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "daterange_column", "'[2001-09-12,)'"));

      try (ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "daterange_column", null, "daterange_column"))) {

        assertTrue(rs.next());
        PGdaterange range = rs.getObject("daterange_column", PGdaterange.class);
        // (,)
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("daterange_column", PGdaterange.class);
        // [1970-01-01,9999-12-31)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(LocalDate.ofEpochDay(0), range.getLowerBound());
        assertEquals(LocalDate.of(9999, 12, 31), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("daterange_column", PGdaterange.class);
        // (,2020-12-21)
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertEquals(LocalDate.of(2020, 12, 21), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("daterange_column", PGdaterange.class);
        // [1337-04-20,2069-04-20)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(LocalDate.of(1337, 4, 20), range.getLowerBound());
        assertEquals(LocalDate.of(2069, 4, 20), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("daterange_column", PGdaterange.class);
        // [2001-09-12,)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(LocalDate.of(2001, 9, 12), range.getLowerBound());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertFalse(rs.next());
      }
    }

  }

  @Test
  public void insertDateRange() throws SQLException {
    try (PreparedStatement insert =
             con.prepareStatement("INSERT INTO table1 (daterange_column) VALUES (?)")) {
      assertDateRangeInsert(insert, new PGdaterange("[1970-01-01,1970-01-01)"), "empty");
      assertDateRangeInsert(insert, new PGdaterange("(,)"), "(,)");

      assertDateRangeInsert(insert, new PGdaterange("[1970-01-01,)"), "[1970-01-01,)");
      assertDateRangeInsert(insert, new PGdaterange("(1970-01-01,]"), "[1970-01-02,)");

      assertDateRangeInsert(insert, new PGdaterange("[,1970-01-01)"), "(,1970-01-01)");
      assertDateRangeInsert(insert, new PGdaterange("[,1970-01-01]"), "(,1970-01-02)");

      assertDateRangeInsert(insert, new PGdaterange("[1970-01-01,1970-01-02)"), "[1970-01-01,1970-01-02)");
      assertDateRangeInsert(insert, new PGdaterange("[1970-01-01,1970-01-03)"), "[1970-01-01,1970-01-03)");
      assertDateRangeInsert(insert, new PGdaterange("(1970-01-01,1970-01-02]"), "[1970-01-02,1970-01-03)");
      assertDateRangeInsert(insert, new PGdaterange(LocalDate.ofEpochDay(0), LocalDate.ofEpochDay(2)), "[1970-01-01,1970-01-03)");
      assertDateRangeInsert(insert, new PGdaterange(LocalDate.ofEpochDay(0), false, LocalDate.ofEpochDay(2), true), "[1970-01-02,1970-01-04)");
    }
  }

  private void assertDateRangeInsert(PreparedStatement insert, PGdaterange daterange,
      String expected) throws SQLException {
    if (((PGConnection) con).getPreferQueryMode() == PreferQueryMode.SIMPLE) {
      try (Statement stmt = con.createStatement()) {
        // simple mode supports no bind parameter
        stmt.executeUpdate("INSERT INTO table1 (daterange_column) VALUES ('" + daterange + "'::daterange)");
      }
    } else {
      insert.setObject(1, daterange);
      insert.executeUpdate();
    }

    try (Statement stmt = con.createStatement(); ResultSet rs =
        stmt.executeQuery(TestUtil.selectSQL("table1", "daterange_column"))) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject(1, PGdaterange.class).getValue());
      assertFalse(rs.next());
      stmt.executeUpdate("DELETE FROM table1");
    }
  }

  @Test
  public void selectTsRange() throws SQLException {
    try (Statement stmt = con.createStatement()) {

      stmt.executeUpdate(TestUtil.insertSQL("table1", "tsrange_column", "'(,)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "tsrange_column", "'[\"1970-01-01 00:00:00\",\"9999-12-31 00:00:00\")'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "tsrange_column", "'(,\"2020-12-21 00:00:00\")'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "tsrange_column", "'[\"1337-04-20 00:00:00\",\"2069-04-20 00:00:00\")'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "tsrange_column", "'[\"2001-09-12 00:00:00\",)'"));

      try (ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "tsrange_column", null, "tsrange_column"))) {

        assertTrue(rs.next());
        PGtsrange range = rs.getObject("tsrange_column", PGtsrange.class);
        // (,)
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("tsrange_column", PGtsrange.class);
        // [1970-01-01,9999-12-31)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(LocalDate.ofEpochDay(0).atStartOfDay(), range.getLowerBound());
        assertEquals(LocalDate.of(9999, 12, 31).atStartOfDay(), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("tsrange_column", PGtsrange.class);
        // (,2020-12-21)
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertEquals(LocalDate.of(2020, 12, 21).atStartOfDay(), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("tsrange_column", PGtsrange.class);
        // [1337-04-20,2069-04-20)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(LocalDate.of(1337, 4, 20).atStartOfDay(), range.getLowerBound());
        assertEquals(LocalDate.of(2069, 4, 20).atStartOfDay(), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("tsrange_column", PGtsrange.class);
        // [2001-09-12,)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(LocalDate.of(2001, 9, 12).atStartOfDay(), range.getLowerBound());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertFalse(rs.next());
      }
    }

  }

  @Test
  public void insertTsRange() throws SQLException {
    try (PreparedStatement insert =
             con.prepareStatement("INSERT INTO table1 (tsrange_column) VALUES (?)")) {
      assertTsRangeInsert(insert, new PGtsrange("[\"1970-01-01 00:00:00\",\"1970-01-01 00:00:00\")"), "empty");
      assertTsRangeInsert(insert, new PGtsrange("(,)"), "(,)");

      assertTsRangeInsert(insert, new PGtsrange("[\"1970-01-01 00:00:00\",)"), "[\"1970-01-01 00:00:00\",)");
      assertTsRangeInsert(insert, new PGtsrange("(\"1970-01-01 00:00:00\",]"), "(\"1970-01-01 00:00:00\",)");

      assertTsRangeInsert(insert, new PGtsrange("[,\"1970-01-01 00:00:00\")"), "(,\"1970-01-01 00:00:00\")");
      assertTsRangeInsert(insert, new PGtsrange("[,\"1970-01-01 00:00:00\"]"), "(,\"1970-01-01 00:00:00\"]");

      assertTsRangeInsert(insert, new PGtsrange("[\"1970-01-01 00:00:00\",\"1970-01-02 00:00:00\")"), "[\"1970-01-01 00:00:00\",\"1970-01-02 00:00:00\")");
      assertTsRangeInsert(insert, new PGtsrange("[\"1970-01-01 00:00:00\",\"1970-01-03 00:00:00\")"), "[\"1970-01-01 00:00:00\",\"1970-01-03 00:00:00\")");
      assertTsRangeInsert(insert, new PGtsrange("(\"1970-01-01 00:00:00\",\"1970-01-02 00:00:00\"]"), "(\"1970-01-01 00:00:00\",\"1970-01-02 00:00:00\"]");
      assertTsRangeInsert(insert, new PGtsrange(LocalDate.ofEpochDay(0).atStartOfDay(), LocalDate.ofEpochDay(2).atStartOfDay()), "[\"1970-01-01 00:00:00\",\"1970-01-03 00:00:00\")");
      assertTsRangeInsert(insert, new PGtsrange(LocalDate.ofEpochDay(0).atStartOfDay(), false, LocalDate.ofEpochDay(2).atStartOfDay(), true), "(\"1970-01-01 00:00:00\",\"1970-01-03 00:00:00\"]");
    }
  }

  private void assertTsRangeInsert(PreparedStatement insert, PGtsrange tsrange,
      String expected) throws SQLException {
    if (((PGConnection) con).getPreferQueryMode() == PreferQueryMode.SIMPLE) {
      try (Statement stmt = con.createStatement()) {
        // simple mode supports no bind parameter
        stmt.executeUpdate("INSERT INTO table1 (tsrange_column) VALUES ('" + tsrange + "'::tsrange)");
      }
    } else {
      insert.setObject(1, tsrange);
      insert.executeUpdate();
    }

    try (Statement stmt = con.createStatement(); ResultSet rs =
        stmt.executeQuery(TestUtil.selectSQL("table1", "tsrange_column"))) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject(1, PGtsrange.class).getValue());
      assertFalse(rs.next());
      stmt.executeUpdate("DELETE FROM table1");
    }
  }

  @Test
  public void selectTsTzRange() throws SQLException {
    try (Statement stmt = con.createStatement()) {

      stmt.executeUpdate(TestUtil.insertSQL("table1", "tstzrange_column", "'(,)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "tstzrange_column", "'[\"1970-01-01 00:00:00+00\",\"9999-12-31 00:00:00+00\")'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "tstzrange_column", "'(,\"2020-12-21 00:00:00+00\")'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "tstzrange_column", "'[\"1337-04-20 00:00:00+00\",\"2069-04-20 00:00:00+00\")'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "tstzrange_column", "'[\"2001-09-12 00:00:00+00\",)'"));

      try (ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "tstzrange_column", null, "tstzrange_column"))) {

        assertTrue(rs.next());
        PGtstzrange range = rs.getObject("tstzrange_column", PGtstzrange.class);
        // (,)
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("tstzrange_column", PGtstzrange.class);
        // [1970-01-01,9999-12-31)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(offsetDateTime("1970-01-01"), range.getLowerBound());
        assertEquals(offsetDateTime("9999-12-31"), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("tstzrange_column", PGtstzrange.class);
        // (,2020-12-21)
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertEquals(offsetDateTime("2020-12-21"), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("tstzrange_column", PGtstzrange.class);
        // [1337-04-20,2069-04-20)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(offsetDateTime("1337-04-20"), range.getLowerBound());
        assertEquals(offsetDateTime("2069-04-20"), range.getUpperBound());
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        range = rs.getObject("tstzrange_column", PGtstzrange.class);
        // [2001-09-12,)
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(offsetDateTime("2001-09-12"), range.getLowerBound());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertFalse(rs.next());
      }
    }

  }

  @Test
  public void insertTsTzRange() throws SQLException {
    try (PreparedStatement insert =
             con.prepareStatement("INSERT INTO table1 (tstzrange_column) VALUES (?)")) {
      ZoneOffset offset = ZoneOffset.UTC;
      assertTsTzRangeInsert(insert, new PGtstzrange("[\"1970-01-01 00:00:00+00\",\"1970-01-01 00:00:00+00\")"), "empty");
      assertTsTzRangeInsert(insert, new PGtstzrange("(,)"), "(,)");

      assertTsTzRangeInsert(insert, new PGtstzrange("[\"1970-01-01 00:00:00+00\",)"), "[" + tstz("1970-01-01") + ",)");
      assertTsTzRangeInsert(insert, new PGtstzrange("(\"1970-01-01 00:00:00+00\",]"), "(" + tstz("1970-01-01") + ",)");

      assertTsTzRangeInsert(insert, new PGtstzrange("[,\"1970-01-01 00:00:00+00\")"), "(," + tstz("1970-01-01") + ")");
      assertTsTzRangeInsert(insert, new PGtstzrange("[,\"1970-01-01 00:00:00+00\"]"), "(," + tstz("1970-01-01") + "]");

      assertTsTzRangeInsert(insert, new PGtstzrange("[\"1970-01-01 00:00:00+00\",\"1970-01-02 00:00:00+00\")"), "[" + tstz("1970-01-01") + "," + tstz("1970-01-02") + ")");
      assertTsTzRangeInsert(insert, new PGtstzrange("[\"1970-01-01 00:00:00+00\",\"1970-01-03 00:00:00+00\")"), "[" + tstz("1970-01-01") + "," + tstz("1970-01-03") + ")");
      assertTsTzRangeInsert(insert, new PGtstzrange("(\"1970-01-01 00:00:00+00\",\"1970-01-02 00:00:00+00\"]"), "(" + tstz("1970-01-01") + "," + tstz("1970-01-02") + "]");
      assertTsTzRangeInsert(insert, new PGtstzrange(LocalDate.ofEpochDay(0).atStartOfDay().atOffset(offset), LocalDate.ofEpochDay(2).atStartOfDay().atOffset(offset)), "[" + tstz("1970-01-01") + "," + tstz("1970-01-03") + ")");
      assertTsTzRangeInsert(insert, new PGtstzrange(LocalDate.ofEpochDay(0).atStartOfDay().atOffset(offset), false, LocalDate.ofEpochDay(2).atStartOfDay().atOffset(offset), true), "(" + tstz("1970-01-01") + "," + tstz("1970-01-03") + "]");
    }
  }

  private void assertTsTzRangeInsert(PreparedStatement insert, PGtstzrange tstzrange,
      String expected) throws SQLException {
    if (((PGConnection) con).getPreferQueryMode() == PreferQueryMode.SIMPLE) {
      try (Statement stmt = con.createStatement()) {
        // simple mode supports no bind parameter
        stmt.executeUpdate("INSERT INTO table1 (tstzrange_column) VALUES ('" + tstzrange + "'::tstzrange)");
      }
    } else {
      insert.setObject(1, tstzrange);
      insert.executeUpdate();
    }

    try (Statement stmt = con.createStatement(); ResultSet rs =
        stmt.executeQuery(TestUtil.selectSQL("table1", "tstzrange_column"))) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject(1, PGtstzrange.class).getValue());
      assertFalse(rs.next());
      stmt.executeUpdate("DELETE FROM table1");
    }
  }

  private static OffsetDateTime offsetDateTime(String tstzOrDate) {
    return OffsetDateTime.parse(tstzOrDate + (tstzOrDate.length() == 10 ? "T00:00:00Z" : ""))
        .atZoneSameInstant(SYSTEM_ZONE).toOffsetDateTime();
  }

  /**
   * @return date formatted as Postgres tstzrange: "1970-01-01 01:00:00+01:00" (for JVM timezone Europe/Paris)
   */
  private static String tstz(String date) {
    return OffsetDateTime.parse(date + (date.length() == 10 ? "T00:00:00Z" : ""))
        .atZoneSameInstant(SYSTEM_ZONE).toOffsetDateTime().format(OFFSET_DATE_TIME_FORMATTER);
  }

}
