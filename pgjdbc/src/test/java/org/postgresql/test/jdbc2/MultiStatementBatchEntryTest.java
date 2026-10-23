/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.BatchUpdateException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A batch entry that holds several statements runs all of them, and is reported the way
 * {@code BatchResultHandler} counts it: one update count per entry, and a failure on the second
 * {@code CommandComplete} that carries a count. A statement with {@code RETURNING} delivers rows
 * instead of a count, so an entry whose later statements have one succeeds with the first count.
 *
 * <p>Simple and extendedForPrepared send the SQL of {@code Statement.addBatch(String)} to the
 * server unsplit, and the extended modes split it, so each mode is a case of its own. Each test
 * starts from the single row {@code (pk 1, col1 0)} with auto-commit off, unless it turns
 * auto-commit on itself.</p>
 */
@ParameterizedClass
@EnumSource(PreferQueryMode.class)
class MultiStatementBatchEntryTest extends BaseTest4 {
  private static final String INCREMENT = "UPDATE multistatement SET col1 = col1 + 1 WHERE pk = 1";

  MultiStatementBatchEntryTest(PreferQueryMode mode) {
    setPreferQueryMode(mode);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    TestUtil.execute(con, "CREATE TEMP TABLE multistatement (pk int, col1 int)");
    TestUtil.execute(con, "INSERT INTO multistatement VALUES (1, 0)");
    con.setAutoCommit(false);
  }

  private int col1() throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT col1 FROM multistatement WHERE pk = 1")) {
      assertTrue(rs.next(), "multistatement has the row pk = 1");
      return rs.getInt(1);
    }
  }

  /**
   * In 42.7.13 and 42.7.14, {@code Statement.addBatch(String)} refused any multi-statement entry
   * with {@code NOT_IMPLEMENTED}.
   */
  @Test
  void entryWithReturningRunsBothStatements() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.addBatch(INCREMENT + "; " + INCREMENT + " RETURNING col1");
      int[] updateCounts = stmt.executeBatch();
      assertAll(
          () -> assertArrayEquals(new int[]{1}, updateCounts, "executeBatch()"),
          () -> assertEquals(2, col1(), "col1 after the batch"));
    }
  }

  /**
   * The second count is detected only when its {@code CommandComplete} arrives, after the server
   * has run both statements, so the entry is reported as failed while its changes stay in the
   * caller's transaction. In 42.7.13 and 42.7.14, {@code Statement.addBatch(String)} refused the
   * entry instead.
   */
  @Test
  void entryWithoutReturningFailsAfterRunningBothStatements() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.addBatch(INCREMENT + "; " + INCREMENT);
      BatchUpdateException e = assertThrows(BatchUpdateException.class, stmt::executeBatch);
      assertAll(
          () -> assertEquals(PSQLState.TOO_MANY_RESULTS.getState(), e.getSQLState(), "SQLState"),
          () -> assertArrayEquals(new int[]{Statement.EXECUTE_FAILED}, e.getUpdateCounts(),
              "getUpdateCounts()"),
          () -> assertEquals(2, col1(), "col1 after the batch"));
    }
  }

  /**
   * The SQL of a {@code PreparedStatement} is split into a {@code CompositeQuery} in every mode. In
   * 42.7.13 and 42.7.14, {@code executeBatch()} threw {@code ClassCastException} from
   * {@code CompositeQuery} to {@code SimpleQuery} before sending anything.
   */
  @Test
  void preparedEntryWithReturningRunsBothStatements() throws SQLException {
    try (PreparedStatement ps =
             con.prepareStatement(INCREMENT + "; " + INCREMENT + " RETURNING col1")) {
      ps.addBatch();
      int[] updateCounts = ps.executeBatch();
      assertAll(
          () -> assertArrayEquals(new int[]{1}, updateCounts, "executeBatch()"),
          () -> assertEquals(2, col1(), "col1 after the batch"));
    }
  }

  @Test
  void preparedEntryWithoutReturningFailsAfterRunningBothStatements() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(INCREMENT + "; " + INCREMENT)) {
      ps.addBatch();
      BatchUpdateException e = assertThrows(BatchUpdateException.class, ps::executeBatch);
      assertAll(
          () -> assertEquals(PSQLState.TOO_MANY_RESULTS.getState(), e.getSQLState(), "SQLState"),
          () -> assertArrayEquals(new int[]{Statement.EXECUTE_FAILED}, e.getUpdateCounts(),
              "getUpdateCounts()"),
          () -> assertEquals(2, col1(), "col1 after the batch"));
    }
  }

  /**
   * The shape from issue #4349: each statement binds its own parameter. The first statement sets
   * col1 to the first parameter and the second appends the second parameter as a decimal digit, so
   * 12 shows each statement received its own value, in order.
   */
  @Test
  void preparedEntryBindsEachStatementItsOwnParameter() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(
        "UPDATE multistatement SET col1 = ? WHERE pk = 1;"
            + " UPDATE multistatement SET col1 = col1 * 10 + ? WHERE pk = 1 RETURNING col1")) {
      ps.setInt(1, 1);
      ps.setInt(2, 2);
      ps.addBatch();
      int[] updateCounts = ps.executeBatch();
      assertAll(
          () -> assertArrayEquals(new int[]{1}, updateCounts, "executeBatch()"),
          () -> assertEquals(12, col1(), "col1 after the batch"));
    }
  }

  /**
   * A batch whose first entry returns more results than the batch has entries reports a failure of
   * a later entry as {@code BatchUpdateException} and reads every response of the batch, so the
   * next query on the connection gets its own result. Results are counted per statement, not per
   * entry, so no count past the first entry identifies an entry, and every entry is reported as
   * {@code EXECUTE_FAILED}, including the ones that ran. Under auto-commit the batch used to throw
   * {@code IllegalArgumentException} from {@code Arrays.fill} instead, leaving the responses of the
   * entries after the failing one for the next query.
   */
  @Test
  void batchFailingAfterAnEntryOfSeveralSelectsLeavesTheConnectionUsable() throws SQLException {
    con.setAutoCommit(true);
    try (Statement stmt = con.createStatement()) {
      stmt.addBatch("SELECT 1; SELECT 2; SELECT 3; SELECT 4");
      stmt.addBatch("SELECT 1/0");
      stmt.addBatch(INCREMENT);
      BatchUpdateException e = assertThrows(BatchUpdateException.class, stmt::executeBatch);
      assertAll(
          () -> assertEquals(PSQLState.DIVISION_BY_ZERO.getState(), e.getSQLState(), "SQLState"),
          () -> assertArrayEquals(
              new int[]{Statement.EXECUTE_FAILED, Statement.EXECUTE_FAILED,
                  Statement.EXECUTE_FAILED},
              e.getUpdateCounts(), "getUpdateCounts()"));
    }
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 42")) {
      assertTrue(rs.next(), "SELECT 42 returns a row");
      assertEquals(42, rs.getInt(1), "SELECT 42 after the failed batch");
    }
  }
}
