/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

/**
 * Provides a default implementation for {@link SqlSerializationContext}.
 */
enum DefaultSqlSerializationContext implements SqlSerializationContext {
  /**
   * Render SQL in a repeatable way (avoid consuming {@link java.io.InputStream} sources),
   * use standard_conforming_strings=yes string literals.
   * This option is useful for {@code toString()} implementations as it does induce side effects.
   */
  STDSTR_IDEMPOTENT(true, true),

  /**
   * Render SQL with replacing all the parameters, including {@link java.io.InputStream} sources.
   * Use standard_conforming_strings=yes for string literals.
   * This option is useful for rendering an executable SQL.
   */
  STDSTR_NONIDEMPOTENT(true, false),

  // Auxiliary options as standard_conforming_strings=on since PostgreSQL 9.1

  /**
   * Render SQL in a repeatable way (avoid consuming {@link java.io.InputStream} sources),
   * use standard_conforming_strings=no string literals.
   * The entry is for completeness only as standard_conforming_strings=no should probably be avoided.
   */
  NONSTDSTR_IDEMPOTENT(false, true),

  /**
   * Render SQL with replacing all the parameters, including {@link java.io.InputStream} sources.
   * Use standard_conforming_strings=no for string literals.
   * The entry is for completeness only as standard_conforming_strings=no should probably be avoided.
   */
  NONSTDSTR_NONIDEMPOTENT(false, false),
  ;

  private final boolean standardConformingStrings;
  private final boolean idempotent;

  DefaultSqlSerializationContext(boolean standardConformingStrings, boolean idempotent) {
    this.standardConformingStrings = standardConformingStrings;
    this.idempotent = idempotent;
  }

  @Override
  public boolean getStandardConformingStrings() {
    return standardConformingStrings;
  }

  @Override
  public boolean getIdempotent() {
    return idempotent;
  }
}
