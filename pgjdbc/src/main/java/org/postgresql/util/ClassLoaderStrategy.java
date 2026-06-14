/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.PGProperty;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Order in which the driver searches classloaders when loading a class named by a connection
 * property. The driver's own classloader can see only what is on its classpath, so in a non-flat
 * class path (for example an application server or an OSGi container) a user-supplied class may be
 * visible only through the {@link Thread#getContextClassLoader() thread context classloader}.
 *
 * <p>The class is <b>NOT</b> public API, so it is subject to change.</p>
 */
public enum ClassLoaderStrategy {

  /**
   * Use the driver's classloader only. This was the behaviour before the strategy became
   * configurable and is the safest choice when the thread context classloader is undefined.
   */
  DRIVER("driver"),

  /**
   * Try the driver's classloader first, then fall back to the thread context classloader. The
   * fallback heals environments where the class is reachable only through the context classloader,
   * while keeping the driver's classloader authoritative.
   */
  DRIVER_FIRST("driver-first"),

  /**
   * Try the thread context classloader first, then fall back to the driver's classloader. Use this
   * in containers that manage class loading and expect their context classloader to win, even when
   * the driver's classloader could resolve a class of the same name.
   */
  CONTEXT_FIRST("context-first");

  private static final ClassLoaderStrategy[] VALUES = values();

  public final String value;

  ClassLoaderStrategy(String value) {
    this.value = value;
  }

  /**
   * Resolves the strategy from the {@link PGProperty#CLASS_LOADER_STRATEGY} connection property.
   *
   * @param info connection properties
   * @return the configured strategy, or {@link #DRIVER_FIRST} when the property is absent
   * @throws PSQLException if the property holds an unknown value
   */
  public static ClassLoaderStrategy of(Properties info) throws PSQLException {
    String value = PGProperty.CLASS_LOADER_STRATEGY.getOrDefault(info);
    if (value == null) {
      return DRIVER_FIRST;
    }
    for (ClassLoaderStrategy strategy : VALUES) {
      if (strategy.value.equalsIgnoreCase(value)) {
        return strategy;
      }
    }
    throw new PSQLException(GT.tr("Invalid classLoaderStrategy value: {0}", value),
        PSQLState.INVALID_PARAMETER_VALUE);
  }

  /**
   * Returns the non-null classloaders to try, in priority order. A null input (for instance a null
   * context classloader) is dropped, so the list contains only classloaders the caller can use.
   *
   * @param driverClassLoader the driver's own classloader
   * @param contextClassLoader the thread context classloader
   * @return an ordered list of non-null classloaders to try
   */
  public List<ClassLoader> classLoaders(@Nullable ClassLoader driverClassLoader,
      @Nullable ClassLoader contextClassLoader) {
    switch (this) {
      case DRIVER:
        return singletonOrEmpty(driverClassLoader);
      case DRIVER_FIRST:
        return nonNullList(driverClassLoader, contextClassLoader);
      case CONTEXT_FIRST:
        return nonNullList(contextClassLoader, driverClassLoader);
      default:
        throw new IllegalStateException("Unexpected classLoaderStrategy: " + this);
    }
  }

  private static List<ClassLoader> singletonOrEmpty(@Nullable ClassLoader classLoader) {
    return classLoader == null ? Collections.emptyList() : Collections.singletonList(classLoader);
  }

  private static List<ClassLoader> nonNullList(@Nullable ClassLoader first,
      @Nullable ClassLoader second) {
    if (first == null) {
      return singletonOrEmpty(second);
    }
    if (second == null) {
      return Collections.singletonList(first);
    }
    return Arrays.asList(first, second);
  }
}
