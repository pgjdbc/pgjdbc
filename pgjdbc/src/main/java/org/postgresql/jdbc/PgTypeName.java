/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import java.util.Locale;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TypeName;

public class PgTypeName implements TypeName {

  /**
   * Checks if a namespace or name requires quoting.
   * <p>
   * TODO: When the namespace or name contains reserved words, it will also be quoted.
   * </p>
   * <p>
   * TODO: Is there a well-defined source of rules on how the backend/psql determines when to quote?
   * </p>
   */
  public static boolean requiresQuotes(String name) {
    // TODO: We can do more than this.  This just matches TypeInfoCache's old
    //       implementation.
    // TODO: should probably check for all special chars
    // TODO: If empty name allowed
    return name.isEmpty()
        || name.indexOf('.') != -1
        || !name.equals(name.toLowerCase(Locale.ROOT));
  }

  // TODO: valueOf method, to be inverse of toString, toString should return fully qualified name.
  //       Maybe other code that cares to not include the schema should just do it there?

  private final boolean isOnPath;
  private final String namespace;
  private final String namespaceQuoted;
  private final String name;
  private final String nameQuoted;

  // TODO: Constructor from canonical form - or parse in valueOf method?
  // TODO: Take a BaseConnection and lookup isOnPath (from possibly-cached)
  //       values as-needed?
  /**
   * Constructs the type name.
   *
   * @param namespace the namespace of this type
   * @param name the per-namespace unique name
   * @param isOnPath is this type on the {@code search_path}?
   */
  public PgTypeName(String namespace, String name, boolean isOnPath) {
    this.isOnPath = isOnPath;
    this.namespace = namespace;
    this.namespaceQuoted = requiresQuotes(namespace)
        ? ('"' + namespace + '"')
        : namespace;
    this.name = name;
    this.nameQuoted = requiresQuotes(name)
        ? ('"' + name + '"')
        : name;
  }

  /**
   * Constructs the type name with {@code isOnPath = false}.
   *
   * @param namespace the namespace of this type
   * @param name the per-namespace unique name
   */
  public PgTypeName(String namespace, String name) {
    this(namespace, name, false);
  }

  /**
   * {@inheritDoc}
   * <p>
   * TODO: We are assuming the {@code search_path} does not change for the life
   *       of the {@link TypeInfoCache}, which currently is the life of a
   *       {@link BaseConnection}.  This means this value can be incorrect.  Is
   *       there a better way to handle this without causing frequent/polling
   *       checks on the backend?  Is this documented?  Should it be documented?
   *       Should we use {@link #getCanonicalName()} in all names we return
   *       back, such as from
   *       {@link PgResultSetMetaData#getColumnTypeName(int)}?
   */
  @Override
  public boolean isOnPath() {
    return isOnPath;
  }

  @Override
  public String getNamespace() {
    return namespace;
  }

  @Override
  public String getNamespaceQuoted() {
    return namespaceQuoted;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getNameQuoted() {
    return nameQuoted;
  }

  @Override
  public String toString() {
    return isOnPath ? getNameQuoted() : getCanonicalName();
  }

  @Override
  public String getCanonicalName() {
    return getNamespaceQuoted() + '.' + getNameQuoted();
  }

  @Override
  public int hashCode() {
    return getNamespace().hashCode() * 31 + getName().hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof TypeName)) {
      return false;
    }
    TypeName other = (TypeName)obj;
    return
        // name first, since it will vary more
        getName().equals(other.getName())
        && getNamespace().equals(other.getNamespace());
  }

  @Override
  public int compareTo(TypeName other) {
    int diff = getNamespace().compareTo(other.getNamespace());
    if (diff != 0) {
      return diff;
    }
    return getName().compareTo(other.getName());
  }
}
