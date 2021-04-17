/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

/**
 * A similar version of Java 8 java.util.function.Supplier interface that allows returning nulls.
 */
@FunctionalInterface
public interface Supplier<T> {

  /**
   * Returns next result.
   *
   * @return next result
   */
  T get();
}
