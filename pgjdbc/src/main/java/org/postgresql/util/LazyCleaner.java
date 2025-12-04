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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LazyCleaner is a utility class that allows to register objects for deferred cleanup.
 *
 * <p>On Java 9+, this uses the native {@link java.lang.ref.Cleaner} API.
 * On Java 8, this uses a custom implementation based on PhantomReferences and ForkJoinPool.</p>
 *
 * <p>Note: this is a driver-internal class</p>
 */
public abstract class LazyCleaner {
  private static final Logger LOGGER = Logger.getLogger(LazyCleaner.class.getName());
  private static final LazyCleaner instance = create(
      "PostgreSQL-JDBC-Cleaner",
      Duration.ofMillis(Long.getLong("pgjdbc.config.cleanup.thread.ttl", 30000))
  );

  /**
   * Cleanable interface for objects that can be manually cleaned.
   *
   * @param <T> the type of exception that can be thrown during cleanup
   */
  public interface Cleanable<T extends Throwable> {
    void clean() throws T;
  }

  /**
   * CleaningAction interface for cleanup actions that are notified whether cleanup
   * occurred due to a leak (automatic) or manual cleanup.
   *
   * @param <T> the type of exception that can be thrown during cleanup
   */
  public interface CleaningAction<T extends Throwable> {
    void onClean(boolean leak) throws T;
  }

  /**
   * Returns a default cleaner instance.
   *
   * <p>Note: this is driver-internal API.</p>
   * @return the instance of LazyCleaner
   */
  public static LazyCleaner getInstance() {
    return instance;
  }

  /**
   * Registers an object for cleanup when it becomes phantom reachable.
   *
   * @param obj the object to monitor for cleanup (should not be the same as action)
   * @param action the action to perform when the object becomes unreachable
   * @param <T> the type of exception that can be thrown during cleanup
   * @return a Cleanable that can be used to manually trigger cleanup
   */
  public abstract <T extends Throwable> Cleanable<T> register(Object obj, CleaningAction<T> action);

  /**
   * Returns whether the cleanup thread is currently running.
   * For Java 9+ implementation, returns true if there are watched objects.
   * For legacy implementation, returns true if the cleanup thread is active.
   *
   * @return true if cleanup operations are active
   */
  public abstract boolean isThreadRunning();

  /**
   * Creates a new LazyCleaner instance with custom configuration.
   *
   * <p>On Java 9+, uses the native Cleaner API. The {@code threadTtl} parameter is ignored
   * since the JVM manages Cleaner threads, but {@code threadName} is used for the cleaner's
   * thread factory.</p>
   *
   * <p>On Java 8, uses a custom PhantomReference-based implementation that respects both
   * {@code threadName} and {@code threadTtl} parameters.</p>
   *
   * <p>Note: this is driver-internal API.</p>
   *
   * @param threadName the name for the cleanup thread
   * @param threadTtl the maximum time the cleanup thread will wait for references (Java 8 only)
   * @return a new LazyCleaner instance
   */
  public static LazyCleaner create(String threadName, Duration threadTtl) {
    try {
      // Use Java 11+ cleaner when available
      Class<? extends LazyCleaner> clazz =
          Class.forName(
                  "org.postgresql.util.Java11LazyCleaner",
                  false,
                  LazyCleaner.class.getClassLoader())
              .asSubclass(LazyCleaner.class);
      Constructor<? extends LazyCleaner> constructor = clazz.getDeclaredConstructor();
      return constructor.newInstance();
    } catch (ClassNotFoundException e) {
      // Java 11+ Cleaner not available, fall through to legacy implementation
      LOGGER.log(Level.FINE, "Java 11+ Cleaner API not available, using legacy implementation");
    } catch (InvocationTargetException e) {
      // Failed to instantiate Java11LazyCleaner, log and fall back to legacy
      LOGGER.log(Level.WARNING,
          "Failed to instantiate Java11LazyCleaner, falling back to legacy implementation", e.getCause());
    } catch (Throwable e) {
      // Failed to instantiate Java11LazyCleaner, log and fall back to legacy
      LOGGER.log(Level.WARNING,
          "Failed to instantiate Java11LazyCleaner, falling back to legacy implementation", e);
    }

    // Use legacy implementation for Java 8 compatibility
    return new Java8LazyCleaner(threadTtl, threadName);
  }
}
