/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Exercises updateable ResultSet (ResultSet.CONCUR_UPDATABLE) against
 * composite (Struct) and composite-array columns from a consumer perspective.
 *
 * <p>Assertions read the persisted state through a fresh SELECT rather than
 * from the same ResultSet after insertRow/updateRow: the local rowBuffer
 * path in PgResultSet.setRowBufferColumn currently falls into
 * String.valueOf(...) for non-PGobject Struct/Array values, which is a
 * separate known gap from the wire-format binding the driver uses to
 * actually send the data to the server.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ParameterizedClass
@MethodSource("data")
class CompositeUpdateableResultSetConsumerTest extends BaseTest4 {
  CompositeUpdateableResultSetConsumerTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @BeforeAll
  static void setUpSchema() throws SQLException {
    try (Connection conn = TestUtil.openDB();
         Statement stmt = conn.createStatement()) {
      cleanup(stmt);

      stmt.execute("CREATE TYPE updateable_order_line AS (sku text, quantity int)");
      stmt.execute("CREATE TABLE updateable_orders ("
          + "id int primary key, "
          + "line updateable_order_line, "
          + "items updateable_order_line[])");
    }
  }

  @AfterAll
  static void tearDownSchema() throws SQLException {
    try (Connection conn = TestUtil.openDB();
         Statement stmt = conn.createStatement()) {
      cleanup(stmt);
    }
  }

  private static void cleanup(Statement stmt) throws SQLException {
    stmt.execute("DROP TABLE IF EXISTS updateable_orders");
    stmt.execute("DROP TYPE IF EXISTS updateable_order_line CASCADE");
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    try (Statement stmt = con.createStatement()) {
      stmt.execute("TRUNCATE TABLE updateable_orders");
    }
  }

  @Test
  void insertRow_withStructColumn_persistsCompositeToServer() throws SQLException {
    Struct line = con.createStruct("updateable_order_line", new Object[]{"sku-ins", 7});

    try (Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
         ResultSet rs = stmt.executeQuery("SELECT id, line, items FROM updateable_orders")) {
      rs.moveToInsertRow();
      rs.updateInt(1, 1);
      rs.updateObject(2, line);
      rs.insertRow();
    }

    assertPersistedLine(1, "sku-ins", 7);
  }

  @Test
  void insertRow_withCompositeArrayColumn_persistsArrayToServer() throws SQLException {
    Struct first = con.createStruct("updateable_order_line", new Object[]{"sku-a", 1});
    Struct second = con.createStruct("updateable_order_line", new Object[]{"sku-b", 2});
    Array items = con.createArrayOf("updateable_order_line", new Object[]{first, second});

    try (Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
         ResultSet rs = stmt.executeQuery("SELECT id, line, items FROM updateable_orders")) {
      rs.moveToInsertRow();
      rs.updateInt(1, 2);
      rs.updateArray(3, items);
      rs.insertRow();
    }

    assertPersistedItems(2, new Object[][]{{"sku-a", 1}, {"sku-b", 2}});
  }

  @Test
  void updateRow_replacesStructColumn_persistsNewComposite() throws SQLException {
    try (Statement seed = con.createStatement()) {
      seed.executeUpdate(
          "INSERT INTO updateable_orders (id, line) VALUES (3, ROW('sku-old', 0)::updateable_order_line)");
    }
    Struct replacement = con.createStruct("updateable_order_line", new Object[]{"sku-new", 42});

    try (Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
         ResultSet rs = stmt.executeQuery(
             "SELECT id, line, items FROM updateable_orders WHERE id = 3")) {
      assertTrue(rs.next());
      rs.updateObject(2, replacement);
      rs.updateRow();
    }

    assertPersistedLine(3, "sku-new", 42);
  }

  @Test
  void updateRow_replacesCompositeArrayColumn_persistsNewArray() throws SQLException {
    try (Statement seed = con.createStatement()) {
      seed.executeUpdate(
          "INSERT INTO updateable_orders (id, items) VALUES (4, ARRAY[ROW('sku-old', 0)::updateable_order_line])");
    }
    Struct first = con.createStruct("updateable_order_line", new Object[]{"sku-x", 10});
    Struct second = con.createStruct("updateable_order_line", new Object[]{"sku-y", 20});
    Array items = con.createArrayOf("updateable_order_line", new Object[]{first, second});

    try (Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
         ResultSet rs = stmt.executeQuery(
             "SELECT id, line, items FROM updateable_orders WHERE id = 4")) {
      assertTrue(rs.next());
      rs.updateArray(3, items);
      rs.updateRow();
    }

    assertPersistedItems(4, new Object[][]{{"sku-x", 10}, {"sku-y", 20}});
  }

  @Test
  void updateObject_withStructAndArray_acceptsAndPersistsBothInSingleRow() throws SQLException {
    try (Statement seed = con.createStatement()) {
      seed.executeUpdate("INSERT INTO updateable_orders (id) VALUES (5)");
    }
    Struct line = con.createStruct("updateable_order_line", new Object[]{"sku-both", 3});
    Struct itemA = con.createStruct("updateable_order_line", new Object[]{"sku-i1", 1});
    Struct itemB = con.createStruct("updateable_order_line", new Object[]{"sku-i2", 2});
    Array items = con.createArrayOf("updateable_order_line", new Object[]{itemA, itemB});

    try (Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
         ResultSet rs = stmt.executeQuery(
             "SELECT id, line, items FROM updateable_orders WHERE id = 5")) {
      assertTrue(rs.next());
      rs.updateObject(2, line);
      rs.updateObject(3, items);
      rs.updateRow();
    }

    assertPersistedLine(5, "sku-both", 3);
    assertPersistedItems(5, new Object[][]{{"sku-i1", 1}, {"sku-i2", 2}});
  }

