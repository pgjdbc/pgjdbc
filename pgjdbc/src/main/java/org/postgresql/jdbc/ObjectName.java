/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * Represents a PostgreSQL object name, which consists of a namespace and a name.
 */
public class ObjectName {
  final @Nullable String namespace;
  final String name;

  /**
   * Constructs a new ObjectName.
   *
   * @param namespace the namespace, or null for unqualified names
   * @param name the name
   */
  public ObjectName(@Nullable String namespace, String name) {
    this.namespace = namespace;
    this.name = name;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ObjectName that = (ObjectName) o;
    return Objects.equals(namespace, that.namespace) && Objects.equals(name, that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(namespace) * 31 + name.hashCode();
  }

  /**
   * Gets the namespace.
   *
   * @return the namespace, or null for unqualified names
   */
  public @Nullable String getNamespace() {
    return namespace;
  }

  /**
   * Gets the name.
   *
   * @return the name
   */
  public String getName() {
    return name;
  }
}
