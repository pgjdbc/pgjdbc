/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

/* changes were made to move it into the org.postgresql.util package
 *
 * Copyright 2022 Juan Lopes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.postgresql.util;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.JavaVersion;
import org.postgresql.test.annotations.DisableLogger;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class LazyCleanerTest {
  @Test
  @DisableLogger(LazyCleanerImpl.class)
  void phantomCleaner() throws InterruptedException {
    List<Object> list = new ArrayList<>(Arrays.asList(
        new Object(), new Object(), new Object()));

    Duration ttl = ofSeconds(5);
    LazyCleanerImpl t = new LazyCleanerImpl("Cleaner", ttl);

    String[] collected = new String[list.size()];
    List<LazyCleaner.Cleanable<RuntimeException>> cleaners = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      final int ii = i;
      cleaners.add(
          t.register(
              list.get(i),
              leak -> {
                collected[ii] = leak ? "LEAK" : "NO LEAK";
                if (ii == 0) {
                  throw new RuntimeException(
                      "Exception from cleanup action to verify if the cleaner thread would survive"
                  );
                }
              }
          )
      );
    }

    // The active LazyCleanerImpl is selected by multi-release packaging, not by the runtime JDK
    // (the Maven source-distribution build runs the Java 8 variant on any JDK), so branch on the
    // observed behaviour rather than on JavaVersion.
    boolean dedicatedThread = t.isThreadRunning();
    if (JavaVersion.getRuntimeVersion() == JavaVersion.v1_8) {
      assertTrue(dedicatedThread,
          "Java 8 always loads the PhantomReference-based cleaner, which runs a dedicated thread");
    }

    cleaners.get(1).clean();

    list.set(0, null);
    System.gc();
    System.gc();

    list.clear();
    System.gc();
    System.gc();

    if (dedicatedThread) {
      Await.until(
          "The cleanup thread should detect leaks and terminate after GC",
          threadStopBudget(ttl),
          () -> !t.isThreadRunning()
      );
    } else {
      // The native Cleaner has no dedicated thread to stop; give it room to process the refs
      Thread.sleep(1000);
    }

    assertEquals(
        Arrays.asList("LEAK", "NO LEAK", "LEAK").toString(),
        Arrays.asList(collected).toString(),
        "Second object has been released properly, so it should be reported as NO LEAK"
    );
  }

  @Test
  void cleanupCompletesAfterManualClean() throws InterruptedException {
    String threadName = UUID.randomUUID().toString();
    Duration ttl = ofSeconds(5);
    LazyCleanerImpl t = new LazyCleanerImpl(threadName, ttl);

    AtomicBoolean cleaned = new AtomicBoolean();
    List<Object> list = new ArrayList<>();
    list.add(new Object());

    LazyCleaner.Cleanable<RuntimeException> cleanable =
        t.register(
            list.get(0),
            leak -> cleaned.set(true)
        );

    // The active LazyCleanerImpl is selected by multi-release packaging, not by the runtime JDK
    // (the Maven source-distribution build runs the Java 8 variant on any JDK), so branch on the
    // observed behaviour rather than on JavaVersion.
    boolean dedicatedThread = t.isThreadRunning();
    if (JavaVersion.getRuntimeVersion() == JavaVersion.v1_8) {
      assertTrue(dedicatedThread,
          "Java 8 always loads the PhantomReference-based cleaner, which runs a dedicated thread");
    }

    // Manually clean the object
    cleanable.clean();

    // Verify it was cleaned
    assertTrue(cleaned.get(), "Object should be cleaned after manual clean");

    // Clear the reference and verify cleanup thread eventually stops
    list.clear();
    System.gc();
    System.gc();

    if (dedicatedThread) {
      Await.until(
          "Cleanup thread should stop when no objects remain",
          threadStopBudget(ttl),
          () -> !t.isThreadRunning()
      );
    } else {
      assertFalse(t.isThreadRunning(),
          "Native Cleaner variant: there is no dedicated thread to stop");
    }
  }

  @Test
  @DisableLogger(LazyCleanerImpl.class)
  void exceptionsDuringCleanupAreHandled() throws InterruptedException {
    Duration ttl = ofSeconds(5);
    LazyCleanerImpl t = new LazyCleanerImpl("test-cleaner", ttl);

    java.util.concurrent.atomic.AtomicInteger cleanupCount = new java.util.concurrent.atomic.AtomicInteger(0);
    List<Object> list = new ArrayList<>();

    // Register object that throws during cleanup
    list.add(new Object());
    t.register(
        list.get(0),
        leak -> {
          cleanupCount.incrementAndGet();
          throw new IllegalStateException("test exception from CleaningAction");
        }
    );

    // Register another object that should still be cleaned
    list.add(new Object());
    AtomicBoolean secondCleaned = new AtomicBoolean(false);
    t.register(
        list.get(1),
        leak -> secondCleaned.set(true)
    );

    // The active LazyCleanerImpl is selected by multi-release packaging, not by the runtime JDK
    // (the Maven source-distribution build runs the Java 8 variant on any JDK), so branch on the
    // observed behaviour rather than on JavaVersion.
    boolean dedicatedThread = t.isThreadRunning();
    if (JavaVersion.getRuntimeVersion() == JavaVersion.v1_8) {
      assertTrue(dedicatedThread,
          "Java 8 always loads the PhantomReference-based cleaner, which runs a dedicated thread");
    }

    // Trigger cleanup
    list.clear();
    System.gc();
    System.gc();

    // Cleanup completion is bound by GC and reference-enqueue latency, not by threadTtl. Allow a
    // generous budget so the assertion holds even when the JVM runs on a single CPU
    // (-XX:ActiveProcessorCount=1), where GC, the reference handler, and the ForkJoinPool worker
    // all share one core.
    Await.until(
        "Both cleanups should complete despite exception",
        ofSeconds(30),
        () -> cleanupCount.get() == 1 && secondCleaned.get()
    );

    if (dedicatedThread) {
      Await.until(
          "Cleanup thread should stop after all objects are cleaned",
          threadStopBudget(ttl),
          () -> !t.isThreadRunning()
      );
    } else {
      assertFalse(t.isThreadRunning(),
          "Native Cleaner variant: there is no dedicated thread to stop");
    }
  }

  @Test
  void exceptionOnCleanRethrowsToCaller() {
    LazyCleanerImpl t = new LazyCleanerImpl("test-cleaner", ofSeconds(5));
    Object obj = new Object();
    RuntimeException expectedException = new RuntimeException("test exception");

    LazyCleaner.Cleanable<RuntimeException> cleanable = t.register(obj, leak -> {
      throw expectedException;
    });

    RuntimeException thrownException = assertThrows(RuntimeException.class, cleanable::clean);
    assertSame(expectedException, thrownException, "Expected same exception instance to be thrown");
  }

  /**
   * Worst-case time for the Java 8 cleanup thread to stop once its registry empties. The thread
   * waits up to one {@code threadTtl} window to observe the final GC'd reference, then must time
   * out a second full {@code threadTtl} window before the loop breaks (see {@code LazyCleanerImpl}).
   * The extra seconds absorb scheduling slack when the JVM runs on a single CPU
   * (-XX:ActiveProcessorCount=1).
   *
   * @param threadTtl the time-to-live configured for the cleaner
   * @return the await budget to use for {@code !isThreadRunning()}
   */
  private static Duration threadStopBudget(Duration threadTtl) {
    return threadTtl.multipliedBy(2).plus(ofSeconds(5));
  }
}
