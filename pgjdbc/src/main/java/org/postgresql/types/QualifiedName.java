/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.types;

import java.util.Objects;

public class QualifiedName {

  private String namespace;
  private String localName;

  private String checkNotNull(String str) throws IllegalArgumentException {
    if (null == str) {
      throw new IllegalArgumentException("Null value for '" + str + "'");
    }
    return str;
  }

  public QualifiedName(String namespace, String localName) {
    this.namespace = checkNotNull(namespace);
    this.localName = checkNotNull(localName);
  }

  public String getLocalName() {
    return localName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    QualifiedName that = (QualifiedName) o;
    return namespace.equals(that.namespace) &&
        localName.equals(that.localName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(namespace, localName);
  }

  @Override
  public String toString() {
    return toString(true);
  }

  public String toString(boolean unqualifyCommonSchemas) {
    if (unqualifyCommonSchemas && (namespace.equals(Type.CATALOG_NAMESPACE) || namespace.equals(Type.PUBLIC_NAMESPACE))) {
      return quoteIfNeeded(localName);
    }
    return quoteIfNeeded(namespace) + "." + quoteIfNeeded(localName);
  }

  /**
   * Move to better location.
   *
   * Quote an identifier if its required.
   *
   * Attempts to do so creating no garbage when not needing a quote.
   *
   * @param ident Identifier to quote, if necessary
   * @return Quoted version of {@code ident}
   */
  public static String quoteIfNeeded(String ident) {
    int first = ident.codePointAt(0);
    int firstType = Character.getType(first);
    StringBuilder bldr = null;
    if (firstType != Character.LOWERCASE_LETTER && first != '_') {
      bldr = new StringBuilder("\"").appendCodePoint(first);
    }
    for (int idx = 1; idx < ident.length(); ++idx) {
      int ch = ident.codePointAt(idx);
      switch (Character.getType(ch)) {
      case Character.LOWERCASE_LETTER:
      case Character.DECIMAL_DIGIT_NUMBER:
        if (bldr == null) continue;
      default:
        switch (ch) {
        case '_':
          if (bldr == null) continue;
          bldr.appendCodePoint(ch);
          break;
        case '"':
          if (bldr == null) bldr = new StringBuilder("\"").append(ident, 0, idx);
          bldr.append('"').append('"');
          break;
        default:
          if (bldr == null) bldr = new StringBuilder("\"").append(ident, 0, idx);
          bldr.appendCodePoint(ch);
        }
      }
    }
    return bldr == null ? ident : bldr.append('"').toString();
  }
}
