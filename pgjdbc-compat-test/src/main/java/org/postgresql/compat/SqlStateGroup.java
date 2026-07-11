/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Buckets a SQLState into a semantic group so the oracle compares the <em>kind</em> of failure rather than
 * its exact code.
 *
 * <p>When both drivers refuse a coercion, the codec path often reports a different SQLState than the
 * baseline (for example {@code 42821 datatype_mismatch} where the baseline raised {@code 22007
 * invalid_datetime_format}). Both mean "this value cannot be read as that type", so they share a group and
 * the difference is treated as compatible. A shift that crosses groups -- say a coercion refusal turning
 * into a connection error ({@code 08...}) -- stays a real, reportable difference.
 *
 * <p>Codes not listed fall back to their two-character SQLState class, which keeps unrelated classes
 * distinct. Extend {@link #GROUPS} as new equivalent codes show up.
 */
public final class SqlStateGroup {
  /** Group for "the value cannot be coerced to the requested type", spanning classes 22, 42 and 0A. */
  public static final String NOT_COERCIBLE = "value-not-coercible";

  private static final Map<String, String> GROUPS = new LinkedHashMap<>();

  static {
    // Data exceptions raised while attempting a coercion.
    GROUPS.put("22003", NOT_COERCIBLE); // numeric_value_out_of_range
    GROUPS.put("22007", NOT_COERCIBLE); // invalid_datetime_format
    GROUPS.put("22008", NOT_COERCIBLE); // datetime_field_overflow
    GROUPS.put("22023", NOT_COERCIBLE); // invalid_parameter_value (pgjdbc CANNOT_COERCE)
    GROUPS.put("22P02", NOT_COERCIBLE); // invalid_text_representation
    GROUPS.put("42820", NOT_COERCIBLE); // numeric_constant_out_of_range (value too large for the target)
    // Type-mismatch / cannot-cast, reported by the codec path and the server.
    GROUPS.put("42804", NOT_COERCIBLE); // datatype_mismatch
    GROUPS.put("42821", NOT_COERCIBLE); // datatype_mismatch (assignment)
    GROUPS.put("42846", NOT_COERCIBLE); // cannot_coerce
    GROUPS.put("0A000", NOT_COERCIBLE); // feature_not_supported (unsupported conversion)
  }

  private SqlStateGroup() {
  }

  /** Returns the group for a SQLState: an explicit bucket, or the two-character class as a fallback. */
  public static String of(@Nullable String sqlState) {
    if (sqlState == null || sqlState.isEmpty()) {
      return "no-state";
    }
    String group = GROUPS.get(sqlState);
    if (group != null) {
      return group;
    }
    return sqlState.length() >= 2 ? sqlState.substring(0, 2) : sqlState;
  }
}
