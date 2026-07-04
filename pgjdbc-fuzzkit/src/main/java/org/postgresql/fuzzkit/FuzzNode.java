/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import java.util.List;

/**
 * A generated composite tree: a record, either a named composite type or an anonymous {@code RECORD},
 * whose fields are each a scalar leaf or a nested record. {@code PgValueArgumentsFactory} builds
 * these recursively (bounded by jetCheck's size budget) and honours PostgreSQL's rule that a named
 * composite cannot carry a {@code record}-typed field: a named node's record children are named,
 * while an anonymous node's may be named or anonymous. It drives both nested-composite codec paths,
 * including the mixed anonymous-holding-named shape.
 */
public final class FuzzNode {

  final List<Field> fields;
  /** {@code true} for an anonymous {@code RECORD}, {@code false} for a named composite type. */
  final boolean anonymous;

  public FuzzNode(List<Field> fields, boolean anonymous) {
    this.fields = fields;
    this.anonymous = anonymous;
  }

  /** One record field: a scalar leaf ({@code scalarOid > 0}) or a nested record. */
  public static final class Field {

    final int scalarOid;
    final Object scalarValue;
    final FuzzNode nested;

    private Field(int scalarOid, Object scalarValue, FuzzNode nested) {
      this.scalarOid = scalarOid;
      this.scalarValue = scalarValue;
      this.nested = nested;
    }

    public static Field scalar(int oid, Object value) {
      return new Field(oid, value, null);
    }

    public static Field record(FuzzNode nested) {
      return new Field(0, null, nested);
    }

    boolean isScalar() {
      return scalarOid > 0;
    }
  }
}
