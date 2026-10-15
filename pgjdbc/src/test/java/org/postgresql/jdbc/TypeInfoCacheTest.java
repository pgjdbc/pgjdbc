/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every Java class name in the built-in type table that the {@link TypeInfoCache} constructor
 * registers names a class that the test class loader loads. {@link TypeInfoCache#getJavaArrayType(String)} finds an
 * array type by that name, so a misspelled name makes the array type unreachable from
 * {@code setObject}. The {@code box} type used to carry {@code org.postgresql.geometric.PGBox}, a
 * class that does not exist.
 */
class TypeInfoCacheTest {

  static List<Arguments> builtInTypes() throws SQLException {
    // The connection throws on every call, so every name the loop reads comes from the built-in
    // table rather than from a pg_type query.
    BaseConnection noServer = (BaseConnection) Proxy.newProxyInstance(
        TypeInfoCacheTest.class.getClassLoader(),
        new Class<?>[]{BaseConnection.class},
        (proxy, method, args) -> {
          throw new UnsupportedOperationException(method.toString());
        });
    TypeInfoCache cache = new TypeInfoCache(noServer, -1);
    Map<String, String> javaClassByPgType = new TreeMap<>();
    for (Iterator<Integer> oids = cache.getPGTypeOidsWithSQLTypes(); oids.hasNext(); ) {
      int oid = oids.next();
      javaClassByPgType.put(cache.getPGType(oid), cache.getJavaClass(oid));
    }
    List<Arguments> result = new ArrayList<>();
    for (Map.Entry<String, String> e : javaClassByPgType.entrySet()) {
      result.add(Arguments.of(e.getKey(), e.getValue()));
    }
    return result;
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("builtInTypes")
  void javaClassNameLoads(String pgType, String javaClass) throws ClassNotFoundException {
    Class.forName(javaClass, false, TypeInfoCacheTest.class.getClassLoader());
  }
}
