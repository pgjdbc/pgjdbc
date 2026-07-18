/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.Oid;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifies every scalar built-in type in {@link Oid} has a matching {@link PGSQLType} constant, so
 * {@code setObject(int, Object, SQLType)} and {@code registerOutParameter(int, SQLType)} can reach
 * it by name. Landing a codec (for example {@code Oid8Codec}, {@code Xid8Codec}) wires a new
 * {@link Oid} constant into {@link CodecRegistry} but does not, by itself, add the parallel
 * {@link PGSQLType} constant -- this check catches that gap the same way {@code
 * org.postgresql.core.OidValuesCorrectnessTest} catches a stale OID value.
 */
class PGSQLTypeCompletenessTest {

  /**
   * {@link Oid} field names that intentionally have no {@link PGSQLType} counterpart. Every
   * {@code *_ARRAY} field is excluded separately below -- {@link PGSQLType} has no array entries at
   * all, since a {@code java.sql.Array} is bound through {@code setArray}, not through a scalar
   * {@code SQLType}.
   */
  private static final Set<String> IGNORED = new HashSet<>(Arrays.asList(
      // sentinel, not a real type
      "UNSPECIFIED",
      // anonymous composite; no scalar SQLType applies
      "RECORD",
      // backward-compat alias, same OID as REFCURSOR
      "REF_CURSOR",
      // extension type; OID is installation-dependent, same reason OidValuesCorrectnessTest
      // excludes it
      "HSTORE",
      // PostgreSQL's internal single-byte "char" (oid 18) has no dedicated codec; the SQL alias
      // "char" for character/bpchar already claims that name in
      // CodecRegistry.registerBuiltinAlias, so a PGSQLType("char") entry here would be ambiguous
      // with -- and resolve to the wrong type as -- that alias
      "CHAR"
  ));

  @Test
  void everyScalarOidHasAMatchingPGSQLType() throws IllegalAccessException {
    List<String> missing = new ArrayList<>();
    List<String> wrongOid = new ArrayList<>();
    for (Field field : Oid.class.getFields()) {
      String name = field.getName();
      if (name.endsWith("_ARRAY") || IGNORED.contains(name)) {
        continue;
      }
      int expectedOid = field.getInt(null);
      PGSQLType type;
      try {
        type = PGSQLType.valueOf(name);
      } catch (IllegalArgumentException e) {
        missing.add(name);
        continue;
      }
      if (type.getOid() != expectedOid) {
        wrongOid.add(name + " (PGSQLType." + name + ".getOid()=" + type.getOid()
            + ", Oid." + name + "=" + expectedOid + ")");
      }
    }
    assertEquals(emptyList(), missing,
        "Oid.java constants with no matching PGSQLType entry. Add one (matching the constant's "
            + "own name in upper case), or list the name in IGNORED above with a reason.");
    assertEquals(emptyList(), wrongOid,
        "PGSQLType entries whose OID doesn't match the same-named Oid.java constant.");
  }
}
