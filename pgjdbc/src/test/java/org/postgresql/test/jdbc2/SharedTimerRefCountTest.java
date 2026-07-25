/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.Driver;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;
import org.postgresql.util.SharedTimer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies the acquire/release lifecycle of the process-global {@link SharedTimer} reference that a
 * connection holds while a query-timeout cancel task is scheduled.
 *
 * <p>The ref count is JVM-wide, so these assertions only hold when no other test mutates the timer
 * concurrently; the class is therefore {@link Isolated}. A JVM-wide default {@code queryTimeout}
 * (the {@code query_timeout} CI matrix axis) also makes unrelated connections hold references, so
 * the assertions track deltas from a baseline captured at the start rather than an absolute zero.</p>
 */
@Isolated("Asserts the process-global SharedTimer ref count, which concurrent tests would perturb")
class SharedTimerRefCountTest {
  @Test
  void multipleCancels() throws Exception {
    SharedTimer sharedTimer = Driver.getSharedTimer();
    int baseRefCount = sharedTimer.getRefCount();

    try (Connection connA = TestUtil.openDB();
         Connection connB = TestUtil.openDB();
         Statement stmtA = connA.createStatement();
         Statement stmtB = connB.createStatement();
    ) {
      assertEquals(baseRefCount, sharedTimer.getRefCount());
      stmtA.setQueryTimeout(1);
      stmtB.setQueryTimeout(1);
      try (ResultSet rsA = stmtA.executeQuery("SELECT pg_sleep(2)")) {
        fail("statement should have been canceled by query timeout since the sleep should take 2 sec and the timeout was 1 sec");
      } catch (SQLException e) {
        assertEquals(
            PSQLState.QUERY_CANCELED.getState(), e.getSQLState(),
            "Query is expected to be cancelled since the sleep should take 2 sec and the timeout was 1 sec");
      }
      assertEquals(baseRefCount + 1, sharedTimer.getRefCount());
      try (ResultSet rsB = stmtB.executeQuery("SELECT pg_sleep(2)");) {
        fail("statement should have been canceled by query timeout since the sleep should take 2 sec and the timeout was 1 sec");
      } catch (SQLException e) {
        assertEquals(
            PSQLState.QUERY_CANCELED.getState(), e.getSQLState(),
            "Query is expected to be cancelled since the sleep should take 2 sec and the timeout was 1 sec");
      }
    }
    assertEquals(baseRefCount, sharedTimer.getRefCount());
  }
}
