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

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.time.Duration;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LazyCleaner {
  private static final Logger LOGGER = Logger.getLogger(LazyCleaner.class.getName());
  private static final LazyCleaner instance = new LazyCleaner(Duration.ofMillis(100), "Cleaner");

  public interface Cleanable {
    void clean();
  }

  public interface CleaningAction<T extends Throwable> {
    void clean(boolean leak) throws T;
  }

  private final ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
  private final long threadTtl;
  private final ThreadFactory threadFactory;
  private boolean threadRunning = false;
  private int watchedCount = 0;
  private @Nullable Node first;

  /**
   * This is a driver-internal class
   * @return the instance of LazyCleaner
   */
  public static LazyCleaner getInstance() {
    return instance;
  }

  private LazyCleaner(Duration threadTtl, final String threadName) {
    this(threadTtl, new ThreadFactory() {
      public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, threadName);
        thread.setDaemon(true);
        return thread;
      }
    });
  }

  private LazyCleaner(Duration threadTtl, ThreadFactory threadFactory) {
    this.threadTtl = threadTtl.toMillis();
    this.threadFactory = threadFactory;
  }

  public Cleanable register(Object obj, CleaningAction action) {
    return add(new Node(obj, action));
  }

  public int getWatchedCount() {
    return watchedCount;
  }

  public boolean isThreadRunning() {
    return threadRunning;
  }

  private synchronized boolean checkEmpty() {
    if (first == null) {
      threadRunning = false;
      return true;
    }
    return false;
  }

  private synchronized Node add(Node node) {
    if (first != null) {
      node.next = first;
      first.prev = node;
    }
    first = node;
    watchedCount++;

    if (!threadRunning) {
      threadRunning = startThread();
    }
    return node;
  }

  private boolean startThread() {
    Thread thread = threadFactory.newThread(new Runnable() {
      public void run() {
        while (true) {
          try {
            Node ref = (Node) queue.remove(threadTtl);
            if (ref != null) {
              ref.clean(true);
            } else if (LazyCleaner.this.checkEmpty()) {
              break;
            }
          } catch (Throwable e) {
            // Ignore exceptions from the cleanup action (including interruption of cleanup thread)
            LOGGER.log(Level.WARNING, "Unexpected exception in cleaner thread main loop", e);
          }
        }
      }
    });
    if (thread != null) {
      thread.start();
      return true;
    }
    LOGGER.log(Level.WARNING, "Unable to create thread ");
    return false;
  }

  private synchronized boolean remove(Node node) {
    // If already removed, do nothing
    if (node.next == node) {
      return false;
    }

    // Update list
    if (first == node) {
      first = node.next;
    }
    if (node.next != null) {
      node.next.prev = node.prev;
    }
    if (node.prev != null) {
      node.prev.next = node.next;
    }

    // Indicate removal by pointing the cleaner to itself
    node.next = node;
    node.prev = node;

    watchedCount--;
    return true;
  }

  private class Node extends PhantomReference<Object> implements Cleanable, CleaningAction {
    private final CleaningAction action;
    private @Nullable Node prev = null;
    private @Nullable Node next = null;

    Node(Object referent, CleaningAction action) {
      super(referent, queue);
      this.action = action;
      //Objects.requireNonNull(referent); // poor man`s reachabilityFence
    }

    public void clean() {
      clean(false);
    }

    public void clean(boolean leak) {
      if (!remove(this)) {
        return;
      }
      try {
        action.clean(leak);
      } catch (Throwable e) {
        // Should not happen if cleaners are well-behaved
        LOGGER.log(Level.WARNING, "Unexpected exception in cleaner thread", e);
      }
    }
  }
}
