/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
package org.postgresql.types;

import java.util.HashMap;
import java.util.Map;

public class TypeRegistry {
  private Map<String, Type> typeMap;
  private static TypeRegistry instance;

  // Initialize the type map with predefined types
  private TypeRegistry() {
    typeMap = new HashMap<>();
  }

  public static TypeRegistry getInstance() {
    return instance == null ? new TypeRegistry() : instance;
  }

  // Look up the type in the type map
  public Type loadType(String typeName) {
    return typeMap.computeIfAbsent(typeName, c -> new Type());
  }
}
