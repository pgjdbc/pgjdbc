/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Utility class for safe class loading operations.
 */
public final class ClassUtils {

  private ClassUtils() {
    // Utility class
  }

  /**
   * Safely loads a class using the three-parameter Class.forName with initialize=false
   * and validates it's assignable to the expected type.
   *
   * @param className the name of the class to load
   * @param expectedClass the expected superclass or interface
   * @param classLoader the class loader to use
   * @param <T> the expected type
   * @return the loaded class as a subclass of the expected type
   * @throws ClassNotFoundException if the class cannot be found
   */
  public static <T> Class<? extends T> forName(String className, Class<T> expectedClass, @Nullable ClassLoader classLoader)
      throws ClassNotFoundException {
    return Class.forName(className, false, classLoader).asSubclass(expectedClass);
  }
}
