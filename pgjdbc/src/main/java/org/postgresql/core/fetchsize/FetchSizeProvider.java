/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.fetchsize;

import java.util.List;

/**
 * Provider implementation allow adapt fetch size base on data that receive last times. Provider
 * also can return as fetch size zero and it will means that should be extracted whole by one round
 * trip to postgres.
 */
public interface FetchSizeProvider {
  /**
   * Calculate next fetch size based on data that was received last time
   *
   * @param previousFetch not null list with rows that was receive last time
   * @return the positive number of result set rows that should be extract from backend by next
   * round trip
   */
  int getNextFetchSize(List<byte[][]> previousFetch);

  /**
   * Fetch size that was use previous round trip or default fetch size
   *
   * @return the positive number of result set rows that retrieves from backend by one round trip
   */
  int getFetchSize();
}
