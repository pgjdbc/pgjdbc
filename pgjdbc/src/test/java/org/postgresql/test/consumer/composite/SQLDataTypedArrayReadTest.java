/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Reads an array of {@link SQLData} composites as a typed Java array, both
 * directly from the {@link ResultSet} via {@code getObject(col, Leaf[].class)}
 * and from inside another {@link SQLData} via {@code readObject(Leaf[].class)}.
 *
 * <p>{@link SQLDataNestedStructArrayTest} deliberately reads composite-array
 * fields as {@code java.sql.Array} and materialises the elements as
 * {@code java.sql.Struct}. This test covers the complementary path: the caller
 * asks for the concrete {@code SQLData} component type, so each element is mapped
 * to the user class.</p>
 *
 * <p>Ported from the {@code SQLDataTest} fixtures contributed by Ken Southerland in
 * <a href="https://github.com/pgjdbc/pgjdbc/pull/3396">pgjdbc/pgjdbc#3396</a>.</p>
 */
@Execution(ExecutionMode.SAME_THREAD)
@ParameterizedClass
@MethodSource("data")
public class SQLDataTypedArrayReadTest extends BaseTest4 {

  SQLDataTypedArrayReadTest(BinaryMode binaryMode) {
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
      stmt.execute("CREATE TYPE sqldata_leaf AS (label text, weight int)");
      stmt.execute("CREATE TYPE sqldata_leaf_box AS (name text, leaves sqldata_leaf[])");
      stmt.execute("CREATE TABLE sqldata_leaf_rows (id int primary key, leaves sqldata_leaf[])");
      stmt.execute("CREATE TABLE sqldata_leaf_box_rows (id int primary key, payload sqldata_leaf_box)");
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
    stmt.execute("DROP TABLE IF EXISTS sqldata_leaf_box_rows");
    stmt.execute("DROP TABLE IF EXISTS sqldata_leaf_rows");
    stmt.execute("DROP TYPE IF EXISTS sqldata_leaf_box CASCADE");
    stmt.execute("DROP TYPE IF EXISTS sqldata_leaf CASCADE");
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    try (Statement stmt = con.createStatement()) {
      stmt.execute("TRUNCATE TABLE sqldata_leaf_rows");
      stmt.execute("TRUNCATE TABLE sqldata_leaf_box_rows");
    }
  }

  @Test
  void getObjectTypedArrayMapsEachElementToSqlData() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("INSERT INTO sqldata_leaf_rows (id, leaves) VALUES (1, ARRAY["
          + "ROW('a', 1)::sqldata_leaf,"
          + "ROW('b', 2)::sqldata_leaf"
          + "])");
    }

    try (PreparedStatement select = con.prepareStatement(
        "SELECT leaves FROM sqldata_leaf_rows WHERE id = 1");
         ResultSet rs = select.executeQuery()) {
      assertTrue(rs.next());
      Leaf[] leaves = rs.getObject(1, Leaf[].class);
      assertNotNull(leaves);
      assertEquals(2, leaves.length);
      assertEquals("a", leaves[0].label);
      assertEquals(1, leaves[0].weight);
      assertEquals("b", leaves[1].label);
      assertEquals(2, leaves[1].weight);
    }
  }

  @Test
  void readObjectTypedArrayInsideCompositeMapsEachElementToSqlData() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("INSERT INTO sqldata_leaf_box_rows (id, payload) VALUES (1, ROW("
          + "'box',"
          + "ARRAY[ROW('a', 1)::sqldata_leaf, ROW('b', 2)::sqldata_leaf]"
          + ")::sqldata_leaf_box)");
    }

    try (PreparedStatement select = con.prepareStatement(
        "SELECT payload FROM sqldata_leaf_box_rows WHERE id = 1");
         ResultSet rs = select.executeQuery()) {
      assertTrue(rs.next());
      LeafBox actual = rs.getObject(1, LeafBox.class);
      assertNotNull(actual);
      assertEquals("box", actual.name);
      assertNotNull(actual.leaves);
      assertEquals(2, actual.leaves.length);
      assertEquals("a", actual.leaves[0].label);
      assertEquals(1, actual.leaves[0].weight);
      assertEquals("b", actual.leaves[1].label);
      assertEquals(2, actual.leaves[1].weight);
    }
  }

  /** Leaf composite read through primitive readers only. */
  public static final class Leaf implements SQLData {
    String label;
    int weight;

    public Leaf() {
    }

    @Override
    public String getSQLTypeName() {
      return "sqldata_leaf";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      label = stream.readString();
      weight = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      throw new UnsupportedOperationException("read-only fixture");
    }
  }

  /** Composite whose array-of-composite field is read as a typed {@code Leaf[]}. */
  public static final class LeafBox implements SQLData {
    String name;
    Leaf[] leaves;

    public LeafBox() {
    }

    @Override
    public String getSQLTypeName() {
      return "sqldata_leaf_box";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      name = stream.readString();
      leaves = stream.readObject(Leaf[].class);
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      throw new UnsupportedOperationException("read-only fixture");
    }
  }
}
