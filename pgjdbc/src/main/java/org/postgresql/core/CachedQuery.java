/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.CanEstimateSize;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Stores information on the parsed JDBC query. It is used to cut parsing overhead when executing
 * the same query through {@link java.sql.Connection#prepareStatement(String)}.
 */
public class CachedQuery implements CanEstimateSize {
  /**
   * Cache key. {@link String} or {@code org.postgresql.jdbc.CallableQueryKey}. It is assumed that
   * {@code String.valueOf(key)*2} would give reasonable estimate of the number of retained bytes by
   * given key (see {@link #getSize}).
   */
  public final Object key;
  public final Query query;
  public final boolean isFunction;
  public final boolean outParmBeforeFunc;

  private final AtomicLong executeCount;

  public CachedQuery(Object key, Query query, boolean isFunction, boolean outParmBeforeFunc, AtomicLong counter) {
    this.key = key;
    this.query = query;
    this.isFunction = isFunction;
    this.outParmBeforeFunc = outParmBeforeFunc;
    this.executeCount = counter;
  }

  public void increaseExecuteCount() {
    executeCount.incrementAndGet();
  }

  public void increaseExecuteCount(int inc) {
    executeCount.addAndGet(inc);
  }

  /**
   * Number of times this statement has been used
   *
   * @return number of times this statement has been used
   */
  public int getExecuteCount() {
    return (int) Math.min(executeCount.get(), Integer.MAX_VALUE);
  }

  @Override
  public long getSize() {
    int queryLength = String.valueOf(key).length() * 2 /* 2 bytes per char */;
    return queryLength * 2 /* original query and native sql */
        + 100L /* entry in hash map, CachedQuery wrapper, etc */;
  }

  @Override
  public String toString() {
    return "CachedQuery{"
        + "executeCount=" + executeCount
        + ", query=" + query
        + ", isFunction=" + isFunction
        + ", outParmBeforeFunc=" + outParmBeforeFunc
        + '}';
  }
}
