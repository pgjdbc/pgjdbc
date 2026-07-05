/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.util.PGobject;

/**
 * A generated {@code json} / {@code jsonb} value for the JQF codec round-trip targets: a
 * {@link PGobject} holding a valid, non-empty JSON literal. It is a distinct holder class only so
 * {@link PgValueArgumentsFactory} can tell a JSON-shaped {@code PGobject} apart from the bit-string
 * {@link FuzzBit}; both decode back to {@link PGobject}, which compares by value alone, so the same
 * holder serves {@code json} and {@code jsonb}.
 */
final class FuzzJson {

  private final PGobject value;

  FuzzJson(PGobject value) {
    this.value = value;
  }

  PGobject value() {
    return value;
  }
}
