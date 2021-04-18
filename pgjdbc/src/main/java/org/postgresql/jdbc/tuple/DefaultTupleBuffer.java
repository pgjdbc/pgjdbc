/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.tuple;

import org.postgresql.core.Tuple;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

public class DefaultTupleBuffer implements TupleBuffer {
  private @Nullable TupleProvider tupleProvider;
  private final List<Tuple> buffer = new ArrayList<Tuple>();

  DefaultTupleBuffer(TupleProvider tupleProvider) {
    this.tupleProvider = tupleProvider;
  }

  @Override
  public boolean isEmpty() {
    // Fetch next row
    List<Tuple> buffer = this.buffer;
    if (!buffer.isEmpty()) {
      return true;
    }
    advanceTo(1);
    return !buffer.isEmpty();
  }

  @Override
  public boolean advanceTo(int index) {
    List<Tuple> buffer = this.buffer;
    TupleProvider tupleProvider = this.tupleProvider;
    while (buffer.size() <= index) {
      if (tupleProvider == null) {
        return false;
      }
      Tuple tuple = tupleProvider.get();
      if (tuple == null) {
        // Tuple not found, so we can't advance. A new fetch request needed
        return false;
      }
      buffer.add(tuple);
    }
    return true;
  }

  @Override
  public void add(Tuple tuple) {
    buffer.add(tuple);
  }

  @Override
  public void add(int index, Tuple tuple) {
    // TODO: verify indices
    buffer.add(index, tuple);
  }

  @Override
  public void addAll(TupleBuffer other) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Tuple get(int index) {
    return buffer.get(index);
  }

  @Override
  public Tuple set(int index, Tuple tuple) {
    return buffer.set(index, tuple);
  }

  @Override
  public Tuple remove(int index) {
    return buffer.remove(index);
  }

  @Override
  public void bufferResults() {
    advanceTo(Integer.MAX_VALUE);
  }

  @Override
  public int size() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() {
    TupleProvider tupleProvider = this.tupleProvider;
    if (tupleProvider == null) {
      return;
    }
    while (tupleProvider.get() != null) {
      /* ignored */
    }
  }
}
