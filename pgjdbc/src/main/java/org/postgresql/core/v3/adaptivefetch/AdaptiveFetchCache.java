/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3.adaptivefetch;

import org.postgresql.PGProperty;
import org.postgresql.core.Query;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The main purpose of this class is to handle adaptive fetching process. Adaptive fetching is used
 * to compute fetch size to fully use size defined by maxResultBuffer. Computing is made by dividing
 * maxResultBuffer size by max row result size noticed so far. Each query have separate adaptive
 * fetch size computed, but same queries have it shared. If adaptive fetch is turned on, first fetch
 * is going to be made with defaultRowFetchSize, next fetching of resultSet will be made with
 * computed adaptive fetch size. If adaptive fetch is turned on during fetching, then first fetching
 * made by ResultSet will be made with defaultRowFetchSize, next will use computed adaptive fetch
 * size. Property adaptiveFetch need properties defaultRowFetchSize and maxResultBuffer to work.
 */
public class AdaptiveFetchCache {

  private final Map<String, AdaptiveFetchCacheEntry> adaptiveFetchInfoMap;
  private boolean adaptiveFetch = false;
  private int minimumAdaptiveFetchSize = 0;
  private int maximumAdaptiveFetchSize = -1;
  private long maximumResultBufferSize = -1;

  public AdaptiveFetchCache(long maximumResultBufferSize, Properties info)
      throws SQLException {
    this.adaptiveFetchInfoMap = new HashMap<String, AdaptiveFetchCacheEntry>();

    this.adaptiveFetch = PGProperty.ADAPTIVE_FETCH.getBoolean(info);
    this.minimumAdaptiveFetchSize = PGProperty.ADAPTIVE_FETCH_MINIMUM.getInt(info);
    this.maximumAdaptiveFetchSize = PGProperty.ADAPTIVE_FETCH_MAXIMUM.getInt(info);

    this.maximumResultBufferSize = maximumResultBufferSize;
  }

  /**
   * Add query to being cached and computing adaptive fetch size.
   *
   * @param adaptiveFetch state of adaptive fetch, which should be used during adding query
   * @param query         query to be cached
   */
  public void addNewQuery(boolean adaptiveFetch, @NonNull Query query) {
    if (adaptiveFetch && maximumResultBufferSize != -1) {
      String sql = query.getNativeSql().trim();
      AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = adaptiveFetchInfoMap.get(sql);
      if (adaptiveFetchCacheEntry == null) {
        adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
      }
      adaptiveFetchCacheEntry.incrementCounter();

      adaptiveFetchInfoMap.put(sql, adaptiveFetchCacheEntry);
    }
  }

  /**
   * Update adaptive fetch size for given query.
   *
   * @param adaptiveFetch       state of adaptive fetch, which should be used during updating fetch
   *                            size for query
   * @param query               query to be updated
   * @param maximumRowSizeBytes max row size used during updating information about adaptive fetch
   *                            size for given query
   */
  public void updateQueryFetchSize(boolean adaptiveFetch, @NonNull Query query, int maximumRowSizeBytes) {
    if (adaptiveFetch && maximumResultBufferSize != -1) {
      String sql = query.getNativeSql().trim();
      AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = adaptiveFetchInfoMap.get(sql);
      if (adaptiveFetchCacheEntry != null) {
        int adaptiveMaximumRowSize = adaptiveFetchCacheEntry.getMaximumRowSizeBytes();
        if (adaptiveMaximumRowSize < maximumRowSizeBytes && maximumRowSizeBytes > 0) {
          int newFetchSize = (int) (maximumResultBufferSize / maximumRowSizeBytes);
          newFetchSize = adjustFetchSize(newFetchSize);

          adaptiveFetchCacheEntry.setMaximumRowSizeBytes(maximumRowSizeBytes);
          adaptiveFetchCacheEntry.setSize(newFetchSize);

          adaptiveFetchInfoMap.put(sql, adaptiveFetchCacheEntry);
        }
      }
    }
  }

  /**
   * Get adaptive fetch size for given query.
   *
   * @param adaptiveFetch state of adaptive fetch, which should be used during getting fetch size
   *                      for query
   * @param query         query to which we want get adaptive fetch size
   * @return adaptive fetch size for query or -1 if size doesn't exist/adaptive fetch state is false
   */
  public int getFetchSizeForQuery(boolean adaptiveFetch, @NonNull Query query) {
    if (adaptiveFetch && maximumResultBufferSize != -1) {
      String sql = query.getNativeSql().trim();
      AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = adaptiveFetchInfoMap.get(sql);
      if (adaptiveFetchCacheEntry != null) {
        return adaptiveFetchCacheEntry.getSize();
      }
    }
    return -1;
  }

  /**
   * Remove query information from caching.
   *
   * @param adaptiveFetch state of adaptive fetch, which should be used during removing fetch size
   *                      for query
   * @param query         query to be removed from caching
   */
  public void removeQuery(boolean adaptiveFetch, @NonNull Query query) {
    if (adaptiveFetch && maximumResultBufferSize != -1) {
      String sql = query.getNativeSql().trim();
      AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = adaptiveFetchInfoMap.get(sql);
      if (adaptiveFetchCacheEntry != null) {
        adaptiveFetchCacheEntry.decrementCounter();

        if (adaptiveFetchCacheEntry.getCounter() < 1) {
          adaptiveFetchInfoMap.remove(sql);
        } else {
          adaptiveFetchInfoMap.put(sql, adaptiveFetchCacheEntry);
        }
      }
    }
  }

  /**
   * Set maximum and minimum constraints on given value.
   *
   * @param actualSize value which should be the computed fetch size
   * @return value which meet the constraints
   */
  private int adjustFetchSize(int actualSize) {
    int size = adjustMaximumFetchSize(actualSize);
    size = adjustMinimumFetchSize(size);
    return size;
  }

  /**
   * Set minimum constraint on given value.
   *
   * @param actualSize value which should be the computed fetch size
   * @return value which meet the minimum constraint
   */
  private int adjustMinimumFetchSize(int actualSize) {
    if (minimumAdaptiveFetchSize == 0) {
      return actualSize;
    }
    if (minimumAdaptiveFetchSize > actualSize) {
      return minimumAdaptiveFetchSize;
    } else {
      return actualSize;
    }
  }

  /**
   * Set maximum constraint on given value.
   *
   * @param actualSize value which should be the computed fetch size
   * @return value which meet the maximum constraint
   */
  private int adjustMaximumFetchSize(int actualSize) {
    if (maximumAdaptiveFetchSize == -1) {
      return actualSize;
    }
    if (maximumAdaptiveFetchSize < actualSize) {
      return maximumAdaptiveFetchSize;
    } else {
      return actualSize;
    }
  }

  /**
   * Get state of adaptive fetch.
   *
   * @return state of adaptive fetch
   */
  public boolean getAdaptiveFetch() {
    return adaptiveFetch;
  }

  /**
   * Set state of adaptive fetch.
   *
   * @param adaptiveFetch desired state of adaptive fetch
   */
  public void setAdaptiveFetch(boolean adaptiveFetch) {
    this.adaptiveFetch = adaptiveFetch;
  }
}
