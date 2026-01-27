/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

/**
 * Tests for GitHub issue #3910: cleanupSavepoints=true causes "Unknown Response Type C."
 * error when used with Large Object operations (fastpath).
 *
 * <p>The bug occurs because {@code releaseSavePoint()} in {@code QueryExecutorImpl} sends
 * the RELEASE SAVEPOINT command but doesn't wait for the response. The CommandComplete ('C')
 * response is left in the buffer. When a fastpath operation (used by Large Objects) executes
 * next, {@code receiveFastpathResult()} receives this unexpected 'C' message and throws
 * "Unknown Response Type C."</p>
 *
 * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/3910">Issue #3910</a>
 */
@ParameterizedClass
@MethodSource("data")
class CleanupSavepointsWithFastpathTest extends BaseTest4 {
  CleanupSavepointsWithFastpathTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  static Iterable<Arguments> data() {
    Collection<Arguments> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(arguments(binaryMode));
    }
    return ids;
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.AUTOSAVE.set(props, "always");
    PGProperty.CLEANUP_SAVEPOINTS.set(props, true);
  }

  /**
   * Tests that Large Object operations work correctly when cleanupSavepoints=true
   * and autosave=always are both enabled.
   *
   * <p>This reproduces the bug from issue #3910 where the combination of these
   * settings causes fastpath operations to fail with "Unknown Response Type C."</p>
   */
  @Test
  void testLargeObjectWithCleanupSavepoints() throws Exception {
    con.setAutoCommit(false);

    // Execute a query to trigger autosave mechanism
    // This will set a savepoint before the query and release it after (due to cleanupSavepoints)
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT 1");
    }

    // Now try Large Object operations - these use fastpath protocol
    // The bug: the RELEASE SAVEPOINT response ('C') is still in the buffer
    // and receiveFastpathResult() will read it instead of the expected response
    LargeObjectManager lom = con.unwrap(PGConnection.class).getLargeObjectAPI();

    // This should NOT throw "Unknown Response Type C."
    long oid = lom.createLO();
    try {
      try (LargeObject lo = lom.open(oid)) {
        byte[] data = "Test data for issue #3910".getBytes(StandardCharsets.UTF_8);
        lo.write(data);
        lo.seek(0);
        byte[] readBack = lo.read(data.length);
        assertArrayEquals(data, readBack,
            "Large object data should be readable after write");
      }
    } finally {
      lom.delete(oid);
    }
  }

  /**
   * Tests that a sequence of queries followed by Large Object operations works correctly.
   * This tests multiple rounds of savepoint creation/cleanup followed by fastpath.
   */
  @Test
  void testMultipleQueriesThenLargeObject() throws Exception {
    con.setAutoCommit(false);

    // Execute multiple queries - each will trigger savepoint creation and cleanup
    try (Statement stmt = con.createStatement()) {
      for (int i = 0; i < 5; i++) {
        stmt.execute("SELECT " + i);
      }
    }

    // Now try Large Object operations
    LargeObjectManager lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
    long oid = lom.createLO();
    try {
      try (LargeObject lo = lom.open(oid)) {
        lo.write(new byte[]{1, 2, 3, 4, 5});
      }
    } finally {
      lom.delete(oid);
    }
  }

  /**
   * Tests interleaving of regular queries and Large Object operations.
   */
  @Test
  void testInterleavedQueriesAndLargeObjects() throws Exception {
    con.setAutoCommit(false);

    LargeObjectManager lom = con.unwrap(PGConnection.class).getLargeObjectAPI();

    for (int i = 0; i < 3; i++) {
      // Execute a query (triggers savepoint cleanup)
      try (Statement stmt = con.createStatement();
           ResultSet rs = stmt.executeQuery("SELECT " + i)) {
        rs.next();
      }

      // Then do Large Object operation (uses fastpath)
      long oid = lom.createLO();
      try {
        try (LargeObject lo = lom.open(oid)) {
          lo.write(("Iteration " + i).getBytes(StandardCharsets.UTF_8));
        }
      } finally {
        lom.delete(oid);
      }
    }
  }

  /**
   * Tests PreparedStatement execution followed by Large Object operations.
   * PreparedStatements may use different code paths for savepoint handling.
   */
  @Test
  void testPreparedStatementThenLargeObject() throws Exception {
    con.setAutoCommit(false);

    // Use PreparedStatement to trigger server-side prepare
    try (PreparedStatement ps = con.prepareStatement("SELECT ?")) {
      ps.setInt(1, 42);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
      }
      // Execute again to trigger cached statement path
      ps.setInt(1, 43);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
      }
    }

    // Large Object operation should still work
    LargeObjectManager lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
    long oid = lom.createLO();
    try {
      try (LargeObject lo = lom.open(oid)) {
        lo.write(new byte[100]);
      }
    } finally {
      lom.delete(oid);
    }
  }

  /**
   * Verifies that Large Objects work without cleanupSavepoints (baseline test).
   * This confirms the issue is specifically with cleanupSavepoints=true.
   */
  @Test
  void testLargeObjectWithoutCleanupSavepoints() throws Exception {
    con.setAutoCommit(false);

    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT 1");
    }

    LargeObjectManager lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
    long oid = lom.createLO();
    try {
      try (LargeObject lo = lom.open(oid)) {
        byte[] data = "Baseline test".getBytes(StandardCharsets.UTF_8);
        lo.write(data);
        lo.seek(0);
        byte[] readBack = lo.read(data.length);
        assertArrayEquals(data, readBack);
      }
    } finally {
      lom.delete(oid);
    }
  }
}
