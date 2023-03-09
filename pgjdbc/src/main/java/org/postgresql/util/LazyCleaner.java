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

/**
 * LazyCleaner is a utility class that allows to register objects for deferred cleanup.
 * <p>Note: this is a driver-internal class</p>
 */
public class LazyCleaner {
  private static final Logger LOGGER = Logger.getLogger(LazyCleaner.class.getName());
  private static final LazyCleaner instance =
      new LazyCleaner(
          Duration.ofMillis(Long.getLong("pgjdbc.config.cleanup.thread.ttl", 30000)),
          "PostgreSQL-JDBC-Cleaner"
      );

  public interface Cleanable<T extends Throwable> {
    void clean() throws T;
  }

  public interface CleaningAction<T extends Throwable> {
    void onClean(boolean leak) throws T;
  }

  private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
  private final long threadTtl;
  private final ThreadFactory threadFactory;
  private boolean threadRunning = false;
  private int watchedCount = 0;
  private @Nullable Node<?> first;

  /**
   * Returns a default cleaner instance.
   * <p>Note: this is driver-internal API.</p>
   * @return the instance of LazyCleaner
   */
  public static LazyCleaner getInstance() {
    return instance;
  }

  public LazyCleaner(Duration threadTtl, final String threadName) {
    this(threadTtl, runnable -> {
      Thread thread = new Thread(runnable, threadName);
      thread.setDaemon(true);
      return thread;
    });
  }

  private LazyCleaner(Duration threadTtl, ThreadFactory threadFactory) {
    this.threadTtl = threadTtl.toMillis();
    this.threadFactory = threadFactory;
  }

  public <T extends Throwable> Cleanable<T> register(Object obj, CleaningAction<T> action) {
    assert obj != action : "object handle should not be the same as cleaning action, otherwise"
        + " the object will never become phantom reachable, so the action will never trigger";
    return add(new Node<T>(obj, action));
  }

  public synchronized int getWatchedCount() {
    return watchedCount;
  }

  public synchronized boolean isThreadRunning() {
    return threadRunning;
  }

  private synchronized boolean checkEmpty() {
    if (first == null) {
      threadRunning = false;
      return true;
    }
    return false;
  }

  private synchronized <T extends Throwable> Node<T> add(Node<T> node) {
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
            // Clear setContextClassLoader to avoid leaking the classloader
            Thread.currentThread().setContextClassLoader(null);
            Thread.currentThread().setUncaughtExceptionHandler(null);
            // Node extends PhantomReference, so this cast is safe
            Node<?> ref = (Node<?>) queue.remove(threadTtl);
            if (ref == null) {
              if (checkEmpty()) {
                break;
              }
              continue;
            }
            try {
              ref.onClean(true);
            } catch (Throwable e) {
              if (e instanceof InterruptedException) {
                // This could happen if onClean uses sneaky-throws
                LOGGER.log(Level.WARNING, "Unexpected interrupt while executing onClean", e);
                throw e;
              }
              // Should not happen if cleaners are well-behaved
              LOGGER.log(Level.WARNING, "Unexpected exception while executing onClean", e);
            }
          } catch (InterruptedException e) {
            if (LazyCleaner.this.checkEmpty()) {
              LOGGER.log(
                  Level.FINE,
                  "Cleanup queue is empty, and got interrupt, will terminate the cleanup thread"
              );
              break;
            }
            LOGGER.log(Level.FINE, "Ignoring interrupt since the cleanup queue is non-empty");
          } catch (Throwable e) {
            // Ignore exceptions from the cleanup action
            LOGGER.log(Level.WARNING, "Unexpected exception in cleaner thread main loop", e);
          }
        }
      }
    });
    if (thread != null) {
      thread.start();
      return true;
    }
    LOGGER.log(Level.WARNING, "Unable to create cleanup thread");
    return false;
  }

  private synchronized boolean remove(Node<?> node) {
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

  private class Node<T extends Throwable> extends PhantomReference<Object> implements Cleanable<T>,
      CleaningAction<T> {
    private final @Nullable CleaningAction<T> action;
    private @Nullable Node<?> prev;
    private @Nullable Node<?> next;

    Node(Object referent, CleaningAction<T> action) {
      super(referent, queue);
      this.action = action;
      //Objects.requireNonNull(referent); // poor man`s reachabilityFence
    }

    @Override
    public void clean() throws T {
      onClean(false);
    }

    @Override
    public void onClean(boolean leak) throws T {
      if (!remove(this)) {
        return;
      }
      if (action != null) {
        action.onClean(leak);
      }
    }
  }
}
