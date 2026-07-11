/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code int2} values: the type's own min/max plus the {@code byte} boundaries (and one past
 * them) that make {@code getByte} overflow. The value carries an {@link Integer}, since {@code int2} fits.
 */
public final class Int2EdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private Int2EdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("short_min", "-32768"));
    out.add(at("short_max", "32767"));
    out.add(at("zero", "0"));
    out.add(at("minus_one", "-1"));
    out.add(at("one", "1"));
    out.add(at("byte_max", "127"));
    out.add(at("byte_max_plus_1", "128"));
    out.add(at("byte_min", "-128"));
    out.add(at("byte_min_minus_1", "-129"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, Integer.valueOf(literal));
  }
}
