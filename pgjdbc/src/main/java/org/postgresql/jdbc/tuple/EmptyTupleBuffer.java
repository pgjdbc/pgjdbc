/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.tuple;

import org.postgresql.core.Tuple;

/**
 * This class implements an empty and immutable buffer.
 */
public class EmptyTupleBuffer implements TupleBuffer {
  static final TupleBuffer INSTANCE = new EmptyTupleBuffer();

  @Override
  public boolean isEmpty() {
    return true;
  }

  @Override
  public boolean advanceTo(int index) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void add(Tuple tuple) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void add(int index, Tuple tuple) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addAll(TupleBuffer other) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Tuple get(int index) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Tuple set(int index, Tuple tuple) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Tuple remove(int index) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void bufferResults() {
  }

  @Override
  public void close() {
  }

  @Override
  public int size() {
    return 0;
  }
}
