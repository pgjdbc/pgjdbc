/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.jdbc.PlaceholderStyle;
import org.postgresql.util.CanEstimateSize;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * This class is used as a cache key for simple statements that have no "returning columns".
 * Prepared statements that have no returning columns use just {@code String sql} as a key.
 * Simple and Prepared statements that have returning columns use {@link QueryWithReturningColumnsKey}
 * as a cache key.
 */
class BaseQueryKey implements CanEstimateSize {
  public final String sql;
  public final boolean escapeProcessing;
  public final PlaceholderStyle placeholderStyle;

  BaseQueryKey(String sql, boolean escapeProcessing, PlaceholderStyle placeholderStyle) {
    this.sql = sql;
    this.escapeProcessing = escapeProcessing;
    this.placeholderStyle = placeholderStyle;
  }

  @Override
  public String toString() {
    return "BaseQueryKey{"
        + "sql='" + sql + '\''
        + ", escapeProcessing=" + escapeProcessing
        + ", placeholderStyle=" + placeholderStyle
        + '}';
  }

  @Override
  public long getSize() {
    if (sql == null) { // just in case
      return 16;
    }
    return 16 + sql.length() * 2L; // 2 bytes per char, revise with Java 9's compact strings
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    BaseQueryKey that = (BaseQueryKey) o;

    if (escapeProcessing != that.escapeProcessing) {
      return false;
    }
    if (placeholderStyle != that.placeholderStyle) {
      return false;
    }
    return sql != null ? sql.equals(that.sql) : that.sql == null;

  }

  @Override
  public int hashCode() {
    int result = sql != null ? sql.hashCode() : 0;
    result = 31 * result + (escapeProcessing ? 1 : 0);
    result = 31 * result + placeholderStyle.hashCode();
    return result;
  }
}
