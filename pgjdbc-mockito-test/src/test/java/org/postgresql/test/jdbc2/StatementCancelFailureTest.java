/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PgStatement;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

public class StatementCancelFailureTest extends BaseTest4 {
  /**
   * Test that verifies the fix for issue #3530: Timer cancellation when
   * StatementCancelTimerTask.run throws a runtime error. The test ensures that when
   * cancelIfStillNeeded throws an exception, the Timer continues to work properly for subsequent
   * query cancellations.
   */
  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  @ExtendWith(MockitoExtension.class)
  void testCancelTimerTaskHandlesExceptionFromQueryCancel() throws SQLException {
    PgConnection spyConnection = spy(con.unwrap(PgConnection.class));
    // Mock the cancel() method to throw a RuntimeException on the first call, then work normally
    // This simulates an exception occurring within cancelIfStillNeeded -> cancel() flow
    doThrow(new RuntimeException("Simulated cancel error"))
        .doCallRealMethod()
        .when(spyConnection).cancelQuery();

    // Try breaking Timer by throwing a runtime error from cancelQuery
    try (PgStatement st = spyConnection.createStatement().unwrap(PgStatement.class);) {
      // Set a short timeout that will trigger cancellation
      st.setQueryTimeout(1);

      // Execute a query that takes longer than the timeout - this should trigger
      // cancelIfStillNeeded
      // The exception from cancel() should be caught by StatementCancelTimerTask.run()
      assertDoesNotThrow(
          () -> {
            st.execute("SELECT pg_sleep(5)");
          },
          "Executing pg_sleep(5) with queryTimeout of 1 second typically throws SQLException "
              + "related to a query timeout, however, we mocked cancel() to throw a "
              + "RuntimeException, so the query should succeed");
    }

    // Verify the connection is still workable and ensure the cancellation timer continues to work
    try (Statement secondStatement = spyConnection.createStatement();) {
      secondStatement.setQueryTimeout(1);
      SQLException timeout = assertThrows(SQLException.class, () -> secondStatement.execute(
          "SELECT pg_sleep(5)"));
      assertEquals(PSQLState.QUERY_CANCELED.getState(), timeout.getSQLState(), "pg_sleep(5) with "
          + "timeout of 1 second should be cancelled");
    }
  }
}
