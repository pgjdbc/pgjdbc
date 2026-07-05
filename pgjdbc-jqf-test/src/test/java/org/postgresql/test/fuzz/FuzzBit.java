/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.util.PGobject;

/**
 * A generated {@code bit} / {@code varbit} value for the JQF codec round-trip targets: a
 * {@link PGobject} holding a non-empty bit string (a run of {@code '0'} and {@code '1'}). It is a
 * distinct holder class only so {@link PgValueArgumentsFactory} can tell a bit-string {@code PGobject}
 * apart from the JSON-shaped {@link FuzzJson}; both decode back to {@link PGobject}, which compares by
 * value alone, so the same holder serves {@code bit} and {@code varbit}.
 */
final class FuzzBit {

  private final PGobject value;

  FuzzBit(PGobject value) {
    this.value = value;
  }

  PGobject value() {
    return value;
  }
}
