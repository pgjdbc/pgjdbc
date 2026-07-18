/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.util.List;

/**
 * Integration tests for TypeInfoCache invalidation on DDL commands.
 *
 * <p>These tests verify that the type cache is properly invalidated when
 * types are created, dropped, or altered, ensuring that subsequent queries
 * see the updated type metadata.</p>
 */
public class CacheInvalidationTest {

  private static Connection conn;

  @BeforeAll
  public static void setUp() throws Exception {
    conn = TestUtil.openDB();
    cleanup();
  }

  @AfterAll
  public static void tearDown() throws Exception {
    if (conn != null) {
      cleanup();
      conn.close();
    }
  }

  private static void cleanup() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS cache_test_table CASCADE");
      stmt.execute("DROP TYPE IF EXISTS cache_test_type CASCADE");
      stmt.execute("DROP TYPE IF EXISTS cache_test_v2 CASCADE");
    }
  }

  // ==================== CREATE TYPE Tests ====================

  @Test
  void createType_newTypeIsImmediatelyUsable() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      // Create a new type
      stmt.execute("CREATE TYPE cache_test_type AS (id int, name text)");

      // Use it immediately in same connection
      try (ResultSet rs = stmt.executeQuery(
          "SELECT ROW(1, 'test')::cache_test_type")) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(1, attrs[0]);
        assertEquals("test", attrs[1]);
      }
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TYPE IF EXISTS cache_test_type CASCADE");
      }
    }
  }

  @Test
  void createType_cachesTypeMetadata() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TYPE cache_test_type AS (id int, name text)");

      // First query - populates cache
      try (ResultSet rs = stmt.executeQuery(
          "SELECT ROW(1, 'first')::cache_test_type")) {
        rs.next();
        rs.getObject(1);
      }

      // Second query - should use cached metadata
      try (ResultSet rs = stmt.executeQuery(
          "SELECT ROW(2, 'second')::cache_test_type")) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        Object[] attrs = struct.getAttributes();
        assertEquals(2, attrs[0]);
        assertEquals("second", attrs[1]);
      }
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TYPE IF EXISTS cache_test_type CASCADE");
      }
    }
  }

  // ==================== DROP TYPE Tests ====================

  @Test
  void dropType_cacheInvalidated() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      // Create and use a type
      stmt.execute("CREATE TYPE cache_test_type AS (id int, name text)");

      try (ResultSet rs = stmt.executeQuery(
          "SELECT ROW(1, 'test')::cache_test_type")) {
        rs.next();
        rs.getObject(1);
      }

      // Drop the type
      stmt.execute("DROP TYPE cache_test_type");

      // Attempt to use dropped type should fail
      assertThrows(PSQLException.class, () -> {
        try (ResultSet rs = stmt.executeQuery(
            "SELECT ROW(1, 'test')::cache_test_type")) {
          rs.next();
          rs.getObject(1);
        }
      });
    }
  }

  // ==================== DROP and RECREATE Tests ====================

  @Test
  void dropAndRecreate_newStructureUsed() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      // Create original type with 2 fields
      stmt.execute("CREATE TYPE cache_test_type AS (id int, name text)");

      // Use it and populate cache
      try (ResultSet rs = stmt.executeQuery(
          "SELECT ROW(1, 'original')::cache_test_type")) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        Object[] attrs = struct.getAttributes();
        assertEquals(2, attrs.length);
        assertEquals(1, attrs[0]);
        assertEquals("original", attrs[1]);
      }

      // Drop and recreate with different structure (3 fields)
      stmt.execute("DROP TYPE cache_test_type");
      stmt.execute("CREATE TYPE cache_test_type AS (id int, name text, extra boolean)");

      // Query again - should see new structure
      try (ResultSet rs = stmt.executeQuery(
          "SELECT ROW(2, 'recreated', true)::cache_test_type")) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        Object[] attrs = struct.getAttributes();
        assertEquals(3, attrs.length, "New type should have 3 fields");
        assertEquals(2, attrs[0]);
        assertEquals("recreated", attrs[1]);
        assertEquals(true, attrs[2]);
      }
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TYPE IF EXISTS cache_test_type CASCADE");
      }
    }
  }

  @Test
  void dropAndRecreate_differentFieldTypes() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      // Create type with int field
      stmt.execute("CREATE TYPE cache_test_type AS (value int)");

      try (ResultSet rs = stmt.executeQuery(
          "SELECT ROW(42)::cache_test_type")) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        Object[] attrs = struct.getAttributes();
        assertEquals(42, attrs[0]);
      }

      // Recreate with text field instead
      stmt.execute("DROP TYPE cache_test_type");
      stmt.execute("CREATE TYPE cache_test_type AS (value text)");

      try (ResultSet rs = stmt.executeQuery(
          "SELECT ROW('hello')::cache_test_type")) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        Object[] attrs = struct.getAttributes();
        assertEquals("hello", attrs[0]);
      }
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TYPE IF EXISTS cache_test_type CASCADE");
      }
    }
  }

  // ==================== Table with Composite Column Tests ====================

  @Test
  void tableWithCompositeColumn_cacheHandlesCorrectly() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      // Create type and table
      stmt.execute("CREATE TYPE cache_test_type AS (id int, name text)");
      stmt.execute("CREATE TABLE cache_test_table (pk int, data cache_test_type)");
      stmt.execute("INSERT INTO cache_test_table VALUES (1, ROW(10, 'row1'))");
      stmt.execute("INSERT INTO cache_test_table VALUES (2, ROW(20, 'row2'))");

      // Query and verify caching works
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT data FROM cache_test_table WHERE pk = ?")) {
        ps.setInt(1, 1);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          Struct struct = (Struct) rs.getObject(1);
          Object[] attrs = struct.getAttributes();
          assertEquals(10, attrs[0]);
          assertEquals("row1", attrs[1]);
        }

        ps.setInt(1, 2);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          Struct struct = (Struct) rs.getObject(1);
          Object[] attrs = struct.getAttributes();
          assertEquals(20, attrs[0]);
          assertEquals("row2", attrs[1]);
        }
      }
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS cache_test_table CASCADE");
        stmt.execute("DROP TYPE IF EXISTS cache_test_type CASCADE");
      }
    }
  }

  // ==================== createStruct Cache Tests ====================

  @Test
  void createStruct_usesCorrectTypeInfo() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TYPE cache_test_type AS (id int, name text)");

      // Create struct programmatically
      Struct struct = conn.createStruct("cache_test_type", new Object[]{99, "created"});
      assertNotNull(struct);

      // Use it in a query
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT (?::cache_test_type).id, (?::cache_test_type).name")) {
        ps.setObject(1, struct);
        ps.setObject(2, struct);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertEquals(99, rs.getInt(1));
          assertEquals("created", rs.getString(2));
        }
      }
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TYPE IF EXISTS cache_test_type CASCADE");
      }
    }
  }

  @Test
  void createStruct_afterRecreate_usesNewStructure() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      // Create original type
      stmt.execute("CREATE TYPE cache_test_type AS (id int)");

      // Create struct with original type
      Struct struct1 = conn.createStruct("cache_test_type", new Object[]{1});
      assertEquals("cache_test_type", struct1.getSQLTypeName());

      // Drop and recreate with different structure
      stmt.execute("DROP TYPE cache_test_type");
      stmt.execute("CREATE TYPE cache_test_type AS (id int, name text)");

      // Create struct with new type structure
      Struct struct2 = conn.createStruct("cache_test_type", new Object[]{2, "new"});
      Object[] attrs = struct2.getAttributes();
      assertEquals(2, attrs.length, "New struct should have 2 fields");
      assertEquals(2, attrs[0]);
      assertEquals("new", attrs[1]);
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TYPE IF EXISTS cache_test_type CASCADE");
      }
    }
  }

  // ==================== SET search_path invalidation =====================

  /**
   * Changing the {@code search_path} between two schemas that hold a type
   * with the same name must invalidate the per-connection name->type cache.
   * Otherwise the second {@code getPgTypeByPgName} returns the OID resolved
   * before the {@code SET} and the caller acts on stale metadata.
   */
  @Test
  void setSearchPath_typeCacheInvalidated() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE SCHEMA cache_test_schema_a");
      stmt.execute("CREATE SCHEMA cache_test_schema_b");
      stmt.execute("CREATE TYPE cache_test_schema_a.cache_test_searchpath AS (id int)");
      stmt.execute("CREATE TYPE cache_test_schema_b.cache_test_searchpath AS (name text)");

      org.postgresql.core.TypeInfo typeInfo =
          conn.unwrap(org.postgresql.core.BaseConnection.class).getTypeInfo();

      stmt.execute("SET search_path TO cache_test_schema_a");
      int oidA = typeInfo.getPgTypeByPgName("cache_test_searchpath").getOid();
      List<org.postgresql.jdbc.PgField> fieldsA =
          typeInfo.getFields(oidA);
      assertEquals(1, fieldsA.size(), "schema_a type has one int field");
      assertEquals("id", fieldsA.get(0).getName());

      stmt.execute("SET search_path TO cache_test_schema_b");
      int oidB = typeInfo.getPgTypeByPgName("cache_test_searchpath").getOid();
      // The cache must be invalidated so the bare typename resolves to the
      // type now visible via search_path, not the one cached previously.
      assertEquals(false, oidA == oidB,
          "After SET search_path, getPgTypeByPgName must resolve to the new schema's type");
      List<org.postgresql.jdbc.PgField> fieldsB =
          typeInfo.getFields(oidB);
      assertEquals(1, fieldsB.size(), "schema_b type has one text field");
      assertEquals("name", fieldsB.get(0).getName());
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("RESET search_path");
        stmt.execute("DROP SCHEMA IF EXISTS cache_test_schema_a CASCADE");
        stmt.execute("DROP SCHEMA IF EXISTS cache_test_schema_b CASCADE");
      }
    }
  }

  // ==================== ALTER TABLE on row-type Tests ====================

  /**
   * Every table has an implicit composite row type. After ALTER TABLE
   * drops and re-adds a column with a different type, the row type's
   * field list changes — the driver's per-OID composite-fields cache
   * must invalidate so a subsequent {@code SELECT t FROM t} returns
   * attributes matching the new column types.
   */
  @Test
  void alterTable_rowTypeFieldsRefreshed() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      // Initial schema: (id int, name varchar)
      stmt.execute("CREATE TABLE cache_test_table (id int, name varchar)");
      stmt.execute("INSERT INTO cache_test_table VALUES (1, 'hello')");

      // First read populates the type cache for the table's row type.
      try (ResultSet rs = stmt.executeQuery(
          "SELECT t FROM cache_test_table t")) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(2, attrs.length, "Initial row type has 2 fields");
        assertEquals(1, attrs[0]);
        assertEquals("hello", attrs[1]);
      }

      // Replace the second column with a different-typed column of the
      // same name. The implicit row type changes; pgjdbc's prior cache
      // must not be reused.
      stmt.execute("ALTER TABLE cache_test_table DROP COLUMN name");
      stmt.execute("ALTER TABLE cache_test_table ADD COLUMN name int4");
      stmt.execute("UPDATE cache_test_table SET name = 42 WHERE id = 1");

      try (ResultSet rs = stmt.executeQuery(
          "SELECT t FROM cache_test_table t")) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertNotNull(struct);
        Object[] attrs = struct.getAttributes();
        assertEquals(2, attrs.length, "Row type still has 2 fields after ALTER");
        assertEquals(1, attrs[0]);
        // The cache invalidation must surface the new int4 type; reading
        // the old varchar metadata would either return "42" as a String
        // or throw a decode error.
        assertEquals(42, attrs[1], "Second attribute is int4 after ALTER");
      }
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS cache_test_table CASCADE");
      }
    }
  }

  /**
   * Same scenario as {@link #alterTable_rowTypeFieldsRefreshed} but the
   * column count grows: ALTER TABLE adds a new column. Tests that the
   * cached field list isn't truncated to the old arity.
   */
  @Test
  void alterTable_addColumn_rowTypeReflectsNewArity() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE cache_test_table (id int, name varchar)");
      stmt.execute("INSERT INTO cache_test_table VALUES (1, 'one')");

      try (ResultSet rs = stmt.executeQuery(
          "SELECT t FROM cache_test_table t")) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        assertEquals(2, struct.getAttributes().length);
      }

      stmt.execute("ALTER TABLE cache_test_table ADD COLUMN extra boolean DEFAULT true");

      try (ResultSet rs = stmt.executeQuery(
          "SELECT t FROM cache_test_table t")) {
        rs.next();
        Struct struct = (Struct) rs.getObject(1);
        Object[] attrs = struct.getAttributes();
        assertEquals(3, attrs.length, "Row type grew to 3 fields after ADD COLUMN");
        assertEquals(1, attrs[0]);
        assertEquals("one", attrs[1]);
        assertEquals(true, attrs[2]);
      }
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS cache_test_table CASCADE");
      }
    }
  }

  // ==================== Multiple Connection Tests ====================

  @Test
  void separateConnections_independentCaches() throws SQLException {
    Connection conn2 = null;
    try {
      conn2 = TestUtil.openDB();

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TYPE cache_test_type AS (id int, name text)");
      }

      // Use type on both connections
      try (Statement stmt1 = conn.createStatement();
           Statement stmt2 = conn2.createStatement()) {

        try (ResultSet rs = stmt1.executeQuery(
            "SELECT ROW(1, 'conn1')::cache_test_type")) {
          rs.next();
          Struct s = (Struct) rs.getObject(1);
          assertEquals("conn1", s.getAttributes()[1]);
        }

        try (ResultSet rs = stmt2.executeQuery(
            "SELECT ROW(2, 'conn2')::cache_test_type")) {
          rs.next();
          Struct s = (Struct) rs.getObject(1);
          assertEquals("conn2", s.getAttributes()[1]);
        }
      }
    } finally {
      if (conn2 != null) {
        conn2.close();
      }
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TYPE IF EXISTS cache_test_type CASCADE");
      }
    }
  }
}
