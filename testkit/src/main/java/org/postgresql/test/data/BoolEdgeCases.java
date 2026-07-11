/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code bool} values: the two truth values. The literals are the server's own {@code t}/{@code
 * f} text form, so the read side covers how each getter renders a boolean (text {@code true}/{@code false},
 * the wire byte, the numeric view).
 */
public final class BoolEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private BoolEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(new EdgeCase("true", "t", true));
    out.add(new EdgeCase("false", "f", false));
    return out;
  }
}
