/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.jdbc.CodecDepth;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;

/**
 * Integration tests for codec nesting depth protection.
 *
 * <p>Tests verify that the CodecDepth limit prevents infinite recursion
 * when decoding deeply nested composite types.</p>
 *
 * <p>The maximum nesting depth is 64 levels, which is more than sufficient
 * for any legitimate use case while protecting against circular references
 * or pathological nesting.</p>
 */
public class NestingDepthTest {

  private static Connection conn;

  // Create 10 levels of nested types for testing
  private static final int TEST_NESTING_DEPTH = 10;

  @BeforeAll
  public static void setUp() throws Exception {
    conn = TestUtil.openDB();
    createNestedTypes();
  }

  @AfterAll
  public static void tearDown() throws Exception {
    if (conn != null) {
      dropNestedTypes();
      conn.close();
    }
  }

  @BeforeEach
  void resetDepth() {
    CodecDepth.clear();
  }

  @AfterEach
  void clearDepth() {
    CodecDepth.clear();
  }

  /**
   * Creates a chain of nested composite types:
   * nest_level_0 contains nest_level_1 contains nest_level_2 ... etc.
   */
  private static void createNestedTypes() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      // Drop types in reverse order (innermost first)
      for (int i = 0; i <= TEST_NESTING_DEPTH; i++) {
        stmt.execute("DROP TYPE IF EXISTS nest_level_" + i + " CASCADE");
      }

      // Create innermost type (leaf)
      stmt.execute("CREATE TYPE nest_level_" + TEST_NESTING_DEPTH
          + " AS (value int, name text)");

      // Create wrapper types from inside out
      for (int i = TEST_NESTING_DEPTH - 1; i >= 0; i--) {
        stmt.execute("CREATE TYPE nest_level_" + i
            + " AS (level int, nested nest_level_" + (i + 1) + ")");
      }
    }
  }

  private static void dropNestedTypes() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      for (int i = 0; i <= TEST_NESTING_DEPTH; i++) {
        stmt.execute("DROP TYPE IF EXISTS nest_level_" + i + " CASCADE");
      }
    }
  }

  // ==================== Success Case Tests ====================

  @Test
  void moderateNesting_succeeds() throws SQLException {
    // Build nested composite value: nest_level_0(0, nest_level_1(1, nest_level_2(2, ...)))
    StringBuilder sql = new StringBuilder("SELECT ");
    sql.append("ROW(0, ");

    for (int i = 1; i < TEST_NESTING_DEPTH; i++) {
      sql.append("ROW(").append(i).append(", ");
    }

    // Innermost value
    sql.append("ROW(42, 'leaf')::nest_level_").append(TEST_NESTING_DEPTH);

    // Close all ROWs with type casts
    for (int i = TEST_NESTING_DEPTH - 1; i >= 0; i--) {
      sql.append(")::nest_level_").append(i);
    }

    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(sql.toString())) {
      rs.next();
      Struct outermost = (Struct) rs.getObject(1);
      assertNotNull(outermost);

      // Navigate to innermost and verify
      Struct current = outermost;
      for (int i = 0; i < TEST_NESTING_DEPTH; i++) {
        Object[] attrs = current.getAttributes();
        assertEquals(i, attrs[0], "Level mismatch at depth " + i);
        if (i < TEST_NESTING_DEPTH - 1) {
          current = (Struct) attrs[1];
          assertNotNull(current, "Nested struct should not be null at level " + i);
        }
      }

      // Verify innermost
      Object[] leafAttrs = current.getAttributes();
      Struct leaf = (Struct) leafAttrs[1];
      Object[] innermost = leaf.getAttributes();
      assertEquals(42, innermost[0]);
      assertEquals("leaf", innermost[1]);
    }
  }

  @Test
  void depthCounterResetAfterSuccess() throws SQLException {
    // Run a query that involves nesting
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT ROW(1, ROW(2, 'inner')::nest_level_"
                 + TEST_NESTING_DEPTH + ")::nest_level_" + (TEST_NESTING_DEPTH - 1))) {
      rs.next();
      rs.getObject(1);
    }

    // Verify depth is reset after successful operation
    assertEquals(0, CodecDepth.current(),
        "CodecDepth should be 0 after successful decode");
  }

  // ==================== Unit-Level Depth Limit Test ====================

  @Test
  void exceedingMaxDepth_throwsException() {
    // This tests the CodecDepth limit directly (unit test style)
    // since creating 65 levels of PostgreSQL types is impractical

    // Enter up to max depth
    for (int i = 0; i < 64; i++) {
      try {
        CodecDepth.enter();
      } catch (SQLException e) {
        throw new AssertionError("Should not throw before depth 64", e);
      }
    }

    // The 65th enter should throw
    PSQLException ex = assertThrows(PSQLException.class, CodecDepth::enter);
    assertEquals("Maximum type nesting depth exceeded: 64", ex.getMessage());
  }

  // ==================== Cleanup Verification ====================

  @Test
  void depthCleanedUpOnError() throws SQLException {
    // Simulate an error during decoding and verify cleanup
    CodecDepth.enter();
    CodecDepth.enter();
    assertEquals(2, CodecDepth.current());

    // Simulate error recovery with try-finally pattern
    try {
      CodecDepth.enter();
      // Simulate some operation
      throw new RuntimeException("Simulated error");
    } catch (RuntimeException e) {
      // Expected
    } finally {
      CodecDepth.exit();
    }

    assertEquals(2, CodecDepth.current());
    CodecDepth.clear();
    assertEquals(0, CodecDepth.current());
  }

  @Test
  @Disabled("Array.getArray() routes composite elements through the legacy "
      + "ArrayDecoding path which still returns PGobject; routing composite "
      + "elements through CompositeCodec is a follow-up.")
  void arrayWithNestedComposites_handlesDepth() throws SQLException {
    // Test that arrays of composite types also respect depth limits
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS nest_array_test");
      stmt.execute("CREATE TABLE nest_array_test (id int, items nest_level_"
          + TEST_NESTING_DEPTH + "[])");
      stmt.execute("INSERT INTO nest_array_test VALUES (1, ARRAY[ROW(10, 'a'), ROW(20, 'b')]::nest_level_"
          + TEST_NESTING_DEPTH + "[])");

      try (ResultSet rs = stmt.executeQuery("SELECT items FROM nest_array_test")) {
        rs.next();
        Object array = rs.getArray(1).getArray();
        assertNotNull(array);

        Object[] items = (Object[]) array;
        assertEquals(2, items.length);

        Struct first = (Struct) items[0];
        Object[] firstAttrs = first.getAttributes();
        assertEquals(10, firstAttrs[0]);
        assertEquals("a", firstAttrs[1]);
      }
    } finally {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS nest_array_test");
      }
    }

    // Verify depth reset
    assertEquals(0, CodecDepth.current());
  }
}
