/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Tests for client/server deadlock prevention during batch execution.
 *
 * <p>See <a href="https://github.com/pgjdbc/pgjdbc/issues/194">issue #194</a>.
 *
 * <h2>Background</h2>
 *
 * <p>When pipelining a large batch, the driver sends queries while the server sends back results.
 * If the driver does not periodically flush and read results, both sides can block on their
 * respective TCP send buffers, causing a deadlock.
 *
 * <p>The driver avoids this via {@code flushIfDeadlockRisk()} in {@code QueryExecutorImpl}, which
 * estimates the amount of buffered response data and forces a Sync+flush when it nears the TCP
 * buffer limit. To estimate accurately, it needs the query to be described first (via a Parse +
 * Describe round-trip), so it can call {@code getMaxResultRowSize()} on the query's result fields.
 */
@ParameterizedClass
@MethodSource("data")
public class BatchDeadlockTest extends BaseTest4 {
  public enum ReturningInQuery {
    ID_DATA("id", "data"),
    STAR("*"),
    NO();

    final String[] columns;

    ReturningInQuery(String... columns) {
      this.columns = columns;
    }

  }

  /**
   * Number of rows in the batch. Combined with {@link #PAYLOAD_SIZE}, the total RETURNING data
   * must exceed the TCP buffer capacity (~128KB on most systems) to trigger the deadlock without
   * the fix. 500 rows × 100KB = 50MB, well above any realistic buffer size.
   */
  private static final int BATCH_SIZE = 500;

  /**
   * Size of the text payload per row in characters.
   */
  private static final int PAYLOAD_SIZE = 100_000;

  private final String payload = String.join("", Collections.nCopies(PAYLOAD_SIZE, "X"));

  private final ReturningInQuery returningInQuery;

  public BatchDeadlockTest(ReturningInQuery returningInQuery, BinaryMode binaryMode) {
    this.returningInQuery = returningInQuery;
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (ReturningInQuery returningInQuery : ReturningInQuery.values()) {
      for (BinaryMode binaryMode : BinaryMode.values()) {
        ids.add(new Object[]{returningInQuery, binaryMode});
      }
    }
    return ids;
  }

  @BeforeAll
  static void createTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "batch_deadlock_test", "id SERIAL PRIMARY KEY, data TEXT");
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "batch_deadlock_test");
    }
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    // The pre-describe fix only works with extended query protocol.
    // Simple query mode does not support Describe, so skip these tests.
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE,
        "Batch deadlock fix requires extended query protocol");
    TestUtil.execute(con, "TRUNCATE batch_deadlock_test");
    con.setAutoCommit(false);
  }

  /**
   * Verifies that a large batch using {@code prepareStatement(sql, String[])} with a large
   * {@code TEXT} column completes without deadlock.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void largePreparedBatchWithGeneratedKeysDoesNotDeadlock() throws Exception {
    try (PreparedStatement ps = preparedStatement()) {
      for (int i = 0; i < BATCH_SIZE; i++) {
        ps.setString(1, payload);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private PreparedStatement preparedStatement() throws SQLException {
    switch (returningInQuery) {
      case STAR:
        return con.prepareStatement(
            "INSERT INTO batch_deadlock_test(data) VALUES (?) RETURNING *");
      case NO:
        return con.prepareStatement(
            "INSERT INTO batch_deadlock_test(data) VALUES (?)",
            Statement.RETURN_GENERATED_KEYS);
      default:
        return con.prepareStatement(
            "INSERT INTO batch_deadlock_test(data) VALUES (?)",
            returningInQuery.columns);
    }
  }

  /**
   * Verifies that a large batch using {@code createStatement(sql, String[])} with a large
   * {@code TEXT} column completes without deadlock.
   */
  @Test
  @Disabled("Deadlock with simple (non-prepared) addBatch(sql) is a known issue")
  @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void largeSimpleBatchWithGeneratedKeysDoesNotDeadlock() throws Exception {
    String sql;
    switch (returningInQuery) {
      case STAR:
        sql = "INSERT INTO batch_deadlock_test(data) VALUES ('" + payload + "') RETURNING *";
        break;
      case NO:
        sql = "INSERT INTO batch_deadlock_test(data) VALUES ('" + payload + "')";
        break;
      default:
        sql =
            "INSERT INTO batch_deadlock_test(data) VALUES ('" + payload + "') RETURNING " + String.join(",", returningInQuery.columns);
        break;
    }
    try (Statement ps = con.createStatement()) {
      for (int i = 0; i < BATCH_SIZE; i++) {
        ps.addBatch(sql);
      }
      ps.executeBatch();
    }
  }
}
