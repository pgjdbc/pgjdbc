/*-------------------------------------------------------------------------
*
* Copyright (c) 2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.jdbc;

/**
 * Serves as a cache key for callable statements. For regular statements, just sql string is used as
 * a key.
 */
class CallableQueryKey {
  public final String sql;

  public CallableQueryKey(String sql) {
    this.sql = sql;
  }

  @Override
  public String toString() {
    return sql;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CallableQueryKey)) {
      return false;
    }

    CallableQueryKey that = (CallableQueryKey) o;

    return sql == null ? that.sql == null : sql.equals(that.sql);

  }

  @Override
  public int hashCode() {
    return sql == null ? 0 : sql.hashCode();
  }
}
