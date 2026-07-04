/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgType;

import java.util.Collections;

/**
 * Shared factory for the single-field {@code public.ct} composite used across the coercion fuzzers.
 * The one attribute {@code f} carries the type under test, so the whole fuzzer matrix reaches every
 * scalar type through the same composite shape.
 */
public final class FuzzComposites {

  static final int SINGLE_FIELD_COMPOSITE_OID = 90_030;

  private FuzzComposites() {
  }

  /** A single-field composite {@code public.ct} whose one attribute {@code f} has the given OID. */
  static PgType singleField(int fieldOid) {
    return new PgType(new ObjectName("public", "ct"), "public.ct", SINGLE_FIELD_COMPOSITE_OID, 'c',
        'C', -1, 0, 0, 0, ',', Collections.singletonList(new PgField("f", fieldOid, 1, -1)));
  }
}
