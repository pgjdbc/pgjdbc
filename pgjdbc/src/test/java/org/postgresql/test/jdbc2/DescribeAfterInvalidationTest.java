/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGStatement;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The pre-describe gates (batch response-size estimation, forced binary transfers) must not trust
 * describe results captured before an invalidation event: DDL, {@code SET search_path}, and
 * {@code DEALLOCATE ALL} bump the deallocate epoch, and the next execution re-describes. These
 * are smoke tests: the mis-sized estimate itself is not observable through the API, so the tests
 * pin down that the invalidated path stays functional end to end.
 */
class DescribeAfterInvalidationTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "describe_invalidation", "id serial primary key, val int");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "describe_invalidation");
    super.tearDown();
  }

  @Test
  void batchReturningAfterDdl() throws SQLException {
    batchReturningAfter("ALTER TABLE describe_invalidation ADD COLUMN extra int");
  }

  @Test
  void batchReturningAfterDeallocateAll() throws SQLException {
    batchReturningAfter("DEALLOCATE ALL");
  }

  private void batchReturningAfter(String invalidation) throws SQLException {
    PreparedStatement ps = con.prepareStatement(
        "INSERT INTO describe_invalidation(val) VALUES (?)",
        Statement.RETURN_GENERATED_KEYS);
    ps.unwrap(PGStatement.class).setPrepareThreshold(1);

    ps.setInt(1, 1);
    ps.addBatch();
    ps.setInt(1, 2);
    ps.addBatch();
    assertArrayEquals(new int[]{1, 1}, ps.executeBatch());
    try (ResultSet keys = ps.getGeneratedKeys()) {
      assertTrue(keys.next(), "generated keys expected for the first batch");
    }

    TestUtil.execute(con, invalidation);

    ps.setInt(1, 3);
    ps.addBatch();
    ps.setInt(1, 4);
    ps.addBatch();
    assertArrayEquals(new int[]{1, 1}, ps.executeBatch());
    try (ResultSet keys = ps.getGeneratedKeys()) {
      assertTrue(keys.next(), "generated keys expected after " + invalidation);
    }
    ps.close();
  }
}
