/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * The registry of {@link CountingSocketFactory} keeps a {@link CountingSocketFactory.Counters}
 * reachable for no longer than the test holds it, so a test that never calls
 * {@link CountingSocketFactory#unregister} leaves nothing behind.
 *
 * <p>Each test that waits for the garbage collector fails, rather than skips, when the object it
 * waits for is still reachable after {@link #GC_TIMEOUT_SECONDS}: an object that stays reachable
 * is the leak this class exists to catch.
 */
class CountingSocketFactoryTest {
  private static final long GC_TIMEOUT_SECONDS = 10;

  /** The key of a registered {@code Counters} that nothing holds strongly. */
  private static final class DroppedCounters {
    final String key;
    final WeakReference<CountingSocketFactory.Counters> probe;

    DroppedCounters(CountingSocketFactory.Counters counters) {
      this.key = counters.key();
      this.probe = new WeakReference<>(counters);
    }
  }

  @Test
  void aCountersTheTestHoldsResolvesAfterGarbageCollection() {
    CountingSocketFactory.Counters counters = CountingSocketFactory.register();

    awaitCleared(new WeakReference<>(new Object()), "an unreferenced Object", () -> { });

    assertDoesNotThrow(() -> new CountingSocketFactory(counters.key()),
        () -> "new CountingSocketFactory(" + counters.key() + ") while the test holds its Counters");
  }

  @Test
  void aKeyIsRejectedAfterUnregister() {
    CountingSocketFactory.Counters counters = CountingSocketFactory.register();

    CountingSocketFactory.unregister(counters);

    assertThrows(IllegalArgumentException.class,
        () -> new CountingSocketFactory(counters.key()),
        () -> "new CountingSocketFactory(" + counters.key() + ") after unregister");
  }

  @Test
  void aCountersTheTestDroppedIsCollectedAndItsKeyIsRejected() {
    DroppedCounters dropped = new DroppedCounters(CountingSocketFactory.register());

    awaitCleared(dropped.probe, "Counters " + dropped.key, () -> { });

    assertThrows(IllegalArgumentException.class,
        () -> new CountingSocketFactory(dropped.key),
        () -> "new CountingSocketFactory(" + dropped.key + ") after its Counters was collected");
  }

  /**
   * The key string is reachable only from its {@code Counters} and from the registry entry, so the
   * key is collected only after {@link CountingSocketFactory#register()} has removed the entry.
   */
  @Test
  void registerRemovesTheEntryOfACollectedCounters() {
    WeakReference<String> keyProbe = new WeakReference<>(CountingSocketFactory.register().key());

    awaitCleared(keyProbe, "the registry entry of a dropped Counters",
        CountingSocketFactory::register);
  }

  @Test
  void aCountersIsCollectedOnceItsConnectionIsClosed() throws SQLException {
    DroppedCounters dropped = new DroppedCounters(countersOfAClosedConnection());

    awaitCleared(dropped.probe, "Counters " + dropped.key + " of a closed connection", () -> { });
  }

  private static CountingSocketFactory.Counters countersOfAClosedConnection() throws SQLException {
    CountingSocketFactory.Counters counters = CountingSocketFactory.register();
    Properties props = new Properties();
    PGProperty.SOCKET_FACTORY.set(props, CountingSocketFactory.class.getName());
    PGProperty.SOCKET_FACTORY_ARG.set(props, counters.key());
    try (Connection con = TestUtil.openDB(props);
         Statement statement = con.createStatement()) {
      statement.execute("select 1");
    }
    // Shows that the connection wrote through this Counters, so its socket held it
    assertNotEquals(0, counters.bytesOut.get(), "bytesOut after select 1");
    return counters;
  }

  /**
   * Requests garbage collection and runs {@code betweenRequests} until {@code probe} is cleared,
   * and fails when it is still set after {@link #GC_TIMEOUT_SECONDS}. Each request also allocates,
   * so a young collection runs even where {@code -XX:+DisableExplicitGC} ignores
   * {@link System#gc()}.
   */
  private static void awaitCleared(WeakReference<?> probe, String what, Runnable betweenRequests) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GC_TIMEOUT_SECONDS);
    byte[][] garbage = new byte[64][];
    int gcRequests = 0;
    while (probe.get() != null) {
      if (System.nanoTime() - deadline > 0) {
        fail(what + " is still reachable after " + gcRequests + " System.gc() requests in "
            + GC_TIMEOUT_SECONDS + " seconds");
      }
      for (int i = 0; i < garbage.length; i++) {
        garbage[i] = new byte[64 * 1024];
      }
      System.gc();
      gcRequests++;
      betweenRequests.run();
    }
  }
}
