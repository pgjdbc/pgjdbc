/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Exercises 4-level nesting of composite types and arrays in both directions:
 * <ul>
 *   <li>{@code struct -> array -> struct -> array} — column type is a single
 *       struct {@code nest_outer(outer_id int, leaves nest_leaf[])}, where each
 *       leaf is {@code (label text, scores int[])}.</li>
 *   <li>{@code array -> struct -> array -> struct} — column type is
 *       {@code nest_outer[]}, so the value is an array of structs each of which
 *       contains an array of leaf structs.</li>
 * </ul>
 *
 * <p>Both directions are exercised via {@link Connection#createStruct} /
 * {@link Connection#createArrayOf} and via {@link SQLData}-based round trips
 * to make sure user-supplied SQLData implementations cooperate with the
 * driver's nested composite encoding.</p>
 *
 * <p>For the {@code SQLData} write path, the outer {@code nest_outer} value is
 * an {@link SQLData} that writes its {@code nest_leaf[]} field as a
 * {@link java.sql.Array}. Building a composite-element array inside
 * {@code writeSQL} still requires access to {@link Connection#createArrayOf};
 * the array is therefore built externally and handed to the SQLData via a
 * field. On the read side, the same SQLData reads its {@code nest_leaf[]}
 * back as a {@code java.sql.Array} of {@link Struct}s, then materializes
 * {@link NestLeaf} instances element by element.</p>
 */
@Execution(ExecutionMode.SAME_THREAD)
@ParameterizedClass
@MethodSource("data")
public class NestedStructArrayRoundtripTest extends BaseTest4 {

  NestedStructArrayRoundtripTest(BinaryMode binaryMode) {
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
      stmt.execute("CREATE TYPE nest_leaf AS (label text, scores int[])");
      stmt.execute("CREATE TYPE nest_outer AS (outer_id int, leaves nest_leaf[])");
      stmt.execute("CREATE TABLE nest_struct_array_rows (id int primary key, payload nest_outer)");
      stmt.execute("CREATE TABLE nest_array_struct_rows (id int primary key, items nest_outer[])");
      stmt.execute("CREATE TABLE nest_leaf_matrix_rows (id int primary key, matrix nest_leaf[][])");
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
    stmt.execute("DROP TABLE IF EXISTS nest_leaf_matrix_rows");
    stmt.execute("DROP TABLE IF EXISTS nest_array_struct_rows");
    stmt.execute("DROP TABLE IF EXISTS nest_struct_array_rows");
    stmt.execute("DROP TYPE IF EXISTS nest_outer CASCADE");
    stmt.execute("DROP TYPE IF EXISTS nest_leaf CASCADE");
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    try (Statement stmt = con.createStatement()) {
      stmt.execute("TRUNCATE TABLE nest_struct_array_rows");
      stmt.execute("TRUNCATE TABLE nest_array_struct_rows");
      stmt.execute("TRUNCATE TABLE nest_leaf_matrix_rows");
    }
  }

  // ---------- struct -> array -> struct -> array ----------

  @Test
  void structArrayStructArray_viaCreateStruct_readBackAsStructChain() throws SQLException {
    Struct leaf1 = con.createStruct("nest_leaf",
        new Object[]{"alpha", new Integer[]{1, 2}});
    Struct leaf2 = con.createStruct("nest_leaf",
        new Object[]{"beta", new Integer[]{3, 4, 5}});
    Array leaves = con.createArrayOf("nest_leaf", new Object[]{leaf1, leaf2});
    Struct outer = con.createStruct("nest_outer", new Object[]{100, leaves});

    insertStructArray(1, outer);

    try (PreparedStatement select = con.prepareStatement(
        "SELECT payload FROM nest_struct_array_rows WHERE id = ?")) {
      select.setInt(1, 1);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Struct readOuter = rs.getObject(1, Struct.class);
        Object[] outerAttrs = readOuter.getAttributes();
        assertEquals(2, outerAttrs.length);
        assertEquals(100, outerAttrs[0]);
        Array readLeaves = assertInstanceOf(Array.class, outerAttrs[1]);
        Object[] leafElements = (Object[]) readLeaves.getArray();
        assertEquals(2, leafElements.length);
        assertLeafStruct(leafElements[0], "alpha", new Integer[]{1, 2});
        assertLeafStruct(leafElements[1], "beta", new Integer[]{3, 4, 5});
      }
    }
  }

  @Test
  void structArrayStructArray_viaCreateStruct_readBackAsSqlData() throws SQLException {
    Struct leaf1 = con.createStruct("nest_leaf",
        new Object[]{"alpha", new Integer[]{1, 2}});
    Struct leaf2 = con.createStruct("nest_leaf",
        new Object[]{"beta", new Integer[]{3, 4, 5}});
    Array leaves = con.createArrayOf("nest_leaf", new Object[]{leaf1, leaf2});
    Struct outer = con.createStruct("nest_outer", new Object[]{100, leaves});

    insertStructArray(2, outer);

    try (PreparedStatement select = con.prepareStatement(
        "SELECT payload FROM nest_struct_array_rows WHERE id = ?")) {
      select.setInt(1, 2);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        NestOuter actual = (NestOuter) rs.getObject(1, nestedTypeMap());
        assertNotNull(actual);
        assertEquals(100, actual.outerId);
        NestLeaf[] readLeaves = actual.materializedLeaves;
        assertNotNull(readLeaves);
        assertEquals(2, readLeaves.length);
        assertLeaf(readLeaves[0], "alpha", new Integer[]{1, 2});
        assertLeaf(readLeaves[1], "beta", new Integer[]{3, 4, 5});
      }
    }
  }

  @Test
  void structArrayStructArray_viaSqlDataWrite_roundTrip() throws SQLException {
    // Outer struct is sent via setObject(SQLData). Inside writeSQL the leaves
    // field is written directly as NestLeaf[] via
    // writeObject(SQLData[], JDBCType.ARRAY) — ArrayCodec dispatches each
    // element through the registered composite codec, so no
    // Connection.createArrayOf / createStruct is needed.
    NestOuter outer = new NestOuter(100, new NestLeaf[]{
        new NestLeaf("alpha", new Integer[]{1, 2}),
        new NestLeaf("beta", new Integer[]{3, 4, 5})
    });

    insertStructArray(3, outer);

    try (PreparedStatement select = con.prepareStatement(
        "SELECT payload FROM nest_struct_array_rows WHERE id = ?")) {
      select.setInt(1, 3);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        NestOuter actual = (NestOuter) rs.getObject(1, nestedTypeMap());
        assertEquals(100, actual.outerId);
        NestLeaf[] readLeaves = actual.materializedLeaves;
        assertNotNull(readLeaves);
        assertEquals(2, readLeaves.length);
        assertLeaf(readLeaves[0], "alpha", new Integer[]{1, 2});
        assertLeaf(readLeaves[1], "beta", new Integer[]{3, 4, 5});
      }
    }
  }

  @Test
  void structArrayStructArray_nullLeavesAndNullScores() throws SQLException {
    Struct leafWithNullScores = con.createStruct("nest_leaf",
        new Object[]{"only-label", null});
    Array leaves = con.createArrayOf("nest_leaf", new Object[]{leafWithNullScores});
    Struct outerWithLeaves = con.createStruct("nest_outer", new Object[]{7, leaves});
    Struct outerWithNullLeaves = con.createStruct("nest_outer", new Object[]{8, null});

    insertStructArray(4, outerWithLeaves);
    insertStructArray(5, outerWithNullLeaves);

    try (PreparedStatement select = con.prepareStatement(
        "SELECT payload FROM nest_struct_array_rows WHERE id = ?")) {
      select.setInt(1, 4);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        NestOuter actual = (NestOuter) rs.getObject(1, nestedTypeMap());
        assertEquals(7, actual.outerId);
        NestLeaf[] readLeaves = actual.materializedLeaves;
        assertNotNull(readLeaves);
        assertEquals(1, readLeaves.length);
        assertEquals("only-label", readLeaves[0].label);
        assertNull(readLeaves[0].scores);
      }

      select.setInt(1, 5);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        NestOuter actual = (NestOuter) rs.getObject(1, nestedTypeMap());
        assertEquals(8, actual.outerId);
        assertNull(actual.materializedLeaves);
      }
    }
  }

  // ---------- array -> struct -> array -> struct ----------

  @Test
  void arrayStructArrayStruct_viaCreateStruct_readBackAsStructChain() throws SQLException {
    Struct outer1 = buildOuterStruct(100, new NestLeaf("alpha", new Integer[]{1, 2}),
        new NestLeaf("beta", new Integer[]{3, 4, 5}));
    Struct outer2 = buildOuterStruct(200, new NestLeaf("gamma", new Integer[]{7}));
    Array outerArray = con.createArrayOf("nest_outer", new Object[]{outer1, outer2});

    insertArrayStruct(1, outerArray);

    try (PreparedStatement select = con.prepareStatement(
        "SELECT items FROM nest_array_struct_rows WHERE id = ?")) {
      select.setInt(1, 1);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Object[] outers = (Object[]) rs.getArray(1).getArray();
        assertEquals(2, outers.length);

        Struct readOuter1 = assertInstanceOf(Struct.class, outers[0]);
        Object[] outer1Attrs = readOuter1.getAttributes();
        assertEquals(100, outer1Attrs[0]);
        Object[] outer1Leaves = (Object[]) ((Array) outer1Attrs[1]).getArray();
        assertEquals(2, outer1Leaves.length);
        assertLeafStruct(outer1Leaves[0], "alpha", new Integer[]{1, 2});
        assertLeafStruct(outer1Leaves[1], "beta", new Integer[]{3, 4, 5});

        Struct readOuter2 = assertInstanceOf(Struct.class, outers[1]);
        Object[] outer2Attrs = readOuter2.getAttributes();
        assertEquals(200, outer2Attrs[0]);
        Object[] outer2Leaves = (Object[]) ((Array) outer2Attrs[1]).getArray();
        assertEquals(1, outer2Leaves.length);
        assertLeafStruct(outer2Leaves[0], "gamma", new Integer[]{7});
      }
    }
  }

  @Test
  void arrayStructArrayStruct_viaCreateStruct_readBackAsSqlData() throws SQLException {
    Struct outer1 = buildOuterStruct(100, new NestLeaf("alpha", new Integer[]{1, 2}),
        new NestLeaf("beta", new Integer[]{3, 4, 5}));
    Struct outer2 = buildOuterStruct(200, new NestLeaf("gamma", new Integer[]{7}));
    Array outerArray = con.createArrayOf("nest_outer", new Object[]{outer1, outer2});

    insertArrayStruct(2, outerArray);

    try (PreparedStatement select = con.prepareStatement(
        "SELECT items FROM nest_array_struct_rows WHERE id = ?")) {
      select.setInt(1, 2);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Object[] outers = (Object[]) rs.getArray(1).getArray(nestedTypeMap());
        assertEquals(2, outers.length);

        NestOuter readOuter1 = assertInstanceOf(NestOuter.class, outers[0]);
        assertEquals(100, readOuter1.outerId);
        NestLeaf[] readLeaves1 = readOuter1.materializedLeaves;
        assertNotNull(readLeaves1);
        assertEquals(2, readLeaves1.length);
        assertLeaf(readLeaves1[0], "alpha", new Integer[]{1, 2});
        assertLeaf(readLeaves1[1], "beta", new Integer[]{3, 4, 5});

        NestOuter readOuter2 = assertInstanceOf(NestOuter.class, outers[1]);
        assertEquals(200, readOuter2.outerId);
        NestLeaf[] readLeaves2 = readOuter2.materializedLeaves;
        assertNotNull(readLeaves2);
        assertEquals(1, readLeaves2.length);
        assertLeaf(readLeaves2[0], "gamma", new Integer[]{7});
      }
    }
  }

  @Test
  void arrayStructArrayStruct_nullElementsAndNullLeaves() throws SQLException {
    Struct outerWithLeaves = buildOuterStruct(11,
        new NestLeaf("solo", new Integer[]{42}));
    Struct outerWithNullLeaves = con.createStruct("nest_outer", new Object[]{22, null});
    Array outerArray = con.createArrayOf("nest_outer",
        new Object[]{outerWithLeaves, null, outerWithNullLeaves});

    insertArrayStruct(3, outerArray);

    try (PreparedStatement select = con.prepareStatement(
        "SELECT items FROM nest_array_struct_rows WHERE id = ?")) {
      select.setInt(1, 3);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Object[] outers = (Object[]) rs.getArray(1).getArray(nestedTypeMap());
        assertEquals(3, outers.length);

        NestOuter readOuter1 = assertInstanceOf(NestOuter.class, outers[0]);
        assertEquals(11, readOuter1.outerId);
        assertNotNull(readOuter1.materializedLeaves);
        assertEquals(1, readOuter1.materializedLeaves.length);
        assertLeaf(readOuter1.materializedLeaves[0], "solo", new Integer[]{42});

        assertNull(outers[1]);

        NestOuter readOuter3 = assertInstanceOf(NestOuter.class, outers[2]);
        assertEquals(22, readOuter3.outerId);
        assertNull(readOuter3.materializedLeaves);
      }
    }
  }

  // ---------- nest_leaf[][] — multi-dim composite-element array ----------

  @Test
  void leafMatrix_quotesAndBackslashes_roundTripIntactViaTextAndBinary() throws SQLException {
    // Real escape-stress: composite element whose `label` text field carries a
    // double quote, a backslash, a comma, and array delimiters. The same value
    // is round-tripped twice — once in the active binary mode (where escaping
    // is moot) and once via an explicit text-mode comparison query — to make
    // sure ArrayCodec.encodeText through MultiDimArrayText produces an array
    // literal that PostgreSQL parses back to byte-identical content.
    String tricky = "she said \"hi\" \\ then {oops,};";
    Struct l00 = con.createStruct("nest_leaf", new Object[]{tricky, new Integer[]{1}});
    Struct l01 = con.createStruct("nest_leaf", new Object[]{"plain", new Integer[]{2}});
    Struct[][] matrix = {{l00, l01}};

    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO nest_leaf_matrix_rows (id, matrix) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT matrix FROM nest_leaf_matrix_rows WHERE id = ?")) {
      insert.setInt(1, 99);
      insert.setObject(2, con.createArrayOf("nest_leaf", matrix));
      assertEquals(1, insert.executeUpdate());

      select.setInt(1, 99);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Object[][] back = (Object[][]) rs.getArray(1).getArray();
        Struct s00 = (Struct) back[0][0];
        Object[] attrs = s00.getAttributes();
        assertEquals(tricky, attrs[0],
            "label with quotes / backslashes / array specials must round-trip intact");
        Struct s01 = (Struct) back[0][1];
        assertEquals("plain", s01.getAttributes()[0]);
      }
    }
  }

  @Test
  void leafMatrix_setObjectStructMatrix_readBackAsStructMatrix() throws SQLException {
    // 2x2 matrix of nest_leaf structs sent via setObject(Struct[][]). The
    // generic ArrayCodec path now walks multi-dim via MultiDimArrayBinary and
    // dispatches each leaf-level element through CompositeCodec, so a
    // Struct[][] no longer requires Connection.createArrayOf for the outer.
    Struct l00 = con.createStruct("nest_leaf", new Object[]{"a", new Integer[]{1}});
    Struct l01 = con.createStruct("nest_leaf", new Object[]{"b", new Integer[]{2, 3}});
    Struct l10 = con.createStruct("nest_leaf", new Object[]{"c", new Integer[]{4}});
    Struct l11 = con.createStruct("nest_leaf", new Object[]{"d", new Integer[]{5, 6}});
    Struct[][] matrix = {{l00, l01}, {l10, l11}};

    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO nest_leaf_matrix_rows (id, matrix) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT matrix FROM nest_leaf_matrix_rows WHERE id = ?")) {
      insert.setInt(1, 1);
      insert.setObject(2, con.createArrayOf("nest_leaf", matrix));
      assertEquals(1, insert.executeUpdate());

      select.setInt(1, 1);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Object[][] back = (Object[][]) rs.getArray(1).getArray();
        assertEquals(2, back.length);
        assertEquals(2, back[0].length);
        assertLeafStruct(back[0][0], "a", new Integer[]{1});
        assertLeafStruct(back[0][1], "b", new Integer[]{2, 3});
        assertLeafStruct(back[1][0], "c", new Integer[]{4});
        assertLeafStruct(back[1][1], "d", new Integer[]{5, 6});
      }
    }
  }

  // ---------- helpers ----------

  private Struct buildOuterStruct(int outerId, NestLeaf... leaves) throws SQLException {
    Object[] leafStructs = new Object[leaves.length];
    for (int i = 0; i < leaves.length; i++) {
      leafStructs[i] = con.createStruct("nest_leaf",
          new Object[]{leaves[i].label, leaves[i].scores});
    }
    Array leavesArray = con.createArrayOf("nest_leaf", leafStructs);
    return con.createStruct("nest_outer", new Object[]{outerId, leavesArray});
  }

  private void insertStructArray(int id, Object payload) throws SQLException {
    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO nest_struct_array_rows (id, payload) VALUES (?, ?)")) {
      insert.setInt(1, id);
      insert.setObject(2, payload);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private void insertArrayStruct(int id, Array items) throws SQLException {
    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO nest_array_struct_rows (id, items) VALUES (?, ?)")) {
      insert.setInt(1, id);
      insert.setArray(2, items);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private static Map<String, Class<?>> nestedTypeMap() {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("nest_outer", NestOuter.class);
    typeMap.put("nest_leaf", NestLeaf.class);
    return typeMap;
  }

  private static void assertLeafStruct(Object actual, String expectedLabel,
                                       Integer[] expectedScores) throws SQLException {
    Struct leafStruct = assertInstanceOf(Struct.class, actual);
    Object[] attrs = leafStruct.getAttributes();
    assertEquals(2, attrs.length);
    assertEquals(expectedLabel, attrs[0]);
    if (expectedScores == null) {
      assertNull(attrs[1]);
    } else {
      Array scores = assertInstanceOf(Array.class, attrs[1]);
      assertArrayEquals(expectedScores, (Integer[]) scores.getArray());
    }
  }

  private static void assertLeaf(NestLeaf actual, String expectedLabel,
                                 Integer[] expectedScores) {
    assertNotNull(actual);
    assertEquals(expectedLabel, actual.label);
    if (expectedScores == null) {
      assertNull(actual.scores);
    } else {
      assertArrayEquals(expectedScores, actual.scores);
    }
  }

  /**
   * Leaf composite type carrying a primitive array field.
   * Used both as a plain SQLData and as the element of {@code nest_leaf[]}.
   */
  public static final class NestLeaf implements SQLData {
    String label;
    Integer[] scores;

    public NestLeaf() {
    }

    NestLeaf(String label, Integer[] scores) {
      this.label = label;
      this.scores = scores;
    }

    @Override
    public String getSQLTypeName() {
      return "nest_leaf";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      label = stream.readString();
      Object rawScores = stream.readObject();
      scores = rawScores == null ? null : (Integer[]) ((Array) rawScores).getArray();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(label);
      stream.writeObject(scores, JDBCType.ARRAY);
    }
  }

  /**
   * Outer composite type carrying a {@code nest_leaf[]} field.
   *
   * <p>On the write side the leaves field is streamed directly as
   * {@code NestLeaf[]} via {@code writeObject(SQLData[], JDBCType.ARRAY)}: the
   * array codec dispatches each element through the registered composite
   * codec, so no {@link Connection#createArrayOf} call from inside
   * {@code writeSQL} is required.</p>
   *
   * <p>On the read side the leaves field still surfaces as a
   * {@link java.sql.Array} of {@link Struct}s. We materialize {@link NestLeaf}
   * instances element-by-element because {@code SQLInput} does not currently
   * propagate the active JDBC type map into the nested {@code PgArray}, so
   * {@code getArray()} without an explicit map cannot produce SQLData
   * elements. (Simplifying this requires propagating the active type map from
   * the calling {@code CodecContext} into the lazy {@code PgArray} — a
   * separate change.)</p>
   */
  public static final class NestOuter implements SQLData {
    int outerId;
    /** Leaves to be written via writeSQL — driven through the codec layer. */
    NestLeaf[] writableLeaves;
    /** Materialized leaves read back via the type map. */
    NestLeaf[] materializedLeaves;

    public NestOuter() {
    }

    NestOuter(int outerId, NestLeaf[] writableLeaves) {
      this.outerId = outerId;
      this.writableLeaves = writableLeaves;
    }

    @Override
    public String getSQLTypeName() {
      return "nest_outer";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      outerId = stream.readInt();
      Object rawLeaves = stream.readObject();
      if (rawLeaves == null) {
        materializedLeaves = null;
        return;
      }
      Object[] elements = (Object[]) ((Array) rawLeaves).getArray();
      NestLeaf[] result = new NestLeaf[elements.length];
      for (int i = 0; i < elements.length; i++) {
        Object element = elements[i];
        if (element == null) {
          result[i] = null;
          continue;
        }
        Struct leafStruct = (Struct) element;
        Object[] attrs = leafStruct.getAttributes();
        NestLeaf leaf = new NestLeaf();
        leaf.label = (String) attrs[0];
        Object rawScores = attrs[1];
        if (rawScores == null) {
          leaf.scores = null;
        } else {
          leaf.scores = (Integer[]) ((Array) rawScores).getArray();
        }
        result[i] = leaf;
      }
      materializedLeaves = result;
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(outerId);
      stream.writeObject(writableLeaves, JDBCType.ARRAY);
    }
  }
}
