/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.TypeInfo;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Map} view that delegates to a user-supplied JDBC type map and
 * additionally resolves lookup keys through
 * {@link TypeInfo#getPgTypeByPgName(String)} so that spec-allowed identifier
 * forms — bare, schema-qualified, fully quoted, partial-quoted, mixed-case,
 * aliases — all match the same map entry as long as both keys refer to the
 * same PostgreSQL type OID.
 *
 * <p>Mutating methods delegate to the underlying user map. Lookup ({@link #get}
 * / {@link #containsKey}) first tries the literal key; on a miss it resolves
 * both the lookup key and each user-supplied key to a PostgreSQL OID via
 * {@code regtype} cast (cached by the connection's {@link TypeInfo}) and
 * matches on OID.</p>
 *
 * <p>The slow-path iteration is bounded by the size of the user map (typically
 * a handful of entries) and amortized by {@code TypeInfo}'s per-connection
 * cache, so a missed direct lookup costs at most a few cache hits.</p>
 */
// KeyFor relations bind to the delegate map; the wrapper deliberately accepts
// lookup keys outside that domain (any equivalent identifier form) and forwards
// mutations directly, so KeyFor cannot be enforced at the wrapper boundary.
@SuppressWarnings("keyfor")
final class IdentifierNormalizingTypeMap implements Map<String, Class<?>> {
  private final Map<String, Class<?>> delegate;
  private final TypeInfo typeInfo;

  IdentifierNormalizingTypeMap(Map<String, Class<?>> delegate, TypeInfo typeInfo) {
    this.delegate = delegate;
    this.typeInfo = typeInfo;
  }

  /**
   * Returns {@code map} wrapped in an {@code IdentifierNormalizingTypeMap},
   * or {@code map} unchanged when it is empty or already a wrapper. Use at
   * JDBC-API entry points (e.g. {@code ResultSet.getObject(int, Map)}) and at
   * the {@code CodecContext} boundary to route the user-supplied map through
   * identifier-form normalization without ever double-wrapping.
   */
  static Map<String, Class<?>> of(Map<String, Class<?>> map, TypeInfo typeInfo) {
    if (map.isEmpty() || map instanceof IdentifierNormalizingTypeMap) {
      return map;
    }
    return new IdentifierNormalizingTypeMap(map, typeInfo);
  }

  @Override
  public int size() {
    return delegate.size();
  }

  @Override
  public boolean isEmpty() {
    return delegate.isEmpty();
  }

  @Override
  public boolean containsKey(@Nullable Object key) {
    return get(key) != null;
  }

  @Override
  public boolean containsValue(@Nullable Object value) {
    // pgjdbc's annotated JDK rejects nulls for containsValue; JDBC type maps
    // never store null values, so the answer is unambiguously false.
    return value != null && delegate.containsValue(value);
  }

  @Override
  public @Nullable Class<?> get(@Nullable Object key) {
    if (key == null) {
      return null;
    }
    Class<?> direct = delegate.get(key);
    if (direct != null) {
      return direct;
    }
    if (delegate.isEmpty() || !(key instanceof String)) {
      return null;
    }
    int lookupOid;
    try {
      lookupOid = typeInfo.getPgTypeByPgName((String) key).getOid();
    } catch (SQLException e) {
      return null;
    }
    for (Map.Entry<String, Class<?>> entry : delegate.entrySet()) {
      try {
        int userOid = typeInfo.getPgTypeByPgName(entry.getKey()).getOid();
        if (userOid == lookupOid) {
          return entry.getValue();
        }
      } catch (SQLException ignored) {
        // Skip entries that fail to resolve — likely garbage keys.
      }
    }
    return null;
  }

  @Override
  public @Nullable Class<?> put(String key, Class<?> value) {
    return delegate.put(key, value);
  }

  @Override
  public @Nullable Class<?> remove(@Nullable Object key) {
    if (key == null) {
      return null;
    }
    return delegate.remove(key);
  }

  @Override
  public void putAll(Map<? extends String, ? extends Class<?>> m) {
    delegate.putAll(m);
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  @Override
  public Set<String> keySet() {
    return delegate.keySet();
  }

  @Override
  public Collection<Class<?>> values() {
    return delegate.values();
  }

  @Override
  public Set<Map.Entry<String, Class<?>>> entrySet() {
    return delegate.entrySet();
  }
}
