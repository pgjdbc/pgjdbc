/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.plugin.AuthenticationPlugin;
import org.postgresql.plugin.AuthenticationRequestType;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that a non-zero {@code loginTimeout} interrupts a stuck connection attempt. The
 * {@link AuthenticationPlugin#getPassword} call is interrupted out of {@link Thread#sleep}
 * before the post-sleep code (which would throw a different exception) ever runs, and the
 * driver surfaces the timeout instead of that post-sleep exception.
 */
public class LoginTimeoutInterruptTest {
  private static final long PLUGIN_SLEEP_MS = 30_000;
  private static final long LOGIN_TIMEOUT_SECONDS = 1;
  private static final long WORKER_JOIN_TIMEOUT_MS = 5_000;

  @Test
  void loginTimeoutInterruptsAuthPluginSleep() throws Exception {
    SleepThenThrowAuthPlugin.PLUGIN_INVOKED.set(false);
    SleepThenThrowAuthPlugin.INTERRUPTED_DURING_SLEEP.set(false);
    SleepThenThrowAuthPlugin.POST_SLEEP_REACHED.set(false);
    CapturingExecutor.CAPTURED.set(null);

    Properties props = new Properties();
    PGProperty.LOGIN_TIMEOUT.set(props, Long.toString(LOGIN_TIMEOUT_SECONDS));
    PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME.set(props,
        SleepThenThrowAuthPlugin.class.getName());
    PGProperty.CONNECT_EXECUTOR.set(props,
        CapturingExecutor.class.getName());

    long startNanos = System.nanoTime();
    SQLException ex = assertThrows(SQLException.class, () -> TestUtil.openDB(props));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

    // Join the worker thread so its post-interrupt writes are visible here. If cancel(true)
    // in ConnectTask.abandon() does not actually deliver an interrupt, the worker stays in
    // Thread.sleep for the full PLUGIN_SLEEP_MS, join() times out, and isAlive() is still
    // true.
    Thread worker = CapturingExecutor.CAPTURED.get();
    assertNotNull(worker, "Custom Executor should have captured the worker thread");
    worker.join(WORKER_JOIN_TIMEOUT_MS);
    assertFalse(worker.isAlive(),
        "Worker thread should have exited after cancel(true) interrupted its Thread.sleep,"
            + " but is still alive after " + WORKER_JOIN_TIMEOUT_MS + " ms");

    assertTrue(SleepThenThrowAuthPlugin.PLUGIN_INVOKED.get(),
        "Auth plugin should have been invoked");
    assertTrue(SleepThenThrowAuthPlugin.INTERRUPTED_DURING_SLEEP.get(),
        "Worker thread should have been interrupted out of Thread.sleep via cancel(true)"
            + " in ConnectTask.abandon()");
    assertFalse(SleepThenThrowAuthPlugin.POST_SLEEP_REACHED.get(),
        "Auth plugin should have been interrupted during sleep, not advanced past it");

    // Should fail via loginTimeout, well before the plugin's sleep would have completed.
    assertTrue(elapsedMs < PLUGIN_SLEEP_MS,
        "Connect should be interrupted via loginTimeout, but took " + elapsedMs + " ms"
            + " (plugin sleep is " + PLUGIN_SLEEP_MS + " ms)");

    // The thrown SQLException should be the driver's timeout (CONNECTION_UNABLE_TO_CONNECT),
    // not anything bubbled up from the plugin's post-sleep RuntimeException.
    assertEquals(PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState(), ex.getSQLState(),
        "Expected the loginTimeout SQLException, got: " + ex);
  }

  /**
   * Test-only {@link java.util.concurrent.Executor} that captures the worker thread the driver spawns
   * for the connection attempt, so the test can {@link Thread#join} it and observe whether
   * cancel(true) actually caused the worker to exit.
   */
  public static class CapturingExecutor implements Executor {
    static final AtomicReference<Thread> CAPTURED = new AtomicReference<>();

    @Override
    public void execute(Runnable r) {
      Thread t = new Thread(r, "LoginTimeoutInterruptTest worker");
      t.setDaemon(true);
      CAPTURED.set(t);
      t.start();
    }
  }

  /**
   * Test-only {@link AuthenticationPlugin} that sleeps for a long time and then throws. If the
   * sleep is interrupted (the expected case under {@code loginTimeout}), it rethrows the
   * interrupt as a PSQLException without ever executing the post-sleep statements.
   */
  public static class SleepThenThrowAuthPlugin implements AuthenticationPlugin {
    static final AtomicBoolean PLUGIN_INVOKED = new AtomicBoolean();
    static final AtomicBoolean INTERRUPTED_DURING_SLEEP = new AtomicBoolean();
    static final AtomicBoolean POST_SLEEP_REACHED = new AtomicBoolean();

    @Override
    public char[] getPassword(AuthenticationRequestType type) throws PSQLException {
      PLUGIN_INVOKED.set(true);
      try {
        Thread.sleep(PLUGIN_SLEEP_MS);
      } catch (InterruptedException e) {
        INTERRUPTED_DURING_SLEEP.set(true);
        Thread.currentThread().interrupt();
        throw new PSQLException("SleepThenThrowAuthPlugin interrupted during sleep",
            PSQLState.UNEXPECTED_ERROR, e);
      }
      POST_SLEEP_REACHED.set(true);
      throw new RuntimeException(
          "SleepThenThrowAuthPlugin post-sleep throw should not be reached when "
              + "loginTimeout interrupts the connection attempt");
    }
  }
}
