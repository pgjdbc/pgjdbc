/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3.adaptivefetch;

public class AdaptiveFetchQueryInfo {

  //holds information about adaptive fetch size for query
  private int size = -1;
  //number of queries in execution using that query info
  private int counter = 0;
  //max row size saved for query so far
  private int maxRowSize = -1;

  public int getSize() {
    return size;
  }

  public void setSize(int size) {
    this.size = size;
  }

  public int getCounter() {
    return counter;
  }

  public void setCounter(int counter) {
    this.counter = counter;
  }

  public int getMaxRowSize() {
    return maxRowSize;
  }

  public void setMaxRowSize(int maxRowSize) {
    this.maxRowSize = maxRowSize;
  }
}
