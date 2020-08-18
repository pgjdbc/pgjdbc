/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

/**
 * A similar version of Java 8 java.util.function.Supplier interface to make code work on older Java version.
 */
public interface Supplier<T> {

  /**
   * Returns next result.
   *
   * @return next result
   */
  T get();
}
