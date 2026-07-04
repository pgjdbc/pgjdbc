/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.fuzzkit.coercion.ScalarDescriptor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;

/**
 * One cell of the SQLData read matrix: a field value of a given PostgreSQL type on the canonical wire
 * (the field's own codec), the {@link java.sql.SQLInput} reader that pulls it back, and the
 * {@code prefersJavaTime} flags of the context. {@code PgValueArgumentsFactory} generates every
 * dimension so the fuzzer explores reader/value/config combinations.
 *
 * <p>The wire is always canonical: the driver write paths (typed {@code PgSQLOutput}, generic
 * {@code writeObject}) present field bytes identical to the canonical codec on the diagonal, so they
 * add no unique read coverage; off-diagonal write&rarr;read stays in the round-trip fuzzer. The
 * byte-equivalence of typed writes to the canonical wire is pinned by
 * {@code TypedWriteMatchesCanonicalWireTest}.
 */
public final class CoercionCase {

  final ScalarDescriptor kind;
  final Object value;
  final SqlInputReader reader;
  /** The {@code readObject(Class)} target, or {@code null} for any other reader. */
  final @Nullable Class<?> targetClass;
  /** {@code [date, time, timetz, timestamp, timestamptz]}. */
  final boolean[] prefersJavaTime;

  public CoercionCase(ScalarDescriptor kind, Object value, SqlInputReader reader,
      @Nullable Class<?> targetClass, boolean[] prefersJavaTime) {
    this.kind = kind;
    this.value = value;
    this.reader = reader;
    this.targetClass = targetClass;
    this.prefersJavaTime = prefersJavaTime;
  }

  @Override
  public String toString() {
    return "CoercionCase{oid=" + kind.oid() + ", value=" + value + ", reader="
        + reader + ", targetClass=" + (targetClass == null ? "-" : targetClass.getSimpleName())
        + ", prefersJavaTime=" + Arrays.toString(prefersJavaTime) + '}';
  }
}
