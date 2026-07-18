/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.fuzzkit.coercion.ScalarDescriptor;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One cell of the SQLData round-trip matrix: a value written into a composite attribute through a
 * {@link SqlOutputWriterBinding} and read back through a {@link SqlInputReader}. The write may be off-diagonal
 * (any writer into any attribute, for example {@code writeString} into a {@code time} attribute), so it
 * can refuse; when it produces wire, the read leg runs. An identity pair -- the type's own writer and
 * reader -- additionally checks that the written value survives the round-trip.
 */
public final class CoercionRoundTripCase {

  /** The composite attribute type the value round-trips through. */
  final ScalarDescriptor attr;
  final SqlOutputWriterBinding writer;
  final Object writeValue;
  final SqlInputReader reader;
  /** The {@code readObject(Class)} target, or {@code null} when the read uses the typed reader. */
  final @Nullable Class<?> targetClass;

  public CoercionRoundTripCase(ScalarDescriptor attr, SqlOutputWriterBinding writer, Object writeValue,
      SqlInputReader reader, @Nullable Class<?> targetClass) {
    this.attr = attr;
    this.writer = writer;
    this.writeValue = writeValue;
    this.reader = reader;
    this.targetClass = targetClass;
  }

  @Override
  public String toString() {
    String readAs = targetClass != null
        ? "readObject(" + targetClass.getSimpleName() + ")"
        : reader.label();
    return "CoercionRoundTripCase{oid=" + attr.oid() + ", writer=" + writer.label() + ", read="
        + readAs + ", value=" + writeValue + '}';
  }
}
