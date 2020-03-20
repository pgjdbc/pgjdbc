/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.util.AbstractSequentialList;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * The streaming list by default fetches items on-demand from the given supplier.
 * This means that it can be only iterated through once, and only implements enough of the
 * List api for the PgResultSet to be happy.
 *
 * The list can also switch to basic ArrayList buffered mode, where it preloads items from
 * the supplier to memory. This is required both when applications keep multiple ResultSets
 * open at the same time (thre previous ones must be buffered). Or when the jdbc driver
 * itself does extra queries in the background to fetch for example the metadata.
 *
 * @param <E> Tuple type
 */
public class StreamingList<E> extends AbstractSequentialList<E> {
  private final DynamicListIterator<E> listIterator;
  private boolean firstTime;
  private ArrayList<E> buffer;

  public StreamingList(Supplier<E> supplier) {
    listIterator = new DynamicListIterator<E>(supplier);
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
      if (listIterator.getResultNumber() == index - 1 && listIterator.previous() != null) {
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
      buffer.isEmpty();
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
    private final Supplier<E> supplier;
    private E prev;
    private E next;
    private boolean end;
    private int resultNumber = -1;

    DynamicListIterator(Supplier<E> supplier) {
      this.supplier = supplier;
    }

    int getResultNumber() {
      return resultNumber;
    }

    @Override
    public boolean hasNext() {
      if (next != null) {
        return true;
      }
      if (end) {
        return false;
      }
      prev = next;
      next = supplier.get();
      if (next == null) {
        end = true;
      }
      return next != null;
    }

    @Override
    public E next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      resultNumber++;
      E ret = next;
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

    public ArrayList<E> bufferResults() {
      // the array list will be mostly nulls, but that is ok, since we only use streaming list for forward_only queries
      ArrayList<E> list = new ArrayList<>();
      for (int i=0; i<resultNumber-1; i++) {
        list.add(null);
      }
      if (prev != null) {
        list.add(prev);
      }
      for (int i=list.size(); i<resultNumber; i++) {
        list.add(null);
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
      throw new UnsupportedOperationException();
    }

    @Override
    public E previous() {
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
