/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

/**
 * A generated anonymous {@code RECORD} shape: a list of field type OIDs paired with matching Java
 * values. {@code PgValueArgumentsFactory} builds these from jetCheck so the number of fields, their
 * types, and their values all vary under the guided byte stream.
 */
public final class FuzzRecord {

  public final int[] fieldOids;
  public final Object[] values;

  public FuzzRecord(int[] fieldOids, Object[] values) {
    this.fieldOids = fieldOids;
    this.values = values;
  }
}
