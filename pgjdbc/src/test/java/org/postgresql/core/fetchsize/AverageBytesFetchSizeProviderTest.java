/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.fetchsize;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThat;

import org.hamcrest.CoreMatchers;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;


public class AverageBytesFetchSizeProviderTest {

  @Test
  public void initFetchSizeEqualToDefaultFetchSize() throws Exception {
    final int waitSize = 10;
    AverageBytesFetchSizeProvider provider =
        new AverageBytesFetchSizeProvider(123, waitSize, 0.5);

    assertThat(provider.getFetchSize(), CoreMatchers.equalTo(waitSize));
  }

  @Test
  public void testAdaptFetchSizeBySamples() throws Exception {
    final int initSize = 10;
    final long waitBytes = 200;

    AverageBytesFetchSizeProvider provider =
        new AverageBytesFetchSizeProvider(waitBytes, initSize, 0.5);


    List<byte[][]> rows = new ArrayList<byte[][]>();
    for (int index = 0; index < 10; index++) {
      rows.add(new byte[1][10]);
    }

    provider.getNextFetchSize(rows);

    assertThat(provider.getFetchSize(), not(equalTo(initSize)));
  }

  @Test
  public void testEstimateFetchSizeCanNotLoadMoreThanSpecifiedSizeInBytes() throws Exception {
    final int initSize = 10;
    final long waitBytes = 200;
    final int sizeOfSingleRow = 10;

    AverageBytesFetchSizeProvider provider =
        new AverageBytesFetchSizeProvider(waitBytes, initSize, 0.5);


    List<byte[][]> rows = new ArrayList<byte[][]>();
    for (int index = 0; index < initSize; index++) {
      rows.add(new byte[1][sizeOfSingleRow]);
    }

    int estimatedFetchSize = provider.getNextFetchSize(rows);

    long estemateFetchSizeInBytes = estimatedFetchSize * sizeOfSingleRow;

    assertThat("Estimated fetch size can not exceed specified fetch size in bytes, "
            + "because it can reason of OOM, estimatedFetchSizeInBytes equal to "
            + estemateFetchSizeInBytes
            + " and bytes limit equal to "
            + waitBytes,
        estemateFetchSizeInBytes <= waitBytes, equalTo(true)
    );
  }

  @Test
  public void testMinimalFetchSizeEqualToOneForReallyHugeRecords() throws Exception {
    final int initSize = 20;
    final long limitBytes = 200;
    final int sizeOfSingleRow = (int) limitBytes * 5;

    AverageBytesFetchSizeProvider provider =
        new AverageBytesFetchSizeProvider(limitBytes, initSize, 0.5);


    List<byte[][]> rows = new ArrayList<byte[][]>();
    for (int index = 0; index < initSize; index++) {
      rows.add(new byte[1][sizeOfSingleRow]);
    }

    int estimatedFetchSize = provider.getNextFetchSize(rows);

    assertThat("When single record of table huge than specified fetch size in bytes "
            + "we should start fetch by one record, because if as result estimate "
            + "we return zero it will means that next round trip try load "
            + "all records for statement and fail with OOM",
        estimatedFetchSize, CoreMatchers.equalTo(1)
    );
  }

  @Test
  public void testConsiderPreviousSamplesToNewEstimates() throws Exception {
    final int initSize = 10;
    final long limitBytes = 1000;
    final int sizeOfSmallSingleRow = 10;
    final int sizeOfHugeSingleRow = 50;

    AverageBytesFetchSizeProvider provider =
        new AverageBytesFetchSizeProvider(limitBytes, initSize, 0.5);

    List<byte[][]> smallRows = new ArrayList<byte[][]>();
    for (int index = 0; index < provider.getFetchSize(); index++) {
      smallRows.add(new byte[1][sizeOfSmallSingleRow]);
    }

    int firstEstimateFetchSize = provider.getNextFetchSize(smallRows);

    List<byte[][]> hugeRows = new ArrayList<byte[][]>();
    for (int index = 0; index < provider.getFetchSize(); index++) {
      hugeRows.add(new byte[1][sizeOfHugeSingleRow]);
    }

    int secondEstimateFetchSize = provider.getNextFetchSize(hugeRows);

    assertThat("FetchSizeProvider should consider all previous samples to estimate rows size, "
            + "for example we can receive small rows and then receive huge, in that case after receive "
            + "huge rows fetch size should be reduced. In this test as first estimate was receive "
            + firstEstimateFetchSize
            + " on the second estimate was received "
            + secondEstimateFetchSize,
        secondEstimateFetchSize < firstEstimateFetchSize, equalTo(true)
    );
  }

  @Test
  public void testSmoothFactorAffectFetchSize() throws Exception {
    final int initSize = 10;
    final long limitBytes = 1000;
    final int sizeOfSmallSingleRow = 10;
    final int sizeOfHugeSingleRow = 50;

    List<byte[][]> smallRows = new ArrayList<byte[][]>();
    for (int index = 0; index < initSize; index++) {
      smallRows.add(new byte[1][sizeOfSmallSingleRow]);
    }

    List<byte[][]> hugeRows = new ArrayList<byte[][]>();
    for (int index = 0; index < initSize * 2; index++) {
      hugeRows.add(new byte[1][sizeOfHugeSingleRow]);
    }

    AverageBytesFetchSizeProvider firstProvider =
        new AverageBytesFetchSizeProvider(limitBytes, initSize, 0.9);
    AverageBytesFetchSizeProvider secondProvider =
        new AverageBytesFetchSizeProvider(limitBytes, initSize, 0.1);

    firstProvider.getNextFetchSize(smallRows);
    int estimateFirstProvider = firstProvider.getNextFetchSize(hugeRows);

    secondProvider.getNextFetchSize(smallRows);
    int estimateSecondProvider = secondProvider.getNextFetchSize(hugeRows);

    assertThat("Smaller smoothing factor more more sensitive to spikes",
        estimateSecondProvider < estimateFirstProvider,
        equalTo(true)
    );
  }

  @Test
  public void testFetchSizeCanNotBeGreatThemIntegerMax() throws Exception {
    final int initSize = 10;
    final long limitBytes = Long.MAX_VALUE;
    final int sizeOfSmallSingleRow = 1;

    AverageBytesFetchSizeProvider provider =
        new AverageBytesFetchSizeProvider(limitBytes, initSize, 0.5);

    List<byte[][]> rows = new ArrayList<byte[][]>();
    for (int index = 0; index < initSize; index++) {
      rows.add(new byte[1][sizeOfSmallSingleRow]);
    }

    int estimatedFetchSize = provider.getNextFetchSize(rows);
    assertThat(estimatedFetchSize, equalTo(Integer.MAX_VALUE));
  }
}
