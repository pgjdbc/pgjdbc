/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.sspi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.sspi.SSPIClient;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Verifies that {@code SSPIClient} degrades gracefully when Waffle is missing from the classpath,
 * rather than failing with a linkage error. The probe must stay name-based, so this test runs on any
 * platform (the Windows-only end-to-end path lives in {@code SSPITest}).
 */
class SSPIClientWaffleTest {

  private static final String UNAVAILABLE_MESSAGE = "SSPI unavailable (no Waffle/JNA libraries?)";

  /**
   * A classloader that hides the {@code waffle.*} packages, simulating a runtime without waffle-jna
   * while keeping every other class (including JNA) reachable through the parent.
   */
  private static final class WaffleHidingClassLoader extends ClassLoader {
    WaffleHidingClassLoader(ClassLoader parent) {
      super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith("waffle.")) {
        throw new ClassNotFoundException(name);
      }
      return super.loadClass(name, resolve);
    }
  }

  private static boolean isWaffleAvailable(ClassLoader probeLoader) throws Exception {
    Method method = SSPIClient.class.getDeclaredMethod("isWaffleAvailable", ClassLoader.class);
    method.setAccessible(true);
    return (boolean) method.invoke(null, probeLoader);
  }

  @Test
  void reportsAvailableWhenWaffleIsOnClasspath() throws Exception {
    assertTrue(isWaffleAvailable(getClass().getClassLoader()));
  }

  @Test
  void reportsUnavailableAndWarnsWhenWaffleIsMissing() throws Exception {
    Logger logger = Logger.getLogger("org.postgresql.sspi.SSPIClient");
    List<LogRecord> records = new ArrayList<>();
    Handler handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    handler.setLevel(Level.ALL);
    logger.addHandler(handler);
    try {
      assertFalse(isWaffleAvailable(new WaffleHidingClassLoader(getClass().getClassLoader())));
      boolean warned = records.stream().anyMatch(record -> record.getLevel() == Level.WARNING
          && String.valueOf(record.getMessage()).contains(UNAVAILABLE_MESSAGE));
      assertTrue(warned, "expected a WARNING containing: " + UNAVAILABLE_MESSAGE);
    } finally {
      logger.removeHandler(handler);
    }
  }
}
