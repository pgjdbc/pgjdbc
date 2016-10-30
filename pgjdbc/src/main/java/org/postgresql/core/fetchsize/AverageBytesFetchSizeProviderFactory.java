/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.fetchsize;


public class AverageBytesFetchSizeProviderFactory implements FetchSizeProviderFactory {
  private final long waitFetchSizeInBytes;
  private final double smoothingFactor;

  /**
   * @param waitFetchSizeInBytes positive number bytes that can be extract from database by one
   *                             round trip, really count bytes extract from database by one round
   *                             trip flexible and depends on average size rows already extract from
   *                             database
   * @param smoothingFactor      0 &gt; smoothingFactor &lt; 1
   */
  public AverageBytesFetchSizeProviderFactory(
      long waitFetchSizeInBytes,
      double smoothingFactor
  ) {
    this.waitFetchSizeInBytes = waitFetchSizeInBytes;
    this.smoothingFactor = smoothingFactor;
  }

  @Override
  public FetchSizeProvider getProvider(int defaultFetchSize) {
    return new AverageBytesFetchSizeProvider(waitFetchSizeInBytes, defaultFetchSize, smoothingFactor);
  }
}
