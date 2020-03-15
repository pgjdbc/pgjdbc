/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.util.AbstractSequentialList;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

public class StreamingList<E> extends AbstractSequentialList<E> {
  private final DynamicListIterator<E> listIterator;
  private boolean firstTime;

  public StreamingList(Supplier<E> supplier) {
    listIterator = new DynamicListIterator<E>(supplier);
  }

  @Override
  public ListIterator<E> listIterator(int index) {
    if (index != 0) {
      throw new UnsupportedOperationException("Can only iterate from beginning");
    }
    if (!firstTime) {
      throw new IllegalStateException("Can only fetch iterator once");
    }
    firstTime = false;
    return listIterator;
  }

  @Override
  public int size() {
    // this is the size seen so far
    int bonus = listIterator.hasNext() ? 1 : 0;
    return listIterator.getResultNumber() + bonus;
  }

  @Override
  public boolean add(E element) {
    listIterator.add(element);
    return true;
  }

  @Override
  public E get(int index) {
    if (listIterator.getResultNumber() != index) {
      if (listIterator.getResultNumber() == index - 1 && listIterator.previous() != null) {
        return listIterator.previous();
      }
      throw new UnsupportedOperationException("Can only fetch current item. " + listIterator.getResultNumber() + "!=" + index);
    }
    return listIterator.next();
  }

  public void close() {
    while (listIterator.hasNext()) {
      listIterator.next();
    }
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
      if (next != null) {
        resultNumber++;
      } else {
        end = true;
      }
      return next != null;
    }

    @Override
    public E next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
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
