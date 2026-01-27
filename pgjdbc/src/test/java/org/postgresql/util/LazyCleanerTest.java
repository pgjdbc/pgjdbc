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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.JavaVersion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class LazyCleanerTest {
  @Test
  void phantomCleaner() throws InterruptedException {
    List<Object> list = new ArrayList<>(Arrays.asList(
        new Object(), new Object(), new Object()));

    LazyCleanerImpl t = new LazyCleanerImpl("Cleaner", ofSeconds(5));

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

    if (JavaVersion.getRuntimeVersion() == JavaVersion.v1_8) {
      assertTrue(t.isThreadRunning(),
          "cleanup thread should be running, and it should wait for the leaks");
    }

    cleaners.get(1).clean();

    list.set(0, null);
    System.gc();
    System.gc();

    list.clear();
    System.gc();
    System.gc();

    if (JavaVersion.getRuntimeVersion() == JavaVersion.v1_8) {
      Await.until(
          "The cleanup thread should detect leaks and terminate within 5-10 seconds after GC",
          ofSeconds(10),
          () -> !t.isThreadRunning()
      );
    } else {
      // Allow some room for Java's Cleaner to clean the refs
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
    LazyCleanerImpl t = new LazyCleanerImpl(threadName, ofSeconds(5));

    AtomicBoolean cleaned = new AtomicBoolean();
    List<Object> list = new ArrayList<>();
    list.add(new Object());

    LazyCleaner.Cleanable<RuntimeException> cleanable =
        t.register(
            list.get(0),
            leak -> cleaned.set(true)
        );

    if (JavaVersion.getRuntimeVersion() == JavaVersion.v1_8) {
      assertTrue(t.isThreadRunning(),
          "cleanup thread should be running when there are objects to monitor");
    }

    // Manually clean the object
    cleanable.clean();

    // Verify it was cleaned
    assertTrue(cleaned.get(), "Object should be cleaned after manual clean");

    // Clear the reference and verify cleanup thread eventually stops
    list.clear();
    System.gc();
    System.gc();

    if (JavaVersion.getRuntimeVersion() == JavaVersion.v1_8) {
      Await.until(
          "Cleanup thread should stop when no objects remain",
          ofSeconds(10),
          () -> !t.isThreadRunning()
      );
    }
  }

  @Test
  void exceptionsDuringCleanupAreHandled() throws InterruptedException {
    LazyCleanerImpl t = new LazyCleanerImpl("test-cleaner", ofSeconds(5));

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

    if (JavaVersion.getRuntimeVersion() == JavaVersion.v1_8) {
      assertTrue(t.isThreadRunning(),
          "cleanup thread should be running when there are objects to monitor");
    }

    // Trigger cleanup
    list.clear();
    System.gc();
    System.gc();

    Await.until(
        "Both cleanups should complete despite exception",
        ofSeconds(10),
        () -> cleanupCount.get() == 1 && secondCleaned.get()
    );

    if (JavaVersion.getRuntimeVersion() == JavaVersion.v1_8) {
      Await.until(
          "Cleanup thread should stop after all objects are cleaned",
          ofSeconds(10),
          () -> !t.isThreadRunning()
      );
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
}
