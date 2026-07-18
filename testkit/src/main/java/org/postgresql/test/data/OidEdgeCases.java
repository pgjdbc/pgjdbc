/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code oid} values. An {@code oid} is an unsigned 32-bit integer, so it reaches values above
 * {@code Integer.MAX_VALUE} that a signed {@code getInt} cannot hold; this catalogue covers zero, the
 * signed-int boundary, and the unsigned maximum ({@code 4294967295}).
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class OidEdgeCases {
  /**
   * Literals no {@code oidin} accepts: the empty string and non-ASCII look-alikes of a digit and a minus
   * sign, which additionally walk the multi-byte path of whatever charset decodes the wire text.
   *
   * <p>Deliberately absent from {@link #ALL}: every entry there casts cleanly and callers rely on that. Use
   * this one for the refusal side only.
   */
  public static final List<EdgeCase> MALFORMED = Collections.unmodifiableList(malformed());

  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private OidEdgeCases() {
  }

  private static List<EdgeCase> malformed() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(new EdgeCase("malformed_empty", "", null));
    // U+FF11 U+FF12 U+FF13, fullwidth one, two, three.
    out.add(new EdgeCase("malformed_non_ascii_digits", "１２３", null));
    // U+2212, the typographic minus that is not the ASCII hyphen-minus.
    out.add(new EdgeCase("malformed_non_ascii_minus", "−1", null));
    return out;
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("zero", "0"));
    out.add(at("one", "1"));
    out.add(at("int_max", "2147483647"));
    out.add(at("above_int_max", "2147483648"));
    out.add(at("unsigned_max", "4294967295"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
