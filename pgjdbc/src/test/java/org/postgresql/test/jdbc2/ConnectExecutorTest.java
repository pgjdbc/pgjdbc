/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectExecutorTest {
  @Test
  void customExecutorIsInvoked() throws Exception {
    AtomicInteger threadsCreated = new AtomicInteger();
    Executor executor = r -> {
      threadsCreated.incrementAndGet();
      Thread t = new Thread(r, "ConnectExecutorTest worker");
      t.setDaemon(true);
      t.start();
    };

    ThreadLocalExecutor.DELEGATE.set(executor);
    try {
      Properties props = new Properties();
      PGProperty.LOGIN_TIMEOUT.set(props, "10");
      PGProperty.CONNECT_EXECUTOR.set(props,
          ThreadLocalExecutor.class.getName());

      try (Connection conn = TestUtil.openDB(props)) {
        assertNotNull(conn);
        assertTrue(conn.isValid(1));
      }

      assertEquals(1, threadsCreated.get(),
          "Configured Executor should produce exactly one thread for a connect attempt "
              + "with loginTimeout > 0");
    } finally {
      ThreadLocalExecutor.DELEGATE.remove();
    }
  }

  @Test
  void executorArgMatching() throws Exception {
    Properties props = new Properties();
    PGProperty.LOGIN_TIMEOUT.set(props, "10");
    PGProperty.CONNECT_EXECUTOR.set(props,
        ArgValidatingExecutor.class.getName());
    PGProperty.CONNECT_EXECUTOR_ARG.set(props,
        ArgValidatingExecutor.EXPECTED_ARG);

    try (Connection conn = TestUtil.openDB(props)) {
      assertNotNull(conn);
      assertTrue(conn.isValid(1));
    }
  }

  @Test
  void executorArgMismatchFailsConnect() {
    Properties props = new Properties();
    PGProperty.LOGIN_TIMEOUT.set(props, "10");
    PGProperty.CONNECT_EXECUTOR.set(props,
        ArgValidatingExecutor.class.getName());
    PGProperty.CONNECT_EXECUTOR_ARG.set(props, "wrong-arg");

    assertThrows(SQLException.class, () -> TestUtil.openDB(props),
        "Connect should fail when the executor's constructor rejects the configured arg");
  }

  /**
   * Test-only {@link java.util.concurrent.Executor} that delegates to whatever the current thread
   * has stashed in {@link #DELEGATE}. Lets each test install its own executor without sharing
   * static state across tests.
   */
  public static class ThreadLocalExecutor implements Executor {
    static final ThreadLocal<Executor> DELEGATE = new ThreadLocal<>();

    @Override
    public void execute(Runnable r) {
      Executor ex = DELEGATE.get();
      if (ex == null) {
        throw new IllegalStateException(
            "No Executor configured in ThreadLocalExecutor.DELEGATE");
      }
      ex.execute(r);
    }
  }

  /**
   * Test-only {@link java.util.concurrent.Executor} whose constructor validates the
   * {@code connectExecutorArg} String, throwing if it does not match the expected value. A
   * mismatch surfaces as a failed executor instantiation (and thus a failed connect), without
   * spawning a worker thread or waiting for loginTimeout.
   */
  public static class ArgValidatingExecutor implements Executor {
    static final String EXPECTED_ARG = "expected-arg-value";

    public ArgValidatingExecutor(String arg) {
      if (!EXPECTED_ARG.equals(arg)) {
        throw new IllegalArgumentException("Unexpected connectExecutorArg: " + arg);
      }
    }

    @Override
    public void execute(Runnable r) {
      Thread t = new Thread(r, "ArgValidatingExecutor worker");
      t.setDaemon(true);
      t.start();
    }
  }
}