  @Test
  void updateRow_thenReadStructFromSameResultSet_reflectsNewValue() throws SQLException {
    try (Statement seed = con.createStatement()) {
      seed.executeUpdate(
          "INSERT INTO updateable_orders (id, line) VALUES (6, ROW('sku-old', 0)::updateable_order_line)");
    }
    Struct replacement = con.createStruct("updateable_order_line", new Object[]{"sku-rs", 99});

    try (Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
         ResultSet rs = stmt.executeQuery(
             "SELECT id, line, items FROM updateable_orders WHERE id = 6")) {
      assertTrue(rs.next());
      rs.updateObject(2, replacement);
      rs.updateRow();

      Struct actual = rs.getObject(2, Struct.class);
      assertNotNull(actual);
      Object[] attrs = actual.getAttributes();
      assertEquals("sku-rs", attrs[0]);
      assertEquals(99, attrs[1]);
    }
  }

  @Test
  void updateRow_thenReadArrayFromSameResultSet_reflectsNewValue() throws SQLException {
    try (Statement seed = con.createStatement()) {
      seed.executeUpdate(
          "INSERT INTO updateable_orders (id, items) VALUES (7, ARRAY[ROW('sku-old', 0)::updateable_order_line])");
    }
    Struct first = con.createStruct("updateable_order_line", new Object[]{"sku-p", 11});
    Struct second = con.createStruct("updateable_order_line", new Object[]{"sku-q", 22});
    Array items = con.createArrayOf("updateable_order_line", new Object[]{first, second});

    try (Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
         ResultSet rs = stmt.executeQuery(
             "SELECT id, line, items FROM updateable_orders WHERE id = 7")) {
      assertTrue(rs.next());
      rs.updateArray(3, items);
      rs.updateRow();

      Array actual = rs.getArray(3);
      assertNotNull(actual);
      Object[] elements = (Object[]) actual.getArray();
      assertEquals(2, elements.length);
      assertArrayEquals(new Object[]{"sku-p", 11}, ((Struct) elements[0]).getAttributes());
      assertArrayEquals(new Object[]{"sku-q", 22}, ((Struct) elements[1]).getAttributes());
    }
  }

  @Test
  void insertRow_thenReadStructFromSameResultSet_reflectsInsertedValue() throws SQLException {
    Struct line = con.createStruct("updateable_order_line", new Object[]{"sku-ir", 5});

    try (Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
         ResultSet rs = stmt.executeQuery("SELECT id, line, items FROM updateable_orders")) {
      rs.moveToInsertRow();
      rs.updateInt(1, 8);
      rs.updateObject(2, line);
      rs.insertRow();

      Struct actual = rs.getObject(2, Struct.class);
      assertNotNull(actual);
      Object[] attrs = actual.getAttributes();
      assertEquals("sku-ir", attrs[0]);
      assertEquals(5, attrs[1]);
    }
  }

  @Test
  void refreshRow_afterUpdate_pullsServerStateForCompositeAndArray() throws SQLException {
    try (Statement seed = con.createStatement()) {
      seed.executeUpdate("INSERT INTO updateable_orders (id, line, items) VALUES (9, "
          + "ROW('sku-orig', 1)::updateable_order_line, "
          + "ARRAY[ROW('sku-arr-orig', 1)::updateable_order_line])");
    }
    Struct line = con.createStruct("updateable_order_line", new Object[]{"sku-refresh", 7});
    Array items = con.createArrayOf("updateable_order_line",
        new Object[]{con.createStruct("updateable_order_line", new Object[]{"sku-arr-refresh", 7})});

    try (Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
         ResultSet rs = stmt.executeQuery(
             "SELECT id, line, items FROM updateable_orders WHERE id = 9")) {
      assertTrue(rs.next());
      rs.updateObject(2, line);
      rs.updateArray(3, items);
      rs.updateRow();
      rs.refreshRow();

      Struct actualLine = rs.getObject(2, Struct.class);
      assertNotNull(actualLine);
      assertArrayEquals(new Object[]{"sku-refresh", 7}, actualLine.getAttributes());

      Object[] elements = (Object[]) rs.getArray(3).getArray();
      assertEquals(1, elements.length);
      assertArrayEquals(new Object[]{"sku-arr-refresh", 7},
          ((Struct) elements[0]).getAttributes());
    }
  }

  private void assertPersistedLine(int id, String expectedSku, int expectedQuantity)
      throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT line FROM updateable_orders WHERE id = " + id)) {
      assertTrue(rs.next());
      Struct actual = rs.getObject(1, Struct.class);
      assertNotNull(actual);
      Object[] attrs = actual.getAttributes();
      assertEquals(expectedSku, attrs[0]);
      assertEquals(expectedQuantity, attrs[1]);
    }
  }

  private void assertPersistedItems(int id, Object[][] expected) throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT items FROM updateable_orders WHERE id = " + id)) {
      assertTrue(rs.next());
      Array array = rs.getArray(1);
      assertNotNull(array);
      Object[] actual = (Object[]) array.getArray();
      assertEquals(expected.length, actual.length);
      for (int i = 0; i < expected.length; i++) {
        Struct row = (Struct) actual[i];
        assertNotNull(row);
        assertArrayEquals(expected[i], row.getAttributes());
      }
    }
  }
}
