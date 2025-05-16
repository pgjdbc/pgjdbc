/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

/**
 * Provides a default implementation for {@link SqlSerializationContext}.
 */
enum DefaultSqlSerializationContext implements SqlSerializationContext {
  STDSTR_IDEMPOTENT(true, true),
  NONSTDSTR_IDEMPOTENT(false, true),
  STDSTR_NONIDEMPOTENT(true, false),
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
