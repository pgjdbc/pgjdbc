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
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.time.Duration;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LazyCleaner is a utility class that allows to register objects for deferred cleanup.
 *
 * <p>This is the Java 8 compatible implementation that uses PhantomReferences
 * and ForkJoinPool for deferred cleanup operations.</p>
 *
 * <p>On Java 11+, this class is replaced by a version that uses the native
 * {@link java.lang.ref.Cleaner} API via the multi-release JAR mechanism.</p>
 *
 * <p>Note: this is a driver-internal class</p>
 */
public class LazyCleanerImpl implements LazyCleaner {
  private static final Logger LOGGER = Logger.getLogger(LazyCleanerImpl.class.getName());
  private static final LazyCleanerImpl instance = new LazyCleanerImpl(
      "PostgreSQL-JDBC-Cleaner",
      Duration.ofMillis(Long.getLong("pgjdbc.config.cleanup.thread.ttl", 30000))
  );

  private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
  private final String threadName;
  private final Duration threadTtl;
  private boolean threadRunning;
  private @Nullable Node<?> first;

  /**
   * Creates a LazyCleaner with the specified configuration.
   *
   * @param threadName the name for the cleanup thread
   * @param threadTtl the maximum time the cleanup thread will wait for references
   */
  public LazyCleanerImpl(String threadName, Duration threadTtl) {
    this.threadName = threadName;
    this.threadTtl = threadTtl;
  }

  /**
   * Returns a default cleaner instance.
   *
   * <p>Note: this is driver-internal API.</p>
   * @return the instance of LazyCleaner
   */
  public static LazyCleanerImpl getInstance() {
    return instance;
  }

  @Override
  public <T extends Throwable> Cleanable<T> register(Object obj, CleaningAction<T> action) {
    assert obj != action : "object handle should not be the same as cleaning action, otherwise"
        + " the object will never become phantom reachable, so the action will never trigger";
    return add(new Node<>(obj, action));
  }

  /**
   * Returns whether the cleanup thread is currently running.
   *
   * @return true if cleanup operations are active
   */
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

    if (!threadRunning) {
      threadRunning = startThread();
    }
    return node;
  }

  /**
   * RefQueueBlocker retrieves references from the reference queue without blocking ForkJoinPool
   * CPU threads.
   *
   * @param <T> the type of the objects referenced by the {@link Reference}s in the queue
   */
  private static class RefQueueBlocker<T> implements ForkJoinPool.ManagedBlocker {
    private final ReferenceQueue<T> queue;
    private final String threadName;
    private @Nullable Reference<? extends T> ref;
    private final long blockTimeoutMillis;
    private final BooleanSupplier shouldTerminate;

    RefQueueBlocker(ReferenceQueue<T> queue, String threadName, Duration blockTimeout, BooleanSupplier shouldTerminate) {
      this.queue = queue;
      this.threadName = threadName;
      this.blockTimeoutMillis = blockTimeout.toMillis();
      this.shouldTerminate = shouldTerminate;
    }

    @Override
    public boolean isReleasable() {
      if (ref != null || shouldTerminate.getAsBoolean()) {
        return true; // already have a ref from a previous call
      }
      // non-blocking check
      ref = queue.poll();
      // no need to block if we already have a ref from a previous call
      return ref != null;
    }

    @Override
    public boolean block() throws InterruptedException {
      if (isReleasable()) {
        return true;
      }
      Thread currentThread = Thread.currentThread();
      // ForkJoinPool reuses threads, so we set the thread name just for the blocking operation
      String oldName = currentThread.getName();
      try {
        currentThread.setName(threadName);
        // Perform blocking operation
        ref = queue.remove(blockTimeoutMillis);
      } finally {
        currentThread.setName(oldName);
      }
      return false;
    }

    public @Nullable Reference<? extends T> drainOne() {
      Reference<? extends T> ref = this.ref;
      this.ref = null;
      return ref;
    }
  }

  private boolean startThread() {
    // We use ForkJoinPool to work around Thread.inheritedAccessControlContext memory leak
    // Java creates FJP threads without caller's access control context, thus we reduce
    // the surface for the leak.
    ForkJoinPool.commonPool().execute(
        () -> {
          // Clear setContextClassLoader to avoid leaking the classloader
          Thread.currentThread().setContextClassLoader(null);
          RefQueueBlocker<Object> blocker =
              new RefQueueBlocker<>(queue, threadName, threadTtl, this::checkEmpty);
          while (!checkEmpty()) {
            try {
              ForkJoinPool.managedBlock(blocker);
              Node<?> ref = (Node<?>) blocker.drainOne();
              if (ref != null) {
                ref.onClean(true);
              }
            } catch (InterruptedException e) {
              if (!blocker.isReleasable()) {
                LOGGER.log(Level.FINE, "Got interrupt and the cleanup queue is empty, will terminate the cleanup thread");
                break;
              }
              LOGGER.log(Level.FINE, "Got interrupt and the cleanup queue is NOT empty. Will ignore the interrupt");
            } catch (Throwable e) {
              LOGGER.log(Level.WARNING, "Unexpected exception while executing onClean", e);
            }
          }
        }
    );
    return true;
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

    return true;
  }

  private class Node<T extends Throwable> extends PhantomReference<Object> implements Cleanable<T>,
      CleaningAction<T> {
    private final CleaningAction<T> action;
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
      action.onClean(leak);
    }
  }
}
