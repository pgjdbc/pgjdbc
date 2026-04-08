/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.QueryExecutor;
import org.postgresql.core.v3.QueryExecutorImpl;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Properties;

/**
 * Tests to verify that batch execution does not deadlock when the socket receive buffer is
 * smaller than the old hardcoded {@code MAX_BUFFERED_RECV_BYTES} constant (64,000 bytes).
 *
 * <p>See GitHub issue #194: PgJDBC can experience client/server deadlocks during batch execution.
 *
 * <p>The deadlock occurs when the driver pipelines too many queries without flushing and reading
 * the server's responses. Both sides block waiting for the other to read, causing deadlock.
 * The fix initialises {@code maxBufferedRecvBytes} from the actual socket receive buffer size
 * rather than using a hardcoded 64KB constant, so the driver flushes at the right point.
 */
public class BatchDeadlockTest {

  /** Field name in QueryExecutorImpl used to track the flush threshold. */
  private static final String MAX_BUFFERED_RECV_BYTES_FIELD = "maxBufferedRecvBytes";

  /** Field name in QueryExecutorBase for the underlying stream. */
  private static final String PG_STREAM_FIELD = "pgStream";

  /** Field name in PGStream for the underlying socket. */
  private static final String SOCKET_FIELD = "connection";

  /** The old hardcoded value that caused the deadlock (see issue #194). */
  private static final int OLD_HARDCODED_MAX_BUFFERED_RECV_BYTES = 64000;

  /**
   * A small socket receive buffer, much smaller than the old 64KB constant.
   * Large enough to sustain the connection, small enough to expose the bug.
   */
  private static final int SMALL_RECV_BUF_BYTES = 8192;

  /**
   * Number of rows in the batch. Each row generates a response of ~250 bytes
   * (CommandComplete + ReadyForQuery), so 2000 rows ≈ 500KB total response,
   * far exceeding both the old 64KB threshold and the small socket buffer.
   */
  private static final int LARGE_BATCH_SIZE = 2000;

