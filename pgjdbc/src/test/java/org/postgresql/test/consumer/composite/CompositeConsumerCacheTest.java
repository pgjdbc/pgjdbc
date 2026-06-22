/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Properties;

@Execution(ExecutionMode.SAME_THREAD)
class CompositeConsumerCacheTest {
  private static final String TABLE_NAME = "consumer_cache_orders";

  @Test
  void flushCacheOnDdlTrue_refreshesPreparedRowTypeReads() throws SQLException {
    Properties props = new Properties();
    PGProperty.PREPARE_THRESHOLD.set(props, -1);

    try (Connection conn = TestUtil.openDB(props);
         Statement stmt = conn.createStatement()) {
      cleanup(stmt);
      stmt.execute("CREATE TABLE " + TABLE_NAME + " (id int primary key, status text)");
      stmt.execute("INSERT INTO " + TABLE_NAME + " VALUES (1, 'new')");

      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT o FROM " + TABLE_NAME + " o WHERE id = ?")) {
        ps.setInt(1, 1);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          Struct original = rs.getObject(1, Struct.class);
          assertEquals(2, original.getAttributes().length);
        }

        stmt.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN active boolean DEFAULT true");

        ps.setInt(1, 1);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          Struct refreshed = rs.getObject(1, Struct.class);
          assertEquals(3, refreshed.getAttributes().length);
          assertEquals(true, refreshed.getAttributes()[2]);
        }
      }
    } finally {
      try (Connection conn = TestUtil.openDB();
           Statement stmt = conn.createStatement()) {
        cleanup(stmt);
      }
    }
  }

  @Test
  void flushCacheOnDdlFalse_stillAllowsCompositeRowReadsAfterLocalDdl() throws SQLException {
    Properties props = new Properties();
    PGProperty.FLUSH_CACHE_ON_DDL.set(props, false);
    PGProperty.PREPARE_THRESHOLD.set(props, -1);

    try (Connection conn = TestUtil.openDB(props);
         Statement stmt = conn.createStatement()) {
      cleanup(stmt);
      stmt.execute("CREATE TABLE " + TABLE_NAME + " (id int primary key, status text)");
      stmt.execute("INSERT INTO " + TABLE_NAME + " VALUES (1, 'new')");

      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT o FROM " + TABLE_NAME + " o WHERE id = ?")) {
        ps.setInt(1, 1);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          Struct original = rs.getObject(1, Struct.class);
          assertEquals(2, original.getAttributes().length);
        }

        stmt.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN active boolean DEFAULT true");

        ps.setInt(1, 1);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          Struct refreshed = rs.getObject(1, Struct.class);
          assertEquals(3, refreshed.getAttributes().length);
          assertEquals(true, refreshed.getAttributes()[2]);
        }
      }
    } finally {
      try (Connection conn = TestUtil.openDB();
           Statement stmt = conn.createStatement()) {
        cleanup(stmt);
      }
    }
  }

  private static void cleanup(Statement stmt) throws SQLException {
    stmt.execute("DROP TABLE IF EXISTS " + TABLE_NAME + " CASCADE");
  }
}
