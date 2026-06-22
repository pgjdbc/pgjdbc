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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Exercises a user-supplied {@link SQLData} whose composite type has a nested
 * struct field and primitive array fields. Demonstrates the round-trip patterns
 * supported by the current {@link SQLInput}/{@link SQLOutput} surface without
 * exposing {@link Connection#createStruct(String, Object[])} /
 * {@link Connection#createArrayOf(String, Object[])} to user code.
 *
 * <p>Recommended patterns for SQLData implementations:</p>
 * <ul>
 *   <li>Nested composite field — {@code stream.writeObject(nestedSqlData)} and
 *       {@code (NestedType) stream.readObject()}.</li>
 *   <li>Primitive/object array field (e.g. {@code int4[]}, {@code text[]}) —
 *       {@code stream.writeObject(javaArray, JDBCType.ARRAY)} and
 *       {@code (Array) stream.readObject()} (or {@code stream.readArray()} once
 *       implemented).</li>
 * </ul>
 *
 * <p>Building a composite-element array (e.g. {@code my_struct[]}) inside
 * {@code writeSQL} still requires access to {@link Connection#createArrayOf}.
 * Such a case is intentionally not covered here — the SQLData would have to
 * receive the {@code Array} from outside (typically constructed by the caller).</p>
 */
@Execution(ExecutionMode.SAME_THREAD)
@ParameterizedClass
@MethodSource("data")
public class SQLDataNestedStructArrayTest extends BaseTest4 {

  SQLDataNestedStructArrayTest(BinaryMode binaryMode) {
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
      stmt.execute("CREATE TYPE sqldata_tag AS (label text, weight int)");
      stmt.execute("CREATE TYPE sqldata_box AS ("
          + "  box_id int,"
          + "  tag_codes int[],"
          + "  notes text[],"
          + "  primary_tag sqldata_tag"
          + ")");
      stmt.execute("CREATE TABLE sqldata_box_rows (id int primary key, payload sqldata_box)");
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
    stmt.execute("DROP TABLE IF EXISTS sqldata_box_rows");
    stmt.execute("DROP TYPE IF EXISTS sqldata_box CASCADE");
    stmt.execute("DROP TYPE IF EXISTS sqldata_tag CASCADE");
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    try (Statement stmt = con.createStatement()) {
      stmt.execute("TRUNCATE TABLE sqldata_box_rows");
    }
  }

  @Test
  void writeSQLData_andReadBack_withNestedStructAndPrimitiveArrays() throws SQLException {
    Tag primary = new Tag("alpha", 7);
    Box input = new Box(1, new Integer[]{10, 20, 30}, new String[]{"a", "b"}, primary);

    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO sqldata_box_rows (id, payload) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT payload FROM sqldata_box_rows WHERE id = ?")) {
      insert.setInt(1, 1);
      insert.setObject(2, input);
      assertEquals(1, insert.executeUpdate());

      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put("sqldata_box", Box.class);
      typeMap.put("sqldata_tag", Tag.class);

      select.setInt(1, 1);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Box actual = (Box) rs.getObject(1, typeMap);
        assertNotNull(actual);
        assertEquals(1, actual.boxId);
        assertArrayEquals(new Integer[]{10, 20, 30}, actual.tagCodes);
        assertArrayEquals(new String[]{"a", "b"}, actual.notes);
        assertNotNull(actual.primaryTag);
        assertEquals("alpha", actual.primaryTag.label);
        assertEquals(7, actual.primaryTag.weight);
      }
    }
  }

  @Test
  void writeSQLData_andReadBack_withNullNestedStructAndNullArrays() throws SQLException {
    Box input = new Box(2, null, null, null);

    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO sqldata_box_rows (id, payload) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT payload FROM sqldata_box_rows WHERE id = ?")) {
      insert.setInt(1, 2);
      insert.setObject(2, input);
      assertEquals(1, insert.executeUpdate());

      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put("sqldata_box", Box.class);
      typeMap.put("sqldata_tag", Tag.class);

      select.setInt(1, 2);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Box actual = (Box) rs.getObject(1, typeMap);
        assertNotNull(actual);
        assertEquals(2, actual.boxId);
        assertNull(actual.tagCodes);
        assertNull(actual.notes);
        assertNull(actual.primaryTag);
      }
    }
  }

  @Test
  void readBackAsStruct_seesArraysAndNestedStructAsAttributes() throws SQLException {
    Tag primary = new Tag("beta", 99);
    Box input = new Box(3, new Integer[]{1, 2}, new String[]{"x"}, primary);

    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO sqldata_box_rows (id, payload) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT payload FROM sqldata_box_rows WHERE id = ?")) {
      insert.setInt(1, 3);
      insert.setObject(2, input);
      assertEquals(1, insert.executeUpdate());

      select.setInt(1, 3);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        java.sql.Struct struct = rs.getObject(1, java.sql.Struct.class);
        Object[] attrs = struct.getAttributes();
        assertEquals(4, attrs.length);
        assertEquals(3, attrs[0]);
        Array tagCodes = assertInstanceOf(Array.class, attrs[1]);
        assertArrayEquals(new Integer[]{1, 2}, (Integer[]) tagCodes.getArray());
        Array notes = assertInstanceOf(Array.class, attrs[2]);
        assertArrayEquals(new String[]{"x"}, (String[]) notes.getArray());
        java.sql.Struct nestedTag = assertInstanceOf(java.sql.Struct.class, attrs[3]);
        assertArrayEquals(new Object[]{"beta", 99}, nestedTag.getAttributes());
      }
    }
  }

  /**
   * Simple leaf composite that uses only primitive readers/writers.
   */
  public static final class Tag implements SQLData {
    String label;
    int weight;

    public Tag() {
    }

    Tag(String label, int weight) {
      this.label = label;
      this.weight = weight;
    }

    @Override
    public String getSQLTypeName() {
      return "sqldata_tag";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      label = stream.readString();
      weight = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(label);
      stream.writeInt(weight);
    }
  }

  /**
   * Composite with a nested struct field and two primitive array fields.
   *
   * <p>Demonstrates that a user SQLData can serialize / deserialize nested
   * structs and primitive arrays without ever calling
   * {@code Connection.createStruct} / {@code createArrayOf} from inside
   * {@code writeSQL}.</p>
   */
  public static final class Box implements SQLData {
    int boxId;
    Integer[] tagCodes;
    String[] notes;
    Tag primaryTag;

    public Box() {
    }

    Box(int boxId, Integer[] tagCodes, String[] notes, Tag primaryTag) {
      this.boxId = boxId;
      this.tagCodes = tagCodes;
      this.notes = notes;
      this.primaryTag = primaryTag;
    }

    @Override
    public String getSQLTypeName() {
      return "sqldata_box";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      boxId = stream.readInt();
      // Composite array fields surface as Array via readObject(); cast is the
      // current public path (readArray() on SQLInput is not yet implemented).
      Object rawCodes = stream.readObject();
      tagCodes = rawCodes == null ? null : (Integer[]) ((Array) rawCodes).getArray();
      Object rawNotes = stream.readObject();
      notes = rawNotes == null ? null : (String[]) ((Array) rawNotes).getArray();
      // Nested composite returns the mapped SQLData instance directly when
      // the type map has been configured.
      primaryTag = (Tag) stream.readObject();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(boxId);
      // Pass raw Java arrays via writeObject(Object, SQLType) — the field
      // codec (ArrayCodec) handles Object[] / Integer[] / String[] directly,
      // so no Connection.createArrayOf is needed inside writeSQL.
      stream.writeObject(tagCodes, JDBCType.ARRAY);
      stream.writeObject(notes, JDBCType.ARRAY);
      // Nested SQLData goes through writeObject(SQLData); the composite
      // codec recursively materializes a PgSQLOutput for the nested type.
      stream.writeObject(primaryTag);
    }
  }
}
