/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.jdbc.EscapeSyntaxCallMode;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Serves as a cache key for {@link java.sql.CallableStatement}.
 * Callable statements require some special parsing before use (due to JDBC {@code {?= call...}}
 * syntax, thus a special cache key class is used to trigger proper parsing for callable statements.
 */
class CallableQueryKey extends BaseQueryKey {
  /**
   * Whether this key selects the SQL for a call that registers two or more OUT parameters.
   *
   * @see Parser#modifyJdbcCall(String, boolean, int, EscapeSyntaxCallMode, boolean)
   */
  final boolean multipleOutParameters;

  CallableQueryKey(String sql, boolean multipleOutParameters) {
    super(sql, true, true);
    this.multipleOutParameters = multipleOutParameters;
  }

  @Override
  public String toString() {
    return "CallableQueryKey{"
        + "sql='" + sql + '\''
        + ", isParameterized=" + isParameterized
        + ", escapeProcessing=" + escapeProcessing
        + ", multipleOutParameters=" + multipleOutParameters
        + '}';
  }

  @Override
  public int hashCode() {
    return super.hashCode() * 31 + (multipleOutParameters ? 1 : 0);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return super.equals(o) && multipleOutParameters == ((CallableQueryKey) o).multipleOutParameters;
  }
}
