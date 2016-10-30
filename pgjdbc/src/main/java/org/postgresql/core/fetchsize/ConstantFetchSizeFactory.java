/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.fetchsize;


public class ConstantFetchSizeFactory implements FetchSizeProviderFactory {
  @Override
  public FetchSizeProvider getProvider(int defaultFetchSize) {
    return new ConstantFetchSize(defaultFetchSize);
  }
}
