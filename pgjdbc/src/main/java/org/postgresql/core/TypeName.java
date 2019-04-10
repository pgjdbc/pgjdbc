/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

/**
 * A type name containing both the namespace and name.
 * Supports quoted namespace and quoted name to maintain case.
 * When a namespace or name contains a period it will be quoted.
 * Any unquoted name is converted to lowercase.
 */
// TODO: Is it worth having this interface and its separate implementation?
// TODO: What about arrays?
public interface TypeName extends Comparable<TypeName> {

  /**
   * Determines if this type is on the {@code search_path} (TODO: Link).  When
   * on the search_path, the namespace is not included in {@link #toString()}.
   * The namespace, however, is always included in {@link #getCanonicalName()}.
   */
  boolean isOnPath();

  /**
   * Gets the namespace of this type, without any quoting.
   *
   * @see #getNamespaceQuoted()
   */
  String getNamespace();

  /**
   * Gets the namespace of this type, quoted as-needed.
   *
   * @see #getNamespace()
   */
  String getNamespaceQuoted();

  /**
   * Gets the per-namespace unique name of this type, without any quoting.
   *
   * @see #getNameQuoted()
   */
  String getName();

  /**
   * Gets the per-namespace unique name of this type, quoted as-needed.
   *
   * @see #getName()
   */
  String getNameQuoted();

  /**
   * When {@link #getNamespace() the namespace} is {@link #isOnPath() on the
   * path}, returns the {@link #getNameQuoted() possibly-quoted name}.
   * Otherwise returns {@link #getCanonicalName() the canonical name}.
   * <p>
   * This form should be most compatible the way psql (TODO: link) represents
   * type names.
   * </p>
   */
  @Override
  String toString();

  /**
   * Gets the fully qualified, canonical form of this type name:
   * the {@link #getNamespaceQuoted() possibly-quoted namespace} and
   * the {@link #getNameQuoted() possibly-quoted name} separated
   * by a {@code "."}.
   * <p>
   * This name should be used where resilience to {@code search_path} changes is
   * desired, such as when the driver communicates with the backend.
   * </p>
   */
  String getCanonicalName();

  /**
   * The hash code must be computed as the hash code of
   * {@link #getNamespace() the namespace} {@code * 31 +} the hash code of
   * {@link #getName() the name}.
   *
   * @return the hash code based on {@link #getCanonicalName()} and
   *         {@link #getName()}
   */
  // Java 8: default method here
  @Override
  int hashCode();

  /**
   * Two type names are equal when both their {@link #getCanonicalName()
   * canonical name} and {@link #getName() name} are equal, case-sensitive.
   */
  // Java 8: default method here
  @Override
  public boolean equals(Object obj);

  /**
   * Type names are compared by namespace, then name.
   */
  // Java 8: default method here
  @Override
  int compareTo(TypeName other);
}
