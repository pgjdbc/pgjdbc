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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectThreadFactoryTest {
  @Test
  void customFactoryIsInvoked() throws Exception {
    AtomicInteger threadsCreated = new AtomicInteger();
    ThreadFactory threadFactory = r -> {
      threadsCreated.incrementAndGet();
      Thread t = new Thread(r, "ConnectThreadFactoryTest worker");
      t.setDaemon(true);
      return t;
    };

    ThreadLocalThreadFactory.DELEGATE.set(threadFactory);
    try {
      Properties props = new Properties();
      PGProperty.LOGIN_TIMEOUT.set(props, "10");
      PGProperty.CONNECT_THREAD_FACTORY.set(props,
          ThreadLocalThreadFactory.class.getName());

      try (Connection conn = TestUtil.openDB(props)) {
        assertNotNull(conn);
        assertTrue(conn.isValid(1));
      }

      assertEquals(1, threadsCreated.get(),
          "Configured ThreadFactory should produce exactly one thread for a connect attempt "
              + "with loginTimeout > 0");
    } finally {
      ThreadLocalThreadFactory.DELEGATE.remove();
    }
  }

  @Test
  void factoryArgMatching() throws Exception {
    Properties props = new Properties();
    PGProperty.LOGIN_TIMEOUT.set(props, "10");
    PGProperty.CONNECT_THREAD_FACTORY.set(props,
        ArgValidatingThreadFactory.class.getName());
    PGProperty.CONNECT_THREAD_FACTORY_ARG.set(props,
        ArgValidatingThreadFactory.EXPECTED_ARG);

    try (Connection conn = TestUtil.openDB(props)) {
      assertNotNull(conn);
      assertTrue(conn.isValid(1));
    }
  }

  @Test
  void factoryArgMismatchFailsConnect() {
    Properties props = new Properties();
    PGProperty.LOGIN_TIMEOUT.set(props, "10");
    PGProperty.CONNECT_THREAD_FACTORY.set(props,
        ArgValidatingThreadFactory.class.getName());
    PGProperty.CONNECT_THREAD_FACTORY_ARG.set(props, "wrong-arg");

    assertThrows(SQLException.class, () -> TestUtil.openDB(props),
        "Connect should fail when the worker thread's run() rejects the configured arg");
  }

  /**
   * Test-only {@link java.util.concurrent.ThreadFactory} that returns delegates to whatever the
   * current thread has stashed in {@link #DELEGATE}. Lets each test install its own factory
   * without sharing static state across tests.
   */
  public static class ThreadLocalThreadFactory implements ThreadFactory {
    static final ThreadLocal<ThreadFactory> DELEGATE = new ThreadLocal<>();

    @Override
    public Thread newThread(Runnable r) {
      ThreadFactory tf = DELEGATE.get();
      if (tf == null) {
        throw new IllegalStateException(
            "No ThreadFactory configured in ThreadLocalThreadFactory.DELEGATE");
      }
      return tf.newThread(r);
    }
  }

  /**
   * Test-only {@link java.util.concurrent.ThreadFactory} whose constructor captures the
   * {@code connectThreadFactoryArg} String. The produced ThreadFactory wraps the
   * connection task in a Runnable that throws if the captured arg does not match the expected
   * value so the failure happens inside the worker thread's run() rather than in the
   * factory's constructor.
   */
  public static class ArgValidatingThreadFactory implements ThreadFactory {
    static final String EXPECTED_ARG = "expected-arg-value";

    private final String arg;

    public ArgValidatingThreadFactory(String arg) {
      this.arg = arg;
    }

    @Override
    public Thread newThread(Runnable r) {
      String capturedArg = this.arg;
      Thread t = new Thread(() -> {
        if (!EXPECTED_ARG.equals(capturedArg)) {
          throw new IllegalArgumentException(
              "Unexpected connectThreadFactoryArg: " + capturedArg);
        }
        r.run();
      }, "ArgValidatingThreadFactory worker");
      t.setDaemon(true);
      return t;
    }
  }
}
