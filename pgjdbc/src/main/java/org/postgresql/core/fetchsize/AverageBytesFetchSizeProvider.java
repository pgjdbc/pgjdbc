/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.fetchsize;

import java.util.List;

/**
 * Adaptive fetch size to wait fetch size in bytes base on average size last extracted records
 */
public class AverageBytesFetchSizeProvider implements FetchSizeProvider {
  private final long waitFetchSizeInBytes;
  private final double smoothingFactor;

  private double avgRowSize;
  private int lastCalculateFetchSize;

  /**
   * @param waitFetchSizeInBytes positive number bytes that can be extract from database by one
   *                             round trip, really count bytes extract from database by one round
   *                             trip flexible and depends on average size rows already extract from
   *                             database
   * @param defaultFetchSize     initial fetch size that should be use for get enough samples for
   *                             calculate fetch size for next round trip. If the value is large
   *                             enough it can be reason for OOM on table with huge rows, but also
   *                             if the value is small enough it can be reason for not correct rows
   *                             size estimate.
   * @param smoothingFactor      0 &gt; smoothingFactor &lt; 1
   */
  public AverageBytesFetchSizeProvider(
      long waitFetchSizeInBytes,
      int defaultFetchSize,
      double smoothingFactor
  ) {
    if (waitFetchSizeInBytes <= 0) {
      throw new IllegalArgumentException("FetchSizeInBytes should be great than 0");
    }
    this.waitFetchSizeInBytes = waitFetchSizeInBytes;

    if (smoothingFactor < 0 || smoothingFactor > 1) {
      throw new IllegalArgumentException("SmoothingFactor should be between 0 and 1");
    }
    this.smoothingFactor = smoothingFactor;
    if (defaultFetchSize == 0) {
      defaultFetchSize = 10;
    }
    this.lastCalculateFetchSize = defaultFetchSize;
  }

  @Override
  public int getNextFetchSize(List<byte[][]> previousFetch) {
    long bytes_fetched = 0;
    for (byte[][] record : previousFetch) {
      for (byte[] value : record) {
        bytes_fetched += value.length;
      }
    }

    double lastAvgRowSize = bytes_fetched / previousFetch.size();

    if (avgRowSize == 0) {
      avgRowSize = lastAvgRowSize;
    }

    /**
     * calculate average row size base on previous experience by use exponential smoothing
     * https://en.wikipedia.org/wiki/Exponential_smoothing
     */
    avgRowSize = smoothingFactor * avgRowSize + (1 - smoothingFactor) * lastAvgRowSize;

    long calclFetchSize = Math.round(waitFetchSizeInBytes / avgRowSize);
    if (calclFetchSize > Integer.MAX_VALUE) {
      lastCalculateFetchSize = Integer.MAX_VALUE;
    } else {
      lastCalculateFetchSize = (int) calclFetchSize;
    }

    /**
     * Fetch by one record for statement with huge tuple
     */
    if (lastCalculateFetchSize <= 0) {
      lastCalculateFetchSize = 1;
    }

    return lastCalculateFetchSize;
  }

  @Override
  public int getFetchSize() {
    return lastCalculateFetchSize;
  }
}
