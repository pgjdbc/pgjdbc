/*-------------------------------------------------------------------------
*
* Copyright (c) 2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.util;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caches values in simple least-recently-accessed order.
 */
public class LruCache<Key, Value extends CanEstimateSize> {
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

  private final EvictAction<Value> onEvict;
  private final CreateAction<Key, Value> createAction;
  private final int maxSizeEntries;
  private final long maxSizeBytes;
  private long currentSize;

  private final Map<Key, Value> cache = new LinkedHashMap<Key, Value>() {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Key, Value> eldest) {
      // Avoid creating iterators if size constraints not violated
      if (size() <= maxSizeEntries && currentSize <= maxSizeBytes) {
        return false;
      }

      for (Iterator<Map.Entry<Key, Value>> it = entrySet().iterator(); it.hasNext(); ) {
        if (size() <= maxSizeEntries && currentSize <= maxSizeBytes) {
          return false;
        }

        Map.Entry<Key, Value> entry = it.next();
        evictValue(entry.getValue());
        long valueSize = entry.getValue().getSize();
        if (valueSize > 0) {
          // just in case
          currentSize -= valueSize;
        }
        it.remove();
      }
      return false;
    }
  };

  private void evictValue(Value value) {
    try {
      onEvict.evict(value);
    } catch (SQLException e) {
            /* ignore */
    }
  }

  public LruCache(int maxSizeEntries, long maxSizeBytes, CreateAction<Key, Value> createAction,
      EvictAction<Value> onEvict) {
    this.maxSizeEntries = maxSizeEntries;
    this.maxSizeBytes = maxSizeBytes;
    this.createAction = createAction;
    this.onEvict = onEvict;
  }

  /**
   * Borrows an entry from the cache.
   *
   * @param key cache key
   * @return entry from cache or newly created entry if cache does not contain given key.
   * @throws SQLException if entry creation fails
   */
  public Value borrow(Key key) throws SQLException {
    Value value = cache.remove(key);
    if (value == null) {
      return createAction.create(key);
    }
    currentSize -= value.getSize();
    return value;
  }

  /**
   * Returns given value to the cache
   *
   * @param key   key
   * @param value value
   */
  public void put(Key key, Value value) {
    long valueSize = value.getSize();
    if (maxSizeBytes == 0 || maxSizeEntries == 0 || valueSize * 2 > maxSizeBytes) {
      // Just destroy the value if cache is disabled or if entry would consume more than a half of the cache
      evictValue(value);
      return;
    }
    currentSize += valueSize;
    cache.put(key, value);
  }
}