  /** Maximum time the fixed batch is allowed to run before we consider it hung. */
  private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Maximum time to wait for the pre-fix simulation to deadlock.
   * Short enough to keep CI fast; long enough to be reliable.
   */
  private static final Duration DEADLOCK_TIMEOUT = Duration.ofSeconds(10);

  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("receiveBufferSize", String.valueOf(SMALL_RECV_BUF_BYTES));
    con = TestUtil.openDB(props);
    TestUtil.createTempTable(con, "batch_deadlock_test", "id INTEGER, data TEXT");
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      TestUtil.dropTable(con, "batch_deadlock_test");
    } finally {
      TestUtil.closeDB(con);
    }
  }

  /**
   * Verifies that after the fix, {@code maxBufferedRecvBytes} is initialised from the actual
   * socket receive buffer size rather than the old hardcoded 64,000 byte constant.
   *
   * <p>When a small receive buffer is requested via {@code receiveBufferSize}, the OS may round
   * up the value but it should still be smaller than 64,000. The field must reflect this smaller
   * value so that the driver flushes frequently enough to avoid deadlock.
   */
  @Test
  void maxBufferedRecvBytesIsInitialisedFromSocketReceiveBufferSize() throws Exception {
    int maxBufferedRecvBytes = getMaxBufferedRecvBytes(con);
    int actualSocketRecvBuf = getSocketReceiveBufferSize(con);

    assertEquals(actualSocketRecvBuf, maxBufferedRecvBytes,
        "maxBufferedRecvBytes should equal the actual socket receive buffer size, "
            + "not the old hardcoded value of " + OLD_HARDCODED_MAX_BUFFERED_RECV_BYTES);
  }

  /**
   * Verifies that when a small receive buffer is requested, {@code maxBufferedRecvBytes} is
   * smaller than the old hardcoded 64,000 byte constant that caused the deadlock.
   *
   * <p>This test is skipped on systems where the OS enforces a minimum socket buffer size
   * larger than the old constant (uncommon but possible).
   */
  @Test
  void maxBufferedRecvBytesIsSmallerThanOldHardcodedValueWhenSmallBufferRequested()
      throws Exception {
    int actualSocketRecvBuf = getSocketReceiveBufferSize(con);
    if (actualSocketRecvBuf >= OLD_HARDCODED_MAX_BUFFERED_RECV_BYTES) {
      // OS enforced a minimum larger than the old constant; cannot demonstrate difference.
      return;
    }

    int maxBufferedRecvBytes = getMaxBufferedRecvBytes(con);
    assertTrue(maxBufferedRecvBytes < OLD_HARDCODED_MAX_BUFFERED_RECV_BYTES,
        "With a small socket receive buffer (" + actualSocketRecvBuf + " bytes), "
            + "maxBufferedRecvBytes (" + maxBufferedRecvBytes + ") should be less than "
            + "the old hardcoded value of " + OLD_HARDCODED_MAX_BUFFERED_RECV_BYTES + " bytes");
  }

  /**
   * Verifies that a large batch completes successfully with the fix in place.
   *
   * <p>With a small socket receive buffer and {@code maxBufferedRecvBytes} set from the actual
   * buffer size, the driver flushes and reads server responses frequently enough to keep both
   * sides' buffers from filling, preventing deadlock.
   */
  @Test
  void largeBatchCompletesWithoutDeadlockAfterFix() {
    assertTimeoutPreemptively(BATCH_TIMEOUT, () -> executeLargeInsertBatch(con),
        "Large batch should complete without deadlock within "
            + BATCH_TIMEOUT.getSeconds() + "s after the fix");
  }

  /**
   * Verifies that a large batch deadlocks when {@code maxBufferedRecvBytes} is overridden to the
   * old hardcoded value (64,000 bytes) while the actual socket buffer is small (8KB).
   *
   * <p>This reproduces the pre-fix behaviour described in GitHub issue #194: the driver
   * pipelines up to 64KB of estimated responses before flushing, but the actual OS TCP buffers
   * can only hold ~8KB, causing both sides to block waiting for the other to read.
   *
   * <p>A separate connection is used so that the deadlock does not corrupt the connection
   * used by the other tests and tearDown.
   *
   * <p>Note: this test is best-effort. It will skip gracefully when the OS enforces a minimum
   * socket buffer size >= 64KB (e.g. on some Linux systems with large TCP buffer auto-tuning),
   * since in that case the deadlock would not occur even with the old code. The other three
   * tests in this class are reliable on all systems and provide the primary verification.
   */
  @Test
  void largeBatchDeadlocksBeforeFix() throws Exception {
    int actualSocketRecvBuf = getSocketReceiveBufferSize(con);
    if (actualSocketRecvBuf >= OLD_HARDCODED_MAX_BUFFERED_RECV_BYTES) {
      // OS enforced a minimum socket buffer >= old constant; deadlock cannot be triggered.
      return;
    }

    // Open a dedicated connection for the deadlock test so tearDown is not affected.
    Properties props = new Properties();
    props.setProperty("receiveBufferSize", String.valueOf(SMALL_RECV_BUF_BYTES));
    Connection deadlockCon = TestUtil.openDB(props);

    // Simulate pre-fix behaviour: override maxBufferedRecvBytes to the old hardcoded value.
    setMaxBufferedRecvBytes(deadlockCon, OLD_HARDCODED_MAX_BUFFERED_RECV_BYTES);

    boolean[] timedOut = {false};
    try {
      assertTimeoutPreemptively(DEADLOCK_TIMEOUT,
          () -> executeLargeInsertBatch(deadlockCon));
    } catch (AssertionError e) {
      // assertTimeoutPreemptively throws AssertionError on timeout, confirming deadlock.
      timedOut[0] = true;
    } finally {
      // Close forcibly; the connection may be in a broken state after the deadlock.
      try {
        deadlockCon.close();
      } catch (Exception ignored) {
        // expected when the connection was aborted by the timeout
      }
    }

    assertTrue(timedOut[0],
        "With old hardcoded maxBufferedRecvBytes=" + OLD_HARDCODED_MAX_BUFFERED_RECV_BYTES
            + " and actual socket recv buf=" + actualSocketRecvBuf
            + ", the batch should deadlock (time out within "
            + DEADLOCK_TIMEOUT.getSeconds() + "s)");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Inserts {@link #LARGE_BATCH_SIZE} rows as a single JDBC batch.
   * Total response data is approximately LARGE_BATCH_SIZE * 250 bytes.
   */
  private static void executeLargeInsertBatch(Connection con) throws Exception {
    con.setAutoCommit(false);
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO batch_deadlock_test VALUES (?, ?)")) {
      StringBuilder sb = new StringBuilder(200);
      for (int j = 0; j < 200; j++) {
        sb.append('x');
      }
      String payload = sb.toString();
      for (int i = 0; i < LARGE_BATCH_SIZE; i++) {
        ps.setInt(1, i);
        ps.setString(2, payload);
        ps.addBatch();
      }
      ps.executeBatch();
    }
    con.commit();
  }

  private static int getMaxBufferedRecvBytes(Connection con)
      throws Exception {
    QueryExecutor qe = con.unwrap(PgConnection.class).getQueryExecutor();
    Field field = QueryExecutorImpl.class.getDeclaredField(MAX_BUFFERED_RECV_BYTES_FIELD);
    field.setAccessible(true);
    return (int) field.get(qe);
  }

  private static void setMaxBufferedRecvBytes(Connection con, int value)
      throws Exception {
    QueryExecutor qe = con.unwrap(PgConnection.class).getQueryExecutor();
    Field field = QueryExecutorImpl.class.getDeclaredField(MAX_BUFFERED_RECV_BYTES_FIELD);
    field.setAccessible(true);
    field.set(qe, value);
  }

  private static int getSocketReceiveBufferSize(Connection con)
      throws Exception {
    QueryExecutor qe = con.unwrap(PgConnection.class).getQueryExecutor();
    Field pgStreamField = qe.getClass().getSuperclass().getDeclaredField(PG_STREAM_FIELD);
    pgStreamField.setAccessible(true);
    Object pgStream = pgStreamField.get(qe);
    Field socketField = pgStream.getClass().getDeclaredField(SOCKET_FIELD);
    socketField.setAccessible(true);
    Socket socket = (Socket) socketField.get(pgStream);
    return socket.getReceiveBufferSize();
  }
}
