/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3.adaptivefetch;

import org.postgresql.PGProperty;
import org.postgresql.core.Query;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class AdaptiveFetchQueryMonitoring {

  private final Map<String, AdaptiveFetchQueryInfo> adaptiveFetchInfoMap;
  private boolean adaptiveFetch = false;
  private int minimumAdaptiveFetchSize = 0;
  private int maximumAdaptiveFetchSize = -1;
  private long maxResultBufferSize = -1;

  public AdaptiveFetchQueryMonitoring(long maxResultBufferSize, Properties info)
      throws SQLException {
    this.adaptiveFetchInfoMap = new HashMap<String, AdaptiveFetchQueryInfo>();

    this.adaptiveFetch = PGProperty.ADAPTIVE_FETCH.getBoolean(info);
    this.minimumAdaptiveFetchSize = PGProperty.ADAPTIVE_FETCH_MINIMUM.getInt(info);
    this.maximumAdaptiveFetchSize = PGProperty.ADAPTIVE_FETCH_MAXIMUM.getInt(info);

    this.maxResultBufferSize = maxResultBufferSize;
  }

  /**
   * Method to add query to being monitored and computing adaptive fetch size.
   *
   * @param adaptiveFetch state of adaptive fetch, which should be used during adding query
   * @param query         query to be monitored
   */
  public void addNewQuery(boolean adaptiveFetch, Query query) {
    if (adaptiveFetch && maxResultBufferSize != -1) {
      String sql = query.getNativeSql().trim();
      AdaptiveFetchQueryInfo adaptiveFetchQueryInfo = adaptiveFetchInfoMap.get(sql);
      if (adaptiveFetchQueryInfo == null) {
        adaptiveFetchQueryInfo = new AdaptiveFetchQueryInfo();
      }
      adaptiveFetchQueryInfo.setCounter(adaptiveFetchQueryInfo.getCounter() + 1);

      adaptiveFetchInfoMap.put(sql, adaptiveFetchQueryInfo);
    }
  }

  /**
   * Method to update adaptive fetch size for given query.
   *
   * @param adaptiveFetch state of adaptive fetch, which should be used during updating fetch size
   *                      for query
   * @param query         query to be updated
   * @param maxRowSize    max row size used during updating information about adaptive fetch size
   *                      for given query
   */
  public void updateQueryFetchSize(boolean adaptiveFetch, Query query, int maxRowSize) {
    if (adaptiveFetch && maxResultBufferSize != -1) {
      String sql = query.getNativeSql().trim();
      AdaptiveFetchQueryInfo adaptiveFetchQueryInfo = adaptiveFetchInfoMap.get(sql);
      if (adaptiveFetchQueryInfo != null) {
        int adaptiveMaxRowSize = adaptiveFetchQueryInfo.getMaxRowSize();
        if (adaptiveMaxRowSize < maxRowSize && maxRowSize > 0) {
          int newFetchSize = (int) (maxResultBufferSize / maxRowSize);
          newFetchSize = adjustFetchSize(newFetchSize);

          adaptiveFetchQueryInfo.setMaxRowSize(maxRowSize);
          adaptiveFetchQueryInfo.setSize(newFetchSize);

          adaptiveFetchInfoMap.put(sql, adaptiveFetchQueryInfo);
        }
      }
    }
  }

  /**
   * Method to get adaptive fetch size for given query.
   *
   * @param adaptiveFetch state of adaptive fetch, which should be used during getting fetch size
   *                      for query
   * @param query         query to which we want get adaptive fetch size
   * @return adaptive fetch size for query or -1 if size doesn't exist/adaptive fetch state is false
   */
  public int getFetchSizeForQuery(boolean adaptiveFetch, Query query) {
    if (adaptiveFetch && maxResultBufferSize != -1) {
      String sql = query.getNativeSql().trim();
      AdaptiveFetchQueryInfo adaptiveFetchQueryInfo = adaptiveFetchInfoMap.get(sql);
      if (adaptiveFetchQueryInfo != null) {
        return adaptiveFetchQueryInfo.getSize();
      }
    }
    return -1;
  }

  /**
   * Method to remove query information from monitoring.
   *
   * @param adaptiveFetch state of adaptive fetch, which should be used during removing fetch size
   *                      for query
   * @param query         query to be removed from monitoring
   */
  public void removeQuery(boolean adaptiveFetch, Query query) {
    if (adaptiveFetch && maxResultBufferSize != -1) {
      String sql = query.getNativeSql().trim();
      AdaptiveFetchQueryInfo adaptiveFetchQueryInfo = adaptiveFetchInfoMap.get(sql);
      if (adaptiveFetchQueryInfo != null) {
        adaptiveFetchQueryInfo.setCounter(adaptiveFetchQueryInfo.getCounter() - 1);

        if (adaptiveFetchQueryInfo.getCounter() < 1) {
          adaptiveFetchInfoMap.remove(sql);
        } else {
          adaptiveFetchInfoMap.put(sql, adaptiveFetchQueryInfo);
        }
      }
    }
  }

  /**
   * Method to set maximum and minimum constraints on given value.
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
   * Method to set minimum constraint on given value.
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
   * Method to set maximum constraint on given value.
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
   * Method to get state of adaptive fetch.
   *
   * @return state of adaptive fetch
   */
  public boolean getAdaptiveFetch() {
    return adaptiveFetch;
  }

  /**
   * Method to set state of adaptive fetch.
   *
   * @param adaptiveFetch desired state of adaptive fetch
   */
  public void setAdaptiveFetch(boolean adaptiveFetch) {
    this.adaptiveFetch = adaptiveFetch;
  }
}
