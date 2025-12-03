/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.ref.Cleaner;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LazyCleaner is a utility class that allows to register objects for deferred cleanup.
 *
 * <p>This is the Java 11+ implementation that uses the native {@link Cleaner} API
 * introduced in Java 9+ for deferred cleanup operations.</p>
 *
 * <p>This class replaces the Java 8 PhantomReference-based implementation via the
 * multi-release JAR mechanism when running on Java 11+.</p>
 *
 * <p>Note: this is a driver-internal class</p>
 */
public class LazyCleanerImpl implements LazyCleaner {
  private static final Logger LOGGER = Logger.getLogger(LazyCleanerImpl.class.getName());
  private static final LazyCleanerImpl instance = new LazyCleanerImpl(
      "PostgreSQL-JDBC-Cleaner",
      Duration.ofMillis(Long.getLong("pgjdbc.config.cleanup.thread.ttl", 30000))
  );

  private final Cleaner cleaner;

  /**
   * Creates a LazyCleaner with the specified configuration.
   *
   * <p>Note: The {@code threadName} and {@code threadTtl} parameters are ignored
   * in this implementation since the JVM manages Cleaner threads internally.</p>
   *
   * @param threadName the name for the cleanup thread (ignored, for API compatibility)
   * @param threadTtl the maximum time the cleanup thread will wait (ignored, for API compatibility)
   */
  public LazyCleanerImpl(String threadName, Duration threadTtl) {
    // threadName and threadTtl are ignored in Java 11+ since Cleaner manages its own threads
    this.cleaner = Cleaner.create();
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

    // Wrapper is needed to prevent double cleanup (e.g. user-managed cleanups and automatic cleanup)
    CleanableWrapper<T> wrapper = new CleanableWrapper<>(action);

    // Register with the native Cleaner, using the wrapper's cleanup method
    Cleaner.Cleanable nativeCleanable = cleaner.register(obj, wrapper::leakDetected);

    // Add the reference so the cleanable could be deregistered when user calls clean()
    wrapper.setNativeCleanable(nativeCleanable);
    return wrapper;
  }

  /**
   * Returns whether the cleanup thread is currently running.
   *
   * <p>For the Java 11+ implementation, this always returns false since the Cleaner
   * manages its own thread pool internally and there is no dedicated cleanup thread.</p>
   *
   * @return false, since the Cleaner manages threads internally
   */
  public boolean isThreadRunning() {
    // The Cleaner manages its own thread pool, so we return false meaning "there's no extra cleanup thread running"
    return false;
  }

  /**
   * Wrapper that adapts the native Cleaner.Cleanable to our Cleanable interface.
   */
  private static class CleanableWrapper<T extends Throwable> implements Cleanable<T> {
    private Cleaner.@Nullable Cleanable nativeCleanable;
    private volatile @Nullable CleaningAction<T> action;

    CleanableWrapper(CleaningAction<T> action) {
      this.action = action;
    }

    void setNativeCleanable(Cleaner.Cleanable nativeCleanable) {
      this.nativeCleanable = nativeCleanable;
    }

    private synchronized @Nullable CleaningAction<T> getCleaningAction() {
      CleaningAction<T> action = this.action;
      this.action = null;
      return action;
    }

    /**
     * Performs the actual cleanup if it hasn't been done yet.
     * This method is called both from manual clean() and from the Cleaner thread.
     */
    void leakDetected() {
      CleaningAction<T> cleaningAction = getCleaningAction();
      if (cleaningAction == null) {
        return;
      }
      try {
        cleaningAction.onClean(true);
      } catch (Throwable e) {
        if (e instanceof InterruptedException) {
          LOGGER.log(Level.WARNING, "Unexpected interrupt while executing onClean", e);
        } else {
          // Should not happen if cleaners are well-behaved
          LOGGER.log(Level.WARNING, "Unexpected exception while executing onClean", e);
        }
      }
    }

    @Override
    public void clean() throws T {
      CleaningAction<T> cleaningAction = getCleaningAction();
      if (cleaningAction == null) {
        return;
      }

      // Deregister from native Cleaner to prevent automatic cleanup
      // This is safe to call even if cleanup already happened
      Cleaner.Cleanable nativeCleanable = this.nativeCleanable;
      if (nativeCleanable != null) {
        // just in case
        this.nativeCleanable = null;
        nativeCleanable.clean();
      }

      // Call performCleanup with leak=false for manual cleanup
      // We rethrow the exceptions so callers can handle them
      cleaningAction.onClean(false);
    }
  }
}
