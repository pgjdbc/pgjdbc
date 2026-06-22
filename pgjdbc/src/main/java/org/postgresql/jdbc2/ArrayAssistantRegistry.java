/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc2;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.UUIDArrayAssistant;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Array assistants register here.
 *
 * @author Minglei Tu
 */
public class ArrayAssistantRegistry {
  private static final ConcurrentMap<Integer, ArrayAssistant> ARRAY_ASSISTANT_MAP =
      new ConcurrentHashMap<>();

  static {
    ArrayAssistantRegistry.register(Oid.UUID, new UUIDArrayAssistant());
    ArrayAssistantRegistry.register(Oid.UUID_ARRAY, new UUIDArrayAssistant());
  }

  public static @Nullable ArrayAssistant getAssistant(int oid) {
    return ARRAY_ASSISTANT_MAP.get(oid);
  }

  public static void register(int oid, ArrayAssistant assistant) {
    ARRAY_ASSISTANT_MAP.put(oid, assistant);
  }
}
