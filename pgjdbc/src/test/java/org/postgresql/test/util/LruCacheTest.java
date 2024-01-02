/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.util.CanEstimateSize;
import org.postgresql.util.LruCache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Tests {@link org.postgresql.util.LruCache}.
 */
class LruCacheTest {

  private static class Entry implements CanEstimateSize {
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
      return "Entry{" + "id=" + id + '}';
    }
  }

  private final Integer[] expectCreate = new Integer[1];
  private final Deque<Entry> expectEvict = new ArrayDeque<>();
  private final Entry dummy = new Entry(-999);
  private LruCache<Integer, Entry> cache;

  @BeforeEach
  void setUp() throws Exception {
    cache = new LruCache<>(4, 1000, false, new LruCache.CreateAction<Integer, Entry>() {
      @Override
      public Entry create(Integer key) throws SQLException {
        assertEquals(expectCreate[0], key, "Unexpected create");
        return new Entry(key);
      }
    }, new LruCache.EvictAction<Entry>() {
      @Override
      public void evict(Entry entry) throws SQLException {
        if (expectEvict.isEmpty()) {
          fail("Unexpected entry was evicted: " + entry);
        }
        Entry expected = expectEvict.removeFirst();
        assertEquals(expected, entry, "Unexpected evict");
      }
    });
  }

  @Test
  void evictsByNumberOfEntries() throws SQLException {
    Entry a;
    Entry b;
    Entry c;
    Entry d;
    Entry e;

    a = use(1);
    b = use(2);
    c = use(3);
    d = use(4);
    e = use(5, a);
  }

  @Test
  void evictsBySize() throws SQLException {
    Entry a;
    Entry b;
    Entry c;

    a = use(330);
    b = use(331);
    c = use(332);
    use(400, a, b);
  }

  @Test
  void evictsLeastRecentlyUsed() throws SQLException {
    Entry a;
    Entry b;
    Entry c;
    Entry d;

    a = use(1);
    b = use(2);
    c = use(3);
    a = use(1); // reuse a
    use(5);
    d = use(4, b); // expect b to be evicted
  }

  @Test
  void cyclicReplacement() throws SQLException {
    Entry a;
    Entry b;
    Entry c;
    Entry d;
    Entry e;

    a = use(1);
    b = use(2);
    c = use(3);
    d = use(4);
    e = use(5, a);

    for (int i = 0; i < 1000; i++) {
      a = use(1, b);
      b = use(2, c);
      c = use(3, d);
      d = use(4, e);
      e = use(5, a);
    }
  }

  @Test
  void duplicateKey() throws SQLException {
    Entry a;

    a = use(1);
    expectEvict.clear();
    expectEvict.add(a);
    // This overwrites the cache, evicting previous entry with exactly the same key
    cache.put(1, new Entry(1));
    assertEvict();
  }

  @Test
  void caching() throws SQLException {
    Entry a;
    Entry b;
    Entry c;
    Entry d;
    Entry e;

    a = use(1);
    b = use(2);
    c = use(3);
    d = use(4);

    for (int i = 0; i < 10000; i++) {
      c = use(-3);
      b = use(-2);
      a = use(-1);
      e = use(5, d);
      c = use(-3);
      b = use(-2);
      a = use(-1);
      d = use(4, e);
    }
  }

  private Entry use(int expectCreate, Entry... expectEvict) throws SQLException {
    this.expectCreate[0] = expectCreate <= 0 ? -1 : expectCreate;
    this.expectEvict.clear();
    this.expectEvict.addAll(Arrays.asList(expectEvict));
    Entry a = cache.borrow(Math.abs(expectCreate));
    cache.put(a.id, a); // a
    assertEvict();
    return a;
  }

  private void assertEvict() {
    if (expectEvict.isEmpty()) {
      return;
    }
    fail("Some of the expected evictions not happened: " + expectEvict.toString());
  }
}
