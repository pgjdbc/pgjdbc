/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Autovacuum does not always keeps up with the generated bloat, so this class helps vacuuming
 * the pg_largeobject table when it grows too large.
 */
public class LargeObjectVacuum {
  private final Connection connection;
  private final long maxSize;

  public LargeObjectVacuum(Connection connection) {
    this(connection, 1024 * 1024 * 1024);
  }

  public LargeObjectVacuum(Connection connection, long maxSize) {
    this.connection = connection;
    this.maxSize = maxSize;
  }

  public void vacuum() throws SQLException {
    if (getLargeObjectTableSize() > maxSize) {
      vacuumLargeObjectTable();
    }
  }

  private void vacuumLargeObjectTable() throws SQLException {
    // Vacuum can't be executed in a transaction, so we go into autocommit mode
    connection.setAutoCommit(true);
    try (PreparedStatement vacuum =
             connection.prepareStatement("VACUUM FULL ANALYZE pg_largeobject")) {
      vacuum.execute();
    }
    connection.setAutoCommit(false);
  }

  private long getLargeObjectTableSize() throws SQLException {
    try (PreparedStatement ps =
             connection.prepareStatement("select pg_table_size('pg_largeobject')")) {
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
