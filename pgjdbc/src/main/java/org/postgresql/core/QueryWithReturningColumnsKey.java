/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;

/**
 * Cache key for a query that have some returning columns.
 * {@code columnNames} should contain non-quoted column names.
 * The parser will quote them automatically.
 * <p>There's a special case of {@code columnNames == new String[]{"*"}} that means all columns
 * should be returned. {@link Parser} is aware of that and does not quote {@code *}</p>
 */
class QueryWithReturningColumnsKey extends BaseQueryKey {
  public final String[] columnNames;
  private volatile int size; // query length cannot exceed MAX_INT

  QueryWithReturningColumnsKey(String sql, boolean isParameterized, boolean escapeProcessing,
      String @Nullable [] columnNames) {
    super(sql, isParameterized, escapeProcessing);
    if (columnNames == null) {
      // TODO: teach parser to fetch key columns somehow when no column names were given
      columnNames = new String[]{"*"};
    }
    this.columnNames = columnNames;
  }

  @Override
  public long getSize() {
    int size = this.size;
    if (size != 0) {
      return size;
    }
    size = (int) super.getSize();
    if (columnNames != null) {
      size += 16L; // array itself
      final JavaVersion runtimeVersion = JavaVersion.getRuntimeVersion();
      for (String columnName : columnNames) {
        size += runtimeVersion.size(columnName);
      }
    }
    this.size = size;
    return size;
  }

  @Override
  public String toString() {
    return "QueryWithReturningColumnsKey{"
        + "sql='" + sql + '\''
        + ", isParameterized=" + isParameterized
        + ", escapeProcessing=" + escapeProcessing
        + ", columnNames=" + Arrays.toString(columnNames)
        + '}';
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!super.equals(o)) {
      return false;
    }

    QueryWithReturningColumnsKey that = (QueryWithReturningColumnsKey) o;

    // this compares size of array and order of entries. each entry compared
    // as with Objects.equals(o1, o2)
    return Arrays.equals(columnNames, that.columnNames);
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result + Arrays.hashCode(columnNames);
    return result;
  }
}
