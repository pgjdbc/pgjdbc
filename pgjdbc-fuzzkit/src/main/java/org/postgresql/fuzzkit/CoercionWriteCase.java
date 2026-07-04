/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.fuzzkit.coercion.ScalarDescriptor;

/**
 * One cell of the SQLData write coercion matrix: a Java value written through a chosen
 * {@link SqlOutputWriterBinding} into a composite attribute of a given PostgreSQL type. The writer and the
 * attribute type are independent, so the matrix explores off-diagonal writes (for example
 * {@code writeInt} into a {@code text} attribute), not just matching pairs.
 */
public final class CoercionWriteCase {

  /** The composite attribute type the value is written into. */
  final ScalarDescriptor attr;
  final SqlOutputWriterBinding writer;
  /** A value of the writer's input class, or {@code null} for a {@code NOT_IMPLEMENTED} writer. */
  final Object value;

  public CoercionWriteCase(ScalarDescriptor attr, SqlOutputWriterBinding writer, Object value) {
    this.attr = attr;
    this.writer = writer;
    this.value = value;
  }

  @Override
  public String toString() {
    return "CoercionWriteCase{oid=" + attr.oid() + ", writer=" + writer.label() + ", value=" + value
        + '}';
  }
}
