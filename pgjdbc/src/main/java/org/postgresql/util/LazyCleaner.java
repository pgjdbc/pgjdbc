/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

public interface LazyCleaner {
  /**
   * Cleanable interface for objects that can be manually cleaned.
   *
   * @param <T> the type of exception that can be thrown during cleanup
   */
  interface Cleanable<T extends Throwable> {
    void clean() throws T;
  }

  /**
   * CleaningAction interface for cleanup actions that are notified whether cleanup
   * occurred due to a leak (automatic) or manual cleanup.
   *
   * @param <T> the type of exception that can be thrown during cleanup
   */
  interface CleaningAction<T extends Throwable> {
    void onClean(boolean leak) throws T;
  }

  /**
   * Registers an object for cleanup when it becomes phantom reachable.
   *
   * @param obj the object to monitor for cleanup (should not be the same as action)
   * @param action the action to perform when the object becomes unreachable
   * @param <T> the type of exception that can be thrown during cleanup
   * @return a Cleanable that can be used to manually trigger cleanup
   */
  <T extends Throwable> Cleanable<T> register(Object obj, CleaningAction<T> action);
}
