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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LazyCleanerTest {
  @Test
  public void testPhantomCleaner() throws InterruptedException {
    List<Object> list = new ArrayList<Object>(Arrays.asList(
        new Object(), new Object(), new Object()));

    final LazyCleaner t = new LazyCleaner(10, "Cleaner");
    assertFalse(t.isThreadRunning());
    assertEquals(0, t.getWatchedCount());

    final Map<Integer, Boolean> collected = new HashMap<Integer, Boolean>();
    List<LazyCleaner.Cleanable> cleaners = new ArrayList<LazyCleaner.Cleanable>();
    for (int i = 0; i < list.size(); i++) {
      final int ii = i;
      cleaners.add(t.register(list.get(i), new LazyCleaner.CleaningAction() {
        public void onClean(boolean leak) throws Exception {
          collected.put(ii, leak);
        }
      }));
    }
    assertEquals(3, t.getWatchedCount());
    Await.until(new Await.Condition() {
      public boolean get() {
        return t.isThreadRunning();
      }
    });

    cleaners.get(1).clean();

    list.clear();
    System.gc();

    Await.until(new Await.Condition() {
      public boolean get() {
        return !t.isThreadRunning();
      }
    });

    assertEquals(0, t.getWatchedCount());
    assertArrayEquals(new Object[]{true, false, true},  collected.values().toArray());
  }

  @Test
  public void testForceThreadAlive() throws InterruptedException {
    final LazyCleaner t = new LazyCleaner(10, "Cleaner");

    for (int i = 0; i < 5; i++) {
      assertFalse(t.isThreadRunning());
      assertEquals(0, t.getWatchedCount());

      assertSame(t, t.setKeepThreadAlive(true));
      Await.until(new Await.Condition() {
        public boolean get() {
          return t.isThreadRunning();
        }
      });
      assertEquals(1, t.getWatchedCount());

      t.setKeepThreadAlive(true);
      assertEquals(1, t.getWatchedCount());

      t.setKeepThreadAlive(false);
      Await.until(new Await.Condition() {
        public boolean get() {
          return !t.isThreadRunning();
        }
      });
      assertEquals(0, t.getWatchedCount());
    }
  }

  @Test
  public void testGetThread() throws InterruptedException {
    String threadName = UUID.randomUUID().toString();
    final LazyCleaner t = new LazyCleaner(10, threadName);
    Object obj = new Object();
    t.register(obj, new LazyCleaner.CleaningAction() {
      public void onClean(boolean leak) throws Exception {
        throw new RuntimeException("abc");
      }
    });
    Await.until(new Await.Condition() {
      public boolean get() {
        return t.isThreadRunning();
      }
    });
    final Thread thread = getThreadByName(threadName);
    thread.interrupt();
    Await.until(new Await.Condition() {
      public boolean get() {
        return !thread.isInterrupted();
      }
    }); //will ignore interrupt

    obj = null;
    System.gc();
    Await.until(new Await.Condition() {
      public boolean get() {
        return !t.isThreadRunning();
      }
    });
  }

  public static Thread getThreadByName(String threadName) {
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (t.getName().equals(threadName)) return t;
    }
    return null;
  }
}
