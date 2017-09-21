/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

public class Tuple {
  private final boolean forUpdate;
  final byte[][] data;

  public Tuple(int length) {
    this(new byte[length][], true);
  }

  public Tuple(byte[][] data) {
    this(data, false);
  }

  private Tuple(byte[][] data, boolean forUpdate) {
    this.data = data;
    this.forUpdate = forUpdate;
  }

  public int columnCount() {
    return data.length;
  }

  public int length() {
    int length = 0;
    for (byte[] aTuple : data) {
      if (aTuple != null) {
        length += aTuple.length;
      }
    }
    return length;
  }

  public byte[] get(int index) {
    return data[index];
  }

  public Tuple updateableCopy() {
    return copy(true);
  }

  public Tuple readOnlyCopy() {
    return copy(false);
  }

  private Tuple copy(boolean forUpdate) {
    byte[][] dataCopy = new byte[data.length][];
    System.arraycopy(data, 0, dataCopy, 0, data.length);
    return new Tuple(dataCopy, forUpdate);
  }

  public void set(int column, byte[] fieldData) {
    if (!forUpdate) {
      throw new IllegalArgumentException("Attempted to write to readonly tuple");
    }
    data[column] = fieldData;
  }
}
