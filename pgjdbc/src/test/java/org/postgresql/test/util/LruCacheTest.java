/*-------------------------------------------------------------------------
*
* Copyright (c) 2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.util;

import org.postgresql.util.CanEstimateSize;
import org.postgresql.util.LruCache;

import junit.framework.TestCase;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Tests {@link org.postgresql.util.LruCache}
 */
public class LruCacheTest extends TestCase {
  static class Entry implements CanEstimateSize {
    private final int id;

    Entry(int id) {
      this.id = id;
    }


    @Override
    public long getSize() {
      return id;
    }

    @Override
    public String toString() {
      return "Entry{"
          + "id=" + id
          + '}';
    }
  }

  private final Integer[] expectCreate = new Integer[1];
  private final Deque<Entry> expectEvict = new ArrayDeque<Entry>();
  private final Entry dummy = new Entry(-999);
  private LruCache<Integer, Entry> cache;

  @Override
  protected void setUp() throws Exception {
    cache = new LruCache<Integer, Entry>(3, 1000, new LruCache.CreateAction<Integer, Entry>() {
      @Override
      public Entry create(Integer key) throws SQLException {
        assertEquals("Unexpected create", expectCreate[0], key);
        return new Entry(key);
      }
    }, new LruCache.EvictAction<Entry>() {
      @Override
      public void evict(Entry entry) throws SQLException {
        Entry expected = expectEvict.removeFirst();
        assertEquals("Unexpected evict", expected, entry);
      }
    });
  }

  public void testEvictsByNumberOfEntries() throws SQLException {
    Entry a, b, c, d;

    a = use(1, dummy);
    b = use(2, dummy);
    c = use(3, dummy);
    d = use(4, a);
  }

  public void testEvictsBySize() throws SQLException {
    Entry a, b, c, d;

    a = use(3, dummy);
    b = use(5, dummy);
    c = use((int) (1000 - a.getSize() - b.getSize()), dummy);
    // Now cache holds exactly 1000 bytes.
    // a and b should be evicted
    d = use(4, a, b);
  }

  public void testEvictsLeastRecentlyUsed() throws SQLException {
    Entry a, b, c, d;

    a = use(1, dummy);
    b = use(2, dummy);
    c = use(3, dummy);
    a = use(1, dummy); // reuse a
    d = use(4, b); // expect b to be evicted
  }

  public void testCyclicReplacement() throws SQLException {
    Entry a, b, c, d;

    a = use(1, dummy);
    b = use(2, dummy);
    c = use(3, dummy);
    d = use(4, a);

    for (int i = 0; i < 100000; i++) {
      a = use(1, b);
      b = use(2, c);
      c = use(3, d);
      d = use(4, a);
    }
  }

  public void testCaching() throws SQLException {
    Entry a, b, c, d;

    a = use(1, dummy);
    b = use(2, dummy);
    c = use(3, dummy);

    for (int i = 0; i < 100000; i++) {
      b = use(-2, dummy);
      a = use(-1, dummy);
      d = use(4, c);
      b = use(-2, dummy);
      a = use(-1, dummy);
      c = use(3, d);
    }
  }

  private Entry use(int expectCreate, Entry... expectEvict) throws SQLException {
    Entry a;
    this.expectCreate[0] = expectCreate <= 0 ? -1 : expectCreate;
    this.expectEvict.clear();
    this.expectEvict.addAll(Arrays.asList(expectEvict));
    a = cache.borrow(Math.abs(expectCreate));
    cache.put(a.id, a); // a
    return a;
  }
}
