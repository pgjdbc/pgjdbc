/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.fetchsize;


import java.util.List;

/**
 * Default implementation for fetch size provide that always return constant fetch size.
 */
public class ConstantFetchSize implements FetchSizeProvider {
  private final int fetchSize;

  public ConstantFetchSize(int fetchSize) {
    this.fetchSize = fetchSize;
  }

  @Override
  public int getNextFetchSize(List<byte[][]> previousFetch) {
    return fetchSize;
  }

  @Override
  public int getFetchSize() {
    return fetchSize;
  }
}
