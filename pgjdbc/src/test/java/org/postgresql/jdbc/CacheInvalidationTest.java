/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.BaseConnection;
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
