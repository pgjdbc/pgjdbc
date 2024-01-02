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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class LazyCleanerTest {
  @Test
  void phantomCleaner() throws InterruptedException {
    List<Object> list = new ArrayList<>(Arrays.asList(
        new Object(), new Object(), new Object()));

    LazyCleaner t = new LazyCleaner(ofSeconds(5), "Cleaner");

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
    assertEquals(
        list.size(),
        t.getWatchedCount(),
        "All objects are strongly-reachable, so getWatchedCount should reflect it"
    );

    assertTrue(t.isThreadRunning(),
        "cleanup thread should be running, and it should wait for the leaks");

    cleaners.get(1).clean();

    assertEquals(
        list.size() - 1,
        t.getWatchedCount(),
        "One object has been released properly, so getWatchedCount should reflect it"
    );

    list.set(0, null);
    System.gc();
    System.gc();

    Await.until(
        "One object was released, and another one has leaked, so getWatchedCount should reflect it",
        ofSeconds(5),
        () -> t.getWatchedCount() == list.size() - 2
    );

    list.clear();
    System.gc();
    System.gc();

    Await.until(
        "The cleanup thread should detect leaks and terminate within 5-10 seconds after GC",
        ofSeconds(10),
        () -> !t.isThreadRunning()
    );

    assertEquals(
        Arrays.asList("LEAK", "NO LEAK", "LEAK").toString(),
        Arrays.asList(collected).toString(),
        "Second object has been released properly, so it should be reported as NO LEAK"
    );
  }

  @Test
  void getThread() throws InterruptedException {
    String threadName = UUID.randomUUID().toString();
    LazyCleaner t = new LazyCleaner(ofSeconds(5), threadName);
    List<Object> list = new ArrayList<>();
    list.add(new Object());
    LazyCleaner.Cleanable<IllegalStateException> cleanable =
        t.register(
            list.get(0),
            leak -> {
              throw new IllegalStateException("test exception from CleaningAction");
            }
        );
    assertTrue(t.isThreadRunning(),
        "cleanup thread should be running, and it should wait for the leaks");
    Thread thread = getThreadByName(threadName);
    thread.interrupt();
    Await.until(
        "The cleanup thread should ignore the interrupt since there's one object to monitor",
        ofSeconds(10),
        () -> !thread.isInterrupted()
    );
    assertThrows(
        IllegalStateException.class,
        cleanable::clean,
        "Exception from cleanable.clean() should be rethrown"
    );
    thread.interrupt();
    Await.until(
        "The cleanup thread should exit shortly after interrupt as there's no leaks to monitor",
        ofSeconds(1),
        () -> !t.isThreadRunning()
    );
  }

  public static Thread getThreadByName(String threadName) {
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (t.getName().equals(threadName)) {
        return t;
      }
    }
    throw new IllegalStateException("Cleanup thread  " + threadName + " not found");
  }
}
