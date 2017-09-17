/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PGint4range;
import org.postgresql.util.PGint8range;
import org.postgresql.util.PGnumrange;
import org.postgresql.util.PGrange;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class RangeTypeTest extends BaseTest4 {

  public RangeTypeTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();

    assumeMinimumServerVersion("range types requires PostgreSQL 9.2+", ServerVersion.v9_2);
    TestUtil.createTable(con, "table1",
            "int4range_column int4range,"
            + "int8range_column int8range,"
            + "numrange_column numrange,"
            + "tsrange_column tsrange,"
            + "tstzrange_column tstzrange,"
            + "daterange_column daterange"
    );
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
    assertNotEquals(range, new PGnumrange(BigDecimal.valueOf(1), true, BigDecimal.valueOf(3), true));
    assertNotEquals(range, new PGnumrange(BigDecimal.valueOf(1), false, BigDecimal.valueOf(3), true));
    assertNotEquals(range, new PGnumrange(BigDecimal.valueOf(1), false, BigDecimal.valueOf(3), false));
    assertNotEquals(range, new PGnumrange(BigDecimal.valueOf(0), false, BigDecimal.valueOf(2), true));
    assertEquals(range.hashCode(), new PGnumrange(BigDecimal.valueOf(1), BigDecimal.valueOf(3)).hashCode());
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
    try (PreparedStatement insert = con.prepareStatement("INSERT INTO table1 (int4range_column) VALUES (?)")) {
      assertRangeInsert(insert, new PGint4range("[1,1)"), "(,)");
      assertRangeInsert(insert, new PGint4range("(,)"), "(,)");

      assertRangeInsert(insert, new PGint4range("[1,)"), "[1,)");
      assertRangeInsert(insert, new PGint4range("(0,]"), "[1,)");

      assertRangeInsert(insert, new PGint4range("[,3)"), "(,3)");
      assertRangeInsert(insert, new PGint4range("[,2]"), "(,3)");

      assertRangeInsert(insert, new PGint4range("[1,2)"), "[1,2)");
      assertRangeInsert(insert, new PGint4range("[1,3)"), "[1,3)");
      assertRangeInsert(insert, new PGint4range("(0,2]"), "[1,3)");
      assertRangeInsert(insert, new PGint4range(1, 3), "[1,3)");
      assertRangeInsert(insert, new PGint4range(0, false, 2, true), "[1,3)");
    }
  }

  @Test
  public void selectInt8Range() throws SQLException {
    try (Statement stmt = con.createStatement()) {

      stmt.executeUpdate(TestUtil.insertSQL("table1", "int8range_column", "'(,)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "int8range_column", "'[-9223372036854775808,9223372036854775807)'"));
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
    try (PreparedStatement insert = con.prepareStatement("INSERT INTO table1 (int8range_column) VALUES (?)")) {
      assertRangeInsert(insert, new PGint8range("[1,1)"), "(,)");
      assertRangeInsert(insert, new PGint8range("(,)"), "(,)");

      assertRangeInsert(insert, new PGint8range("[1,)"), "[1,)");
      assertRangeInsert(insert, new PGint8range("(0,]"), "[1,)");

      assertRangeInsert(insert, new PGint8range("[,3)"), "(,3)");
      assertRangeInsert(insert, new PGint8range("[,2]"), "(,3)");

      assertRangeInsert(insert, new PGint8range("[1,2)"), "[1,2)");
      assertRangeInsert(insert, new PGint8range("[1,3)"), "[1,3)");
      assertRangeInsert(insert, new PGint8range("(0,2]"), "[1,3)");
      assertRangeInsert(insert, new PGint8range(1L, 3L), "[1,3)");
      assertRangeInsert(insert, new PGint8range(0L, false, 2L, true), "[1,3)");
    }
  }

  @Test
  public void selecNumRange() throws SQLException {
    try (Statement stmt = con.createStatement()) {

      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column", "'(,)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column", "'[-9223372036854775808.8,9223372036854775807.7)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column", "'(,3.3)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column", "'[4.4,20.20)'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column", "'(4.4,20.20]'"));
      stmt.executeUpdate(TestUtil.insertSQL("table1", "numrange_column", "'[50.50,)'"));

      try (ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "numrange_column", null, "numrange_column"))) {

        assertTrue(rs.next());
        assertEquals("(,)", rs.getString("numrange_column"));
        PGnumrange range = rs.getObject("numrange_column", PGnumrange.class);
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertFalse(range.isUpperInclusive());
        assertTrue(range.isUpperInfinite());

        assertTrue(rs.next());
        assertEquals("[-9223372036854775808.8,9223372036854775807.7)", rs.getString("numrange_column"));
        range = rs.getObject("numrange_column", PGnumrange.class);
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(0, range.getLowerBound().compareTo(new BigDecimal("-9223372036854775808.8")));
        assertEquals(0, range.getUpperBound().compareTo(new BigDecimal("9223372036854775807.7")));
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        assertEquals("(,3.3)", rs.getString("numrange_column"));
        range = rs.getObject("numrange_column", PGnumrange.class);
        assertFalse(range.isLowerInclusive());
        assertTrue(range.isLowerInfinite());
        assertEquals(0, range.getUpperBound().compareTo(new BigDecimal("3.3")));
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        assertEquals("[4.4,20.20)", rs.getString("numrange_column"));
        range = rs.getObject("numrange_column", PGnumrange.class);
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(0, range.getLowerBound().compareTo(new BigDecimal("4.4")));
        assertEquals(0, range.getUpperBound().compareTo(new BigDecimal("20.20")));
        assertFalse(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        assertEquals("(4.4,20.20]", rs.getString("numrange_column"));
        range = rs.getObject("numrange_column", PGnumrange.class);
        assertFalse(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
        assertEquals(0, range.getLowerBound().compareTo(new BigDecimal("4.4")));
        assertEquals(0, range.getUpperBound().compareTo(new BigDecimal("20.20")));
        assertTrue(range.isUpperInclusive());
        assertFalse(range.isUpperInfinite());

        assertTrue(rs.next());
        assertEquals("[50.50,)", rs.getString("numrange_column"));
        range = rs.getObject("numrange_column", PGnumrange.class);
        assertTrue(range.isLowerInclusive());
        assertFalse(range.isLowerInfinite());
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
    try (PreparedStatement insert = con.prepareStatement("INSERT INTO table1 (numrange_column) VALUES (?)")) {
      assertRangeInsert(insert, new PGnumrange("[1.1,1.1)"), "(,)");
      assertRangeInsert(insert, new PGnumrange("(,)"), "(,)");

      assertRangeInsert(insert, new PGnumrange("[1.1,)"), "[1.1,)");
      assertRangeInsert(insert, new PGnumrange("(1.1,]"), "(1.1,)");

      assertRangeInsert(insert, new PGnumrange("[,3.3)"), "(,3.3)");
      assertRangeInsert(insert, new PGnumrange("[,2.2]"), "(,2.2]");

      assertRangeInsert(insert, new PGnumrange("[1.1,2.2)"), "[1.1,2.2)");
      assertRangeInsert(insert, new PGnumrange("[1.1,3.3)"), "[1.1,3.3)");
      assertRangeInsert(insert, new PGnumrange("(1.1,2.2]"), "(1.1,2.2]");
      assertRangeInsert(insert, new PGnumrange(new BigDecimal("1.1"), new BigDecimal("3.3")), "[1.1,3.3)");
      assertRangeInsert(insert, new PGnumrange(new BigDecimal("1.1"), false, new BigDecimal("3.3"), true), "(1.1,3.3]");
    }
  }

  private <R extends PGrange<?>> void assertRangeInsert(PreparedStatement insert, R range, String expected)
      throws SQLException {
    String columnName = range.getType() + "_column";
    insert.setObject(1, range);
    insert.executeUpdate();

    try (Statement stmt = con.createStatement();
        ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", columnName))) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getObject(1, range.getClass()).getValue());
      assertFalse(rs.next());
      stmt.executeUpdate("DELETE FROM table1");
    }
  }

}
