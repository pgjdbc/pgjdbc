/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.fetchsize;

public interface FetchSizeProviderFactory {
  /**
   * @param defaultFetchSize default fetch size that should be use as initial fetch size if provider
   *                         can't estimate fetch size without samples
   * @return create instance of fetch size provider
   */
  FetchSizeProvider getProvider(int defaultFetchSize);
}
