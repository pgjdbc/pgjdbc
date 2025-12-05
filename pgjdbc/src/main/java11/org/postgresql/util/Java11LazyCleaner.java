/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.lang.ref.Cleaner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Java11LazyCleaner uses the native {@link Cleaner} API introduced in Java 9+
 * for deferred cleanup operations.
 *
 * <p>Note: this is a driver-internal class</p>
 */
class Java11LazyCleaner extends LazyCleaner {
  private static final Logger LOGGER = Logger.getLogger(Java11LazyCleaner.class.getName());

  private final Cleaner cleaner;

  /**
   * Creates a Java11LazyCleaner with default configuration.
   */
  Java11LazyCleaner() {
    this.cleaner = Cleaner.create();
  }

  @Override
  public <T extends Throwable> Cleanable<T> register(Object obj, CleaningAction<T> action) {
    assert obj != action : "object handle should not be the same as cleaning action, otherwise"
        + " the object will never become phantom reachable, so the action will never trigger";

    // Register with the native Cleaner
    Cleaner.Cleanable nativeCleanable = cleaner.register(obj, () -> {
      try {
        action.onClean(true);
      } catch (Throwable e) {
        if (e instanceof InterruptedException) {
          LOGGER.log(Level.WARNING, "Unexpected interrupt while executing onClean", e);
        } else {
          // Should not happen if cleaners are well-behaved
          LOGGER.log(Level.WARNING, "Unexpected exception while executing onClean", e);
        }
      }
    });

    return new CleanableWrapper<>(nativeCleanable, action);
  }

  @Override
  public boolean isThreadRunning() {
    // The Cleaner manages its own thread pool, so we return true if there are watched objects
    return true;
  }

  /**
   * Wrapper that adapts the native Cleaner.Cleanable to our Cleanable interface.
   */
  private static class CleanableWrapper<T extends Throwable> implements Cleanable<T> {
    private final Cleaner.Cleanable nativeCleanable;
    private final CleaningAction<T> action;
    private volatile boolean requiresCleanup = true;

    CleanableWrapper(
        Cleaner.Cleanable nativeCleanable,
        CleaningAction<T> action) {
      this.nativeCleanable = nativeCleanable;
      this.action = action;
    }

    private synchronized boolean requiresCleanup() {
      if (requiresCleanup) {
        requiresCleanup = false;
        return true;
      }
      return false;
    }

    @Override
    public void clean() throws T {
      // Prevent double cleaning
      if (!requiresCleanup()) {
        return;
      }

      try {
        // Call the action with leak=false since this is manual cleanup
        action.onClean(false);
      } finally {
        // Clean the native cleanable to prevent automatic cleanup
        nativeCleanable.clean();
      }
    }
  }
}
