/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import static org.postgresql.util.internal.Nullness.castNonNull;

import java.io.IOException;

/**
 * A marker exception class to distinguish between "IOException when reading the data" and
 * "IOException when writing the data" when transferring data from one stream to another.
 */
public class SourceStreamIOException extends IOException {
  /**
   * The number of bytes remaining to transfer to the destination stream.
   */
  private final int bytesRemaining;

  public SourceStreamIOException(int bytesRemaining, IOException cause) {
    super(cause);
    this.bytesRemaining = bytesRemaining;
  }

  public int getBytesRemaining() {
    return bytesRemaining;
  }

  @Override
  public synchronized IOException getCause() {
    return (IOException) castNonNull(super.getCause());
  }
}
