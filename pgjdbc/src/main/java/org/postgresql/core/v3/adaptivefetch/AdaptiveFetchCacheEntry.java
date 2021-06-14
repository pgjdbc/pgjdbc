/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3.adaptivefetch;

public class AdaptiveFetchCacheEntry {

  private int size = -1; // Holds information about adaptive fetch size for query
  private int counter = 0; // Number of queries in execution using that query info
  private int maximumRowSizeBytes = -1; // Maximum row size in bytes saved for query so far

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

  public int getMaximumRowSizeBytes() {
    return maximumRowSizeBytes;
  }

  public void setMaximumRowSizeBytes(int maximumRowSizeBytes) {
    this.maximumRowSizeBytes = maximumRowSizeBytes;
  }

  public void incrementCounter() {
    counter++;
  }

  public void decrementCounter() {
    counter--;
  }
}
