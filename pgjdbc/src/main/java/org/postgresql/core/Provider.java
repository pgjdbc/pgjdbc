/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

/**
 * Represents a provider of results.
 *
 * @param <T> the type of results provided by this provider
 */
public interface Provider<T> {

  /**
   * Gets a result.
   *
   * @return a result
   */
  T get();
}
