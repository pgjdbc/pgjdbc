/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.tuple;

import org.postgresql.core.Tuple;

/**
 * Provides a buffer for rows coming from the database.
 * The buffer might be a lazily populated list.
 * TODO: clarify how {@link java.sql.ResultSet#TYPE_FORWARD_ONLY} should be implemented (old rows can be discarded early)
 * TODO: clarify how TupleBuffer should interact with cursors (it is not clear if TupleBuffer or PgResultSet should trigger FETCH ... command to the DB)
 * TODO: should indices be int or long?
 * TODO: remove {@link #size()}
 */
public interface TupleBuffer {
  /**
   * Returns {@code true} if there is at least one row in the buffer.
   * Note: it would return {@code true} even in case the row was discarded (e.g. current position
   * advanced)
   * @return if there is at least one row in the buffer
   */
  boolean isEmpty();

  /**
   * Ensures the tuple with given index (0-based) is buffered. The implementation would trigger
   * lazy load if needed
   * @param index index of the tuple to buffer (0-based)
   * @return true if the row is buffered, or false in case end of stream reached
   */
  boolean advanceTo(int index);

  /**
   * Adds a tuple to the tail of the buffer before any lazily fetched rows.
   * @param tuple tuple to add
   */
  void add(Tuple tuple);

  /**
   * Adds a tuple to the specified position in the buffer.
   * @param index index for the added tuple (index=0 would add the tuple to the head of the buffer)
   * @param tuple tuple to add
   */
  void add(int index, Tuple tuple);

  /**
   * Adds all the tuples to the current buffer.
   * @param other tuple buffer to append
   * @deprecated it is not clear how concatenation of two lazy lists should work
   */
  @Deprecated
  void addAll(TupleBuffer other);

  /**
   * Retrieves tuple by its index (0-based).
   * @param index index of the tuple to return (0-based)
   * @return tuple
   */
  Tuple get(int index);

  /**
   * Replaces the tuple by its index (0-based).
   * @param index index of the tuple to replace (0-based)
   * @param tuple new tuple
   * @return old tuple at given index
   */
  Tuple set(int index, Tuple tuple);

  /**
   * Removes tuple at the given index (0-based).
   * @param index index of the tuple to remove (0-based)
   * @return old tuple at given index
   */
  Tuple remove(int index);

  /**
   * Buffer all the results in memory in case the implementation uses lazy population.
   */
  void bufferResults();

  /**
   * Discards all the rows in the current buffer.
   */
  void close();

  /**
   * Size estimation of the lazily populated lists is non-trivial, so the method must not be used.
   * @return no idea
   * @deprecated do not use
   */
  @Deprecated
  int size();
}
