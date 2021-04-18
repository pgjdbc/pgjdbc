/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.core.Provider;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.AbstractSequentialList;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.NoSuchElementException;

/**
 * The streaming list by default fetches items on-demand from the given supplier.
 * This means that it can be only iterated through once, and only implements enough of the
 * List api for the PgResultSet to be happy.
 *
 * <p>
 * The list can also switch to basic ArrayList buffered mode, where it preloads items from
 * the supplier to memory. This is required both when applications keep multiple ResultSets
 * open at the same time which requires all but last one to be buffered. Or when the jdbc driver
 * itself does extra queries in the background to fetch for example the metadata.
 *</p>
 * @param <E> Tuple type
 */
public class StreamingList<E> extends AbstractSequentialList<E> {
  private final DynamicListIterator<E> listIterator;
  private boolean firstTime;
  private @MonotonicNonNull ArrayList<E> buffer;

  public StreamingList(Provider<@Nullable E> supplier) {
    listIterator = new DynamicListIterator<>(supplier);
  }

  @Override
  public ListIterator<E> listIterator(int index) {
    if (buffer != null) {
      return buffer.listIterator(index);
    }
    if (index != 0) {
      throw new UnsupportedOperationException("Can only iterate from beginning");
    }
    if (!firstTime) {
      throw new IllegalStateException("Can only fetch iterator once");
    }
    firstTime = false;
    return listIterator;
  }

  /**
   * Returns the number of elements seen so far or the actual number of elements if buffered. After every stream iteration the size will be larger.
   * @return the number of elements seen so far or the actual number of elements if buffered.
   */
  @Override
  public int size() {
    if (buffer != null) {
      return buffer.size();
    }
    // this is the size seen so far
    int bonus = listIterator.hasNext() ? 1 : 0;
    return listIterator.getResultNumber() + bonus;
  }

  /**
   * Allows one item to be added to an empty list.
   * @param element The element to add.
   */
  @Override
  public boolean add(E element) {
    listIterator.add(element);
    return true;
  }

  @Override
  public E get(int index) {
    if (buffer != null) {
      return buffer.get(index);
    }
    if (listIterator.getResultNumber() != index) {
      if (listIterator.getResultNumber() == index - 1 && listIterator.hasPrevious()) {
        return listIterator.previous();
      }
      throw new UnsupportedOperationException("Can only fetch current item. " + listIterator.getResultNumber() + "!=" + index);
    }
    return listIterator.next();
  }

  /**
   * Fetches and ignores all elements from the supplier. Allows the supplier to reach final state.
   */
  public void close() {
    while (listIterator.hasNext()) {
      listIterator.next();
    }
  }

  /**
   * True if there are no elements have been fetched so far.
   * @return If no elements
   */
  @Override
  public boolean isEmpty() {
    if (buffer != null) {
      return buffer.isEmpty();
    }
    return listIterator.getResultNumber() == -1;
  }

  /**
   * Switches to buffered mode by polling the provider for results and caching them in memory.
   */
  public void bufferResults() {
    this.buffer = listIterator.bufferResults();
  }

  static class DynamicListIterator<E> implements ListIterator<E> {
    private final Provider<@Nullable E> supplier;
    private @Nullable E prev;
    private @Nullable E next;
    private boolean end;
    private int resultNumber = -1;

    DynamicListIterator(Provider<@Nullable E> supplier) {
      this.supplier = supplier;
    }

    int getResultNumber() {
      return resultNumber;
    }

    @Override
    public boolean hasNext() {
      return peekNext() != null;
    }

    private @Nullable E peekNext() {
      if (next != null) {
        return next;
      }
      if (end) {
        return null;
      }
      prev = next;
      next = supplier.get();
      if (next == null) {
        end = true;
      }
      return next;
    }

    @Override
    public E next() {
      E ret = peekNext();
      if (ret == null) {
        throw new NoSuchElementException();
      }
      resultNumber++;
      next = null;
      return ret;
    }

    @Override
    public void add(E e) {
      if (resultNumber != -1) {
        throw new IllegalStateException("Can only insert items before iterator starts: Current position " + resultNumber);
      }
      next = e;
      resultNumber++;
    }

    @SuppressWarnings("argument.type.incompatible")
    public ArrayList<E> bufferResults() {
      // the array list will contain nulls for rows that have already been processed, but that is ok, since we only use streaming list for forward_only queries
      ArrayList<E> list = new ArrayList<>();

      for (int i = 0; i < resultNumber - 1; i++) {
        list.add(null);
      }
      if (list.size() < resultNumber) {
        list.add(prev);
      }
      while (hasNext()) {
        list.add(next());
      }
      return list;
    }

    @Override
    public int nextIndex() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasPrevious() {
      return prev != null;
    }

    @Override
    public E previous() {
      if (prev == null) {
        throw new NoSuchElementException();
      }
      return prev;
    }

    @Override
    public int previousIndex() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void set(E e) {
      throw new UnsupportedOperationException();
    }
  }
}
