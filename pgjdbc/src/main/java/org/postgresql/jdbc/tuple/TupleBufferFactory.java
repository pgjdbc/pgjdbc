/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.tuple;

import org.postgresql.core.Tuple;

/**
 * Instantiates {@link TupleBuffer}.
 */
public class TupleBufferFactory {
  /**
   * Creates empty and immutable buffer.
   * @return empty and immutable buffer
   */
  public static TupleBuffer empty() {
    return EmptyTupleBuffer.INSTANCE;
  }

  /**
   * Creates a new buffer which can be populated with {@link TupleBuffer#add(Tuple)}.
   * @return a new buffer which can be mutated
   */
  public static DefaultTupleBuffer create() {
    return new DefaultTupleBuffer(null);
  }

  /**
   * Creates a new buffer which can be populated with {@link TupleBuffer#add(Tuple)}.
   * @param tupleProvider provider for the new rows (if provider returns null it signals the end of the stream)
   * @return a new buffer which can be mutated
   */
  public static DefaultTupleBuffer create(TupleProvider tupleProvider) {
    return new DefaultTupleBuffer(tupleProvider);
  }
}
