/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.fuzzkit.coercion.LeafRepr;

/**
 * A generated array value with its two axes: the number of dimensions and the leaf representation. The
 * value is a nested Java array of the descriptor's leaf class -- a boxed {@code Integer[][]} (which may
 * carry SQL NULL leaves) or a primitive {@code int[][]} (non-null leaves only). The array's backend
 * type and the target class the codec decodes to are both derived from the descriptor at
 * {@code arrayOid} together with {@code leafRepr} and {@code ndim}.
 *
 * <p>{@code PgValueArgumentsFactory} builds these from jetCheck so the descriptor, the dimension, the
 * leaf representation, and every leaf value all vary under the guided byte stream.
 */
public final class FuzzArray {

  public final int arrayOid;
  public final LeafRepr leafRepr;
  public final int ndim;
  public final Object value;

  public FuzzArray(int arrayOid, LeafRepr leafRepr, int ndim, Object value) {
    this.arrayOid = arrayOid;
    this.leafRepr = leafRepr;
    this.ndim = ndim;
    this.value = value;
  }
}
