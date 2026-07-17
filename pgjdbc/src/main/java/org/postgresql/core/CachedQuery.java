/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.CanEstimateSize;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Stores information on the parsed JDBC query. It is used to cut parsing overhead when executing
 * the same query through {@link java.sql.Connection#prepareStatement(String)}.
 */
public class CachedQuery implements CanEstimateSize {
  /**
   * Cache key. {@link String} or {@code org.postgresql.util.CanEstimateSize}.
   */
  public final Object key;
  public final Query query;
  public final boolean isFunction;

  private int executeCount;
  private @Nullable Map<String, Integer> callableParameterNameIndexMap;
  private int callableParameterNameIndexMapEpoch;

  public CachedQuery(Object key, Query query, boolean isFunction) {
    assert key instanceof String || key instanceof CanEstimateSize
        : "CachedQuery.key should either be String or implement CanEstimateSize."
        + " Actual class is " + key.getClass();
    this.key = key;
    this.query = query;
    this.isFunction = isFunction;
  }

  public void increaseExecuteCount() {
    if (executeCount < Integer.MAX_VALUE) {
      executeCount++;
    }
  }

  public void increaseExecuteCount(int inc) {
    int newValue = executeCount + inc;
    if (newValue > 0) { // if overflows, just ignore the update
      executeCount = newValue;
    }
  }

  /**
   * Number of times this statement has been used.
   *
   * @return number of times this statement has been used
   */
  public int getExecuteCount() {
    return executeCount;
  }

  /**
   * Map of (folded) CallableStatement parameter name to 1-based JDBC index for this call, cached
   * so that repeated executions of the same callable SQL do not re-query the catalog for the
   * routine's argument names. The mapping is derived from the catalog, so it is tied to the
   * connection's type-cache epoch and is treated as absent once a DDL statement bumps the epoch
   * (the routine's signature may have changed).
   *
   * @param typeCacheEpoch the connection's current type-cache epoch
   * @return the cached parameter-name map, or {@code null} if not resolved yet or stale
   */
  public @Nullable Map<String, Integer> getCallableParameterNameIndexMap(int typeCacheEpoch) {
    if (callableParameterNameIndexMap != null && callableParameterNameIndexMapEpoch == typeCacheEpoch) {
      return callableParameterNameIndexMap;
    }
    return null;
  }

  /**
   * Caches the (folded) parameter name to 1-based JDBC index map for this callable SQL, stamped
   * with the type-cache epoch it was resolved under.
   *
   * @param callableParameterNameIndexMap the resolved parameter-name map
   * @param typeCacheEpoch the type-cache epoch the map was resolved under
   */
  public void setCallableParameterNameIndexMap(Map<String, Integer> callableParameterNameIndexMap,
      int typeCacheEpoch) {
    this.callableParameterNameIndexMap = callableParameterNameIndexMap;
    this.callableParameterNameIndexMapEpoch = typeCacheEpoch;
  }

  @Override
  public long getSize() {
    long queryLength;
    if (key instanceof String) {
      queryLength = ((String) key).length() * 2L; // 2 bytes per char, revise with Java 9's compact strings
    } else {
      queryLength = ((CanEstimateSize) key).getSize();
    }
    return queryLength * 2 /* original query and native sql */
        + 100L /* entry in hash map, CachedQuery wrapper, etc */;
  }

  @Override
  public String toString() {
    return "CachedQuery{"
        + "executeCount=" + executeCount
        + ", query=" + query
        + ", isFunction=" + isFunction
        + '}';
  }
}
