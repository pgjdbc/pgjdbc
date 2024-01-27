/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.jdbc.PlaceholderStyle;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Serves as a cache key for {@link java.sql.CallableStatement}.
 * Callable statements require some special parsing before use (due to JDBC {@code {?= call...}}
 * syntax, thus a special cache key class is used to trigger proper parsing for callable statements.
 */
class CallableQueryKey extends BaseQueryKey {

  CallableQueryKey(String sql) {
    super(sql, true, PlaceholderStyle.JDBC);
  }

  @Override
  public String toString() {
    return "CallableQueryKey{"
        + "sql='" + sql + '\''
        + ", escapeProcessing=" + escapeProcessing
        + ", placeholderStyle=" + placeholderStyle
        + '}';
  }

  @Override
  public int hashCode() {
    return super.hashCode() * 31;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    // Nothing interesting here, overriding equals to make hashCode and equals paired
    return super.equals(o);
  }
}
