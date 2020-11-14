/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

/**
 * Class representing a row in a {@link java.sql.ResultSet}.
 */
public class Tuple {
  private final boolean forUpdate;
  final byte[] @Nullable [] data;

  /**
   * Construct an empty tuple. Used in updatable result sets.
   * @param length the number of fields in the tuple.
   */
  public Tuple(int length) {
    this(new byte[length][], true);
  }

  /**
   * Construct a populated tuple. Used when returning results.
   * @param data the tuple data
   */
  public Tuple(byte[] @Nullable [] data) {
    this(data, false);
  }

  private Tuple(byte[] @Nullable [] data, boolean forUpdate) {
    this.data = data;
    this.forUpdate = forUpdate;
  }

  /**
   * Number of fields in the tuple
   * @return number of fields
   */
  public @NonNegative int fieldCount() {
    return data.length;
  }

  /**
   * Total length in bytes of the tuple data.
   * @return the number of bytes in this tuple
   */
  public @NonNegative int length() {
    int length = 0;
    for (byte[] field : data) {
      if (field != null) {
        length += field.length;
      }
    }
    return length;
  }

  /**
   * Get the data for the given field
   * @param index 0-based field position in the tuple
   * @return byte array of the data
   */
  @Pure
  public byte @Nullable [] get(@NonNegative int index) {
    return data[index];
  }

  /**
   * Create a copy of the tuple for updating.
   * @return a copy of the tuple that allows updates
   */
  public Tuple updateableCopy() {
    return copy(true);
  }

  /**
   * Create a read-only copy of the tuple
   * @return a copy of the tuple that does not allow updates
   */
  public Tuple readOnlyCopy() {
    return copy(false);
  }

  private Tuple copy(boolean forUpdate) {
    byte[][] dataCopy = new byte[data.length][];
    System.arraycopy(data, 0, dataCopy, 0, data.length);
    return new Tuple(dataCopy, forUpdate);
  }

  /**
   * Set the given field to the given data.
   * @param index 0-based field position
   * @param fieldData the data to set
   */
  public void set(@NonNegative int index, byte @Nullable [] fieldData) {
    if (!forUpdate) {
      throw new IllegalArgumentException("Attempted to write to readonly tuple");
    }
    data[index] = fieldData;
  }
}
