/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.StampedLock;

/**
 * Caches values in simple least-recently-accessed order.
 */
public class LruCache<Key extends @NonNull Object, Value extends @NonNull CanEstimateSize>
    implements Gettable<Key, Value> {
  /**
   * Action that is invoked when the entry is removed from the cache.
   *
   * @param <Value> type of the cache entry
   */
  public interface EvictAction<Value> {
    void evict(Value value) throws SQLException;
  }

  /**
   * When the entry is not present in cache, this create action is used to create one.
   *
   * @param <Value> type of the cache entry
   */
  public interface CreateAction<Key, Value> {
    Value create(Key key) throws SQLException;
  }

  private final StampedLock lock = new StampedLock();
  private final EvictAction<Value> onEvict;
  private final CreateAction<Key, Value> createAction;
  private final Map<Key, Value> cache;
  final int maxSizeEntries;
  final long maxSizeBytes;
  long currentSize;

  private class LimitedMap extends LinkedHashMap<Key, Value> {
    private static final long serialVersionUID = 1L;

    LimitedMap(int initialCapacity, float loadFactor, boolean accessOrder) {
      super(initialCapacity, loadFactor, accessOrder);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Key, Value> eldest) {
      // Avoid creating iterators if size constraints not violated
      if (size() <= maxSizeEntries && currentSize <= maxSizeBytes) {
        return false;
      }

      Iterator<Value> it = values().iterator();
      while (it.hasNext()) {
        if (size() <= maxSizeEntries && currentSize <= maxSizeBytes) {
          return false;
        }

        Value value = it.next();
        evictValue(value);
        long valueSize = value.getSize();
        if (valueSize > 0) {
          // just in case
          currentSize -= valueSize;
        }
        it.remove();
      }
      return false;
    }
  }

  final void evictValue(Value value) {
    try {
      onEvict.evict(value);
    } catch (SQLException e) {
      /* ignore */
    }
  }

  /**
   * Creates an instance with defined max entries and max memory used.
   *
   * @param maxSizeEntries The max number of entries to keep.
   * @param maxSizeBytes The max amount of memory consumed by values in cache.
   */
  public LruCache(int maxSizeEntries, long maxSizeBytes) {
    this(maxSizeEntries, maxSizeBytes, null, null);
  }

  /**
   * Creates an instance with defined max entries and max memory used.
   *
   * @param maxSizeEntries The max number of entries to keep.
   * @param maxSizeBytes The max amount of memory consumed by values in cache.
   * @param accessOrder Determines orders entries are purged. A value of {@code true} means access order (usage).
   *      A value of {@code false} means insertion order.
   * @deprecated As it allows controlling access order, which determines if it is actually an LRU cache.
   */
  @Deprecated
  public LruCache(int maxSizeEntries, long maxSizeBytes, boolean accessOrder) {
    this(maxSizeEntries, maxSizeBytes, null, null);
  }

  /**
   * Creates an instance with defined max entries, max memory used, and optional actions to use for creation/eviction.
   *
   * @param maxSizeEntries The max number of entries to keep.
   * @param maxSizeBytes The max amount of memory consumed by values in cache.
   * @param createAction Optional action to use for creating entries on demand.
   * @param onEvict Optional action to call when entries are evicted.
   */
  public LruCache(int maxSizeEntries, long maxSizeBytes,
      @Nullable CreateAction<Key, Value> createAction,
      @Nullable EvictAction<Value> onEvict) {
    this(maxSizeEntries, maxSizeBytes, true, createAction, onEvict);
  }

  /**
   * Creates an instance with defined max entries, max memory used, and optional actions to use for creation/eviction.
   *
   * @param maxSizeEntries The max number of entries to keep.
   * @param maxSizeBytes The max amount of memory consumed by values in cache.
   * @param accessOrder Determines orders entries are purged. A value of {@code true} means access order (usage).
   *      A value of {@code false} means insertion order.
   * @param createAction Optional action to use for creating entries on demand.
   * @param onEvict Optional action to call when entries are evicted.
   * @deprecated As it allows controlling access order, which determines if it is actually an LRU cache.
   */
  @Deprecated
  public LruCache(int maxSizeEntries, long maxSizeBytes, boolean accessOrder,
      @Nullable CreateAction<Key, Value> createAction,
      @Nullable EvictAction<Value> onEvict) {
    this.maxSizeEntries = maxSizeEntries;
    this.maxSizeBytes = maxSizeBytes;
    this.createAction = createAction != null ? createAction : k -> {
      throw new UnsupportedOperationException("createAction == null, so can't create object");
    };
    this.onEvict = onEvict != null ? onEvict : v -> {};
    this.cache = new LimitedMap(16, 0.75f, accessOrder);
  }

  /**
   * Returns an entry from the cache.
   *
   * @param key cache key
   * @return entry from cache or null if cache does not contain given key.
   */
  @Override
  public @Nullable Value get(Key key) {
    //since the map tracks accesses, even a read is a mutating operation
    final long stamp = lock.writeLock();
    try {
      return cache.get(key);
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  /**
   * Borrows an entry from the cache.
   *
   * @param key cache key
   * @return entry from cache or newly created entry if cache does not contain given key.
   * @throws SQLException if entry creation fails
   */
  public Value borrow(Key key) throws SQLException {
    final long stamp = lock.writeLock();
    try {
      Value value = cache.remove(key);
      if (value == null) {
        return createAction.create(key);
      }
      currentSize -= value.getSize();
      return value;
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  /**
   * Returns given value to the cache.
   *
   * @param key key
   * @param value value
   */
  public void put(Key key, Value value) {
    put(key, value, false);
  }

  /**
   * Actual work of put, but allowing calling method to control whether lock is already obtained.
   */
  private void put(Key key, Value value, boolean locked) {
    long valueSize = value.getSize();
    if (maxSizeBytes == 0 || maxSizeEntries == 0 || valueSize * 2 > maxSizeBytes) {
      // Just destroy the value if cache is disabled or if entry would consume more than a half of
      // the cache
      evictValue(value);
      return;
    }
    //this method is called from putAll, so if that call already has the lock, we should not try to obtain here
    final long stamp = locked ? 0 : lock.writeLock();
    @Nullable Value prev;
    try {
      currentSize += valueSize;
      prev = cache.put(key, value);
      if (prev == null) {
        return;
      }
      // This should be a rare case
      currentSize -= prev.getSize();
    } finally {
      if (stamp != 0) {
        lock.unlockWrite(stamp);
      }
    }
    if (prev != value) {
      evictValue(prev);
    }
  }

  /**
   * Puts all the values from the given map into the cache.
   *
   * @param m The map containing entries to put into the cache
   */
  public void putAll(Map<Key, Value> m) {
    final long stamp = lock.writeLock();
    try {
      m.forEach((k,v) -> this.put(k, v, true));
    } finally {
      lock.unlockWrite(stamp);
    }
  }
}
