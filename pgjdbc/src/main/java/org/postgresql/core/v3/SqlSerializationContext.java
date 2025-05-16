/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

/**
 * Specifies the properties required to convert SQL to String.
 */
public interface SqlSerializationContext {
  /**
   * Returns SqlSerializationContext instance with the given parameters
   * @param standardConformingStrings true when string literals should be standard conforming
   * @param idempotent true when idempotent conversion is needed
   * @return Returns SqlSerializationContext instance with the given parameters
   */
  static SqlSerializationContext of(boolean standardConformingStrings, boolean idempotent) {
    if (standardConformingStrings) {
      return idempotent
          ? DefaultSqlSerializationContext.STDSTR_IDEMPOTENT
          : DefaultSqlSerializationContext.STDSTR_NONIDEMPOTENT;
    }
    return idempotent
        ? DefaultSqlSerializationContext.NONSTDSTR_IDEMPOTENT
        : DefaultSqlSerializationContext.NONSTDSTR_NONIDEMPOTENT;
  }

  /**
   * Returns true if strings literals should use {@code standard_conforming_strings=on} encoding.
   * @return true if strings literals should use {@code standard_conforming_strings=on} encoding.
   */
  boolean getStandardConformingStrings();

  /**
   * Returns true if the SQL to String conversion should be idempotent.
   * For instance, if a query parameter comes from an {@link java.io.InputStream},
   * then the stream could be skipped when writing SQL with idempotent mode.
   * @return true if the SQL to String conversion should be idempotent
   */
  boolean getIdempotent();
}
