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
   * Loads a class named by a connection property and validates that it is assignable to the
   * expected type. The class is loaded with {@code initialize=false} so that its static
   * initialiser does not run until the type check has passed.
   *
   * <p>The classloaders to try, and their order, come from {@code strategy}. The first classloader
   * that resolves the name wins; a {@link ClassNotFoundException} from one classloader falls through
   * to the next. A class that resolves but is not a subtype of {@code expectedClass} fails fast with
   * a {@link ClassCastException} rather than falling through.</p>
   *
   * @param className the name of the class to load
   * @param expectedClass the expected superclass or interface
   * @param strategy the order in which to try the classloaders
   * @param driverClassLoader the driver's own classloader
   * @param <T> the expected type
   * @return the loaded class as a subclass of the expected type
   * @throws ClassNotFoundException if none of the classloaders can find the class
   */
  public static <T> Class<? extends T> forName(String className, Class<T> expectedClass,
      ClassLoaderStrategy strategy, @Nullable ClassLoader driverClassLoader)
      throws ClassNotFoundException {
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    ClassNotFoundException firstFailure = null;
    for (ClassLoader classLoader : strategy.classLoaders(driverClassLoader, contextClassLoader)) {
      try {
        return Class.forName(className, false, classLoader).asSubclass(expectedClass);
      } catch (ClassNotFoundException e) {
        if (firstFailure == null) {
          firstFailure = e;
        } else {
          firstFailure.addSuppressed(e);
        }
      }
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
    throw new ClassNotFoundException(className);
  }

  /**
   * Loads a class by name from the given classloader only, validating that it is assignable to the
   * expected type. A {@code null} classLoader means the bootstrap classloader, matching
   * {@link Class#forName(String, boolean, ClassLoader)}.
   *
   * @param className the name of the class to load
   * @param expectedClass the expected superclass or interface
   * @param classLoader the classloader to use
   * @param <T> the expected type
   * @return the loaded class as a subclass of the expected type
   * @throws ClassNotFoundException if the class cannot be found
   * @deprecated use {@link #forName(String, Class, ClassLoaderStrategy, ClassLoader)}, which also
   *     lets the caller fall back to the thread context classloader
   */
  @Deprecated
  public static <T> Class<? extends T> forName(String className, Class<T> expectedClass,
      @Nullable ClassLoader classLoader) throws ClassNotFoundException {
    return Class.forName(className, false, classLoader).asSubclass(expectedClass);
  }
}
