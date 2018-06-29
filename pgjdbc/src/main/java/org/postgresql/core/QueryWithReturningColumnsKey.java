/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

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
  private int size; // query length cannot exceed MAX_INT

  QueryWithReturningColumnsKey(String sql, boolean isParameterized, boolean escapeProcessing,
      String[] columnNames) {
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
      for (String columnName: columnNames) {
        size += columnName.length() * 2L; // 2 bytes per char, revise with Java 9's compact strings
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
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }

    QueryWithReturningColumnsKey that = (QueryWithReturningColumnsKey) o;

    // Probably incorrect - comparing Object[] arrays with Arrays.equals
    return Arrays.equals(columnNames, that.columnNames);
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result + Arrays.hashCode(columnNames);
    return result;
  }
}
