/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLData;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests composite type handling in both binary and text formats.
 *
 * <p>This parameterized test ensures consistent behavior for composite types
 * regardless of the wire format used.</p>
 */
@ParameterizedClass
@MethodSource("data")
public class CompositeFormatTest extends BaseTest4 {

  public CompositeFormatTest(BinaryMode binaryMode) {
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
  public static void createTestTypes() throws SQLException {
    try (Connection conn = TestUtil.openDB();
         Statement stmt = conn.createStatement()) {
      cleanup(stmt);
      stmt.execute("CREATE TYPE comp_format_point AS (x int, y int)");
      stmt.execute("CREATE TYPE comp_format_rect AS (top_left comp_format_point, bottom_right comp_format_point)");
      stmt.execute("CREATE TABLE comp_format_test (id serial PRIMARY KEY, pt comp_format_point, rect comp_format_rect)");
      stmt.execute("INSERT INTO comp_format_test (pt, rect) VALUES ("
          + "ROW(10, 20)::comp_format_point, "
          + "ROW(ROW(0,0)::comp_format_point, ROW(100,100)::comp_format_point)::comp_format_rect)");
    }
  }

  @AfterAll
  public static void dropTestTypes() throws SQLException {
    try (Connection conn = TestUtil.openDB();
         Statement stmt = conn.createStatement()) {
      cleanup(stmt);
    }
  }

  private static void cleanup(Statement stmt) throws SQLException {
    stmt.execute("DROP TABLE IF EXISTS comp_format_test CASCADE");
    stmt.execute("DROP TYPE IF EXISTS comp_format_rect CASCADE");
    stmt.execute("DROP TYPE IF EXISTS comp_format_point CASCADE");
  }

  @Test
  void simpleComposite_asStruct() throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT pt FROM comp_format_test WHERE id = 1")) {
      assertTrue(rs.next());
      Struct struct = rs.getObject(1, Struct.class);
      assertNotNull(struct, "struct should not be null for " + binaryMode);
      Object[] attrs = struct.getAttributes();
      assertEquals(2, attrs.length);
      assertEquals(10, attrs[0]);
      assertEquals(20, attrs[1]);
    }
  }

  @Test
  void simpleComposite_asSQLData() throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("comp_format_point", Point.class);
    con.setTypeMap(typeMap);
    try {
      try (Statement stmt = con.createStatement();
           ResultSet rs = stmt.executeQuery("SELECT pt FROM comp_format_test WHERE id = 1")) {
        assertTrue(rs.next());
        Point pt = (Point) rs.getObject(1);
        assertNotNull(pt, "point should not be null for " + binaryMode);
        assertEquals(10, pt.x);
        assertEquals(20, pt.y);
      }
    } finally {
      con.setTypeMap(new HashMap<>());
    }
  }

  @Test
  void nestedComposite_asStruct() throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT rect FROM comp_format_test WHERE id = 1")) {
      assertTrue(rs.next());
      Struct rect = rs.getObject(1, Struct.class);
      assertNotNull(rect, "rect should not be null for " + binaryMode);
      Object[] attrs = rect.getAttributes();
      assertEquals(2, attrs.length);

      // Both nested attributes should be Structs
      Struct topLeft = (Struct) attrs[0];
      Struct bottomRight = (Struct) attrs[1];

      Object[] tlAttrs = topLeft.getAttributes();
      assertEquals(0, tlAttrs[0]);
      assertEquals(0, tlAttrs[1]);

      Object[] brAttrs = bottomRight.getAttributes();
      assertEquals(100, brAttrs[0]);
      assertEquals(100, brAttrs[1]);
    }
  }

  @Test
  void nestedComposite_asSQLData() throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("comp_format_point", Point.class);
    typeMap.put("comp_format_rect", Rect.class);
    con.setTypeMap(typeMap);
    try {
      try (Statement stmt = con.createStatement();
           ResultSet rs = stmt.executeQuery("SELECT rect FROM comp_format_test WHERE id = 1")) {
        assertTrue(rs.next());
        Rect rect = (Rect) rs.getObject(1);
        assertNotNull(rect, "rect should not be null for " + binaryMode);
        assertNotNull(rect.topLeft);
        assertNotNull(rect.bottomRight);
        assertEquals(0, rect.topLeft.x);
        assertEquals(0, rect.topLeft.y);
        assertEquals(100, rect.bottomRight.x);
        assertEquals(100, rect.bottomRight.y);
      }
    } finally {
      con.setTypeMap(new HashMap<>());
    }
  }

  @Test
  void compositeRoundtrip() throws SQLException {
    // Insert and retrieve a composite value
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO comp_format_test (pt) VALUES (ROW(?, ?)::comp_format_point) RETURNING pt")) {
      ps.setInt(1, 42);
      ps.setInt(2, 84);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        Struct struct = rs.getObject(1, Struct.class);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(42, attrs[0]);
        assertEquals(84, attrs[1]);
      }
    }
  }

  // ==================== Test SQLData Implementations ====================

  public static class Point implements SQLData {
    public int x;
    public int y;
    private String typeName;

    public Point() {
    }

    @Override
    public String getSQLTypeName() {
      return typeName != null ? typeName : "comp_format_point";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.typeName = typeName;
      this.x = stream.readInt();
      this.y = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(x);
      stream.writeInt(y);
    }
  }

  public static class Rect implements SQLData {
    public Point topLeft;
    public Point bottomRight;
    private String typeName;

    public Rect() {
    }

    @Override
    public String getSQLTypeName() {
      return typeName != null ? typeName : "comp_format_rect";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.typeName = typeName;
      Object tl = stream.readObject();
      if (tl instanceof Point) {
        this.topLeft = (Point) tl;
      }
      Object br = stream.readObject();
      if (br instanceof Point) {
        this.bottomRight = (Point) br;
      }
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeObject(topLeft);
      stream.writeObject(bottomRight);
    }
  }
}
