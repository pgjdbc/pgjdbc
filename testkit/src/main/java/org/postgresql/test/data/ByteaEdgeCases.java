/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code bytea} values, given as PostgreSQL hex literals ({@code \xNN}): the empty string, single
 * bytes at the range ends, byte sequences that include NUL and high bytes (the ones a naive text rendering
 * mangles), and a range of lengths around byte and 16-bit-length boundaries (255/256, 65535/65536) that
 * stress the length handling and buffering.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}); the literal is the {@code \x...} hex text.
 */
public final class ByteaEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private ByteaEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("empty", "\\x"));
    out.add(at("single_zero", "\\x00"));
    out.add(at("single_ff", "\\xff"));
    out.add(at("ascii_hello", "\\x48656c6c6f"));
    out.add(at("nul_and_high", "\\x0001027f80ff"));
    out.add(at("high_bytes", "\\xdeadbeefcafe"));
    // Lengths around byte and 16-bit-length boundaries.
    out.add(sized("len_255", 255));
    out.add(sized("len_256", 256));
    out.add(sized("len_257", 257));
    out.add(sized("len_1024", 1024));
    out.add(sized("len_65535", 65535));
    out.add(sized("len_65536", 65536));
    out.add(sized("len_100000", 100000));
    return out;
  }

  private static EdgeCase sized(String name, int length) {
    StringBuilder sb = new StringBuilder(2 + length * 2).append("\\x");
    for (int i = 0; i < length; i++) {
      int b = i & 0xff;
      sb.append(Character.forDigit((b >> 4) & 0xf, 16));
      sb.append(Character.forDigit(b & 0xf, 16));
    }
    return at(name, sb.toString());
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
