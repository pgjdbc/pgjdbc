/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code int8} (bigint) values: the type's own min/max and, since a stored {@code int8} is often
 * read back through a narrower getter, the {@code int}/{@code short}/{@code byte} boundaries (and one past
 * them) that make {@code getInt}/{@code getShort}/{@code getByte} overflow.
 */
public final class Int8EdgeCases {
  /**
   * Literals no {@code int8in} accepts: the empty string and non-ASCII look-alikes of a digit and a minus
   * sign, which additionally walk the multi-byte path of whatever charset decodes the wire text.
   *
   * <p>Deliberately absent from {@link #ALL}: every entry there casts cleanly and callers rely on that. Use
   * this one for the refusal side only.
   */
  public static final List<EdgeCase> MALFORMED = Collections.unmodifiableList(malformed());

  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private Int8EdgeCases() {
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
    out.add(at("long_min", "-9223372036854775808"));
    out.add(at("long_max", "9223372036854775807"));
    out.add(at("zero", "0"));
    out.add(at("minus_one", "-1"));
    out.add(at("one", "1"));
    out.add(at("int_max", "2147483647"));
    out.add(at("int_max_plus_1", "2147483648"));
    out.add(at("int_min", "-2147483648"));
    out.add(at("int_min_minus_1", "-2147483649"));
    out.add(at("short_max_plus_1", "32768"));
    out.add(at("byte_max_plus_1", "128"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, Long.valueOf(literal));
  }
}
