/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.PrefersJavaTime;
import org.postgresql.fuzzkit.coercion.ScalarDescriptor;

import org.checkerframework.checker.nullness.qual.Nullable;

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
  /** The per-type {@code getObject} java.time preferences of the context. */
  final PrefersJavaTime prefersJavaTime;
  /** The attribute modifier stamped on the field {@code f}, or {@code -1} for none. */
  final int appliedTypmod;

  public CoercionCase(ScalarDescriptor kind, Object value, SqlInputReader reader,
      @Nullable Class<?> targetClass, PrefersJavaTime prefersJavaTime) {
    this(kind, value, reader, targetClass, prefersJavaTime, -1);
  }

  /**
   * A case whose field {@code f} carries the given applied modifier ({@code atttypmod}), so a
   * modifier-sensitive type such as {@code numeric(10,2)} decodes to its declared scale through the
   * reader. Pass {@code -1} for no modifier.
   *
   * @param kind the field type descriptor
   * @param value a value of the field type on the canonical wire
   * @param reader the SQLInput reader under test
   * @param targetClass the {@code readObject(Class)} target, or {@code null} for any other reader
   * @param prefersJavaTime the java.time preferences of the context
   * @param appliedTypmod the field's applied modifier, or {@code -1} for none
   */
  public CoercionCase(ScalarDescriptor kind, Object value, SqlInputReader reader,
      @Nullable Class<?> targetClass, PrefersJavaTime prefersJavaTime, int appliedTypmod) {
    this.kind = kind;
    this.value = value;
    this.reader = reader;
    this.targetClass = targetClass;
    this.prefersJavaTime = prefersJavaTime;
    this.appliedTypmod = appliedTypmod;
  }

  /**
   * Builds a case whose {@code prefersJavaTime} flags come from the five low bits of a single byte:
   * {@code 0x01} date, {@code 0x02} time, {@code 0x04} timetz, {@code 0x08} timestamp, {@code 0x10}
   * timestamptz. It lets a generated {@code @FuzzTest} draw the whole config axis as one
   * {@code FuzzedDataProvider} byte and hand it straight here, keeping the generated body to a single
   * line; pass {@code 0} for the all-false config.
   *
   * @param kind the field type descriptor
   * @param value a value of the field type on the canonical wire
   * @param reader the SQLInput reader under test
   * @param targetClass the {@code readObject(Class)} target, or {@code null} for any other reader
   * @param prefersJavaTime the packed {@code prefersJavaTime} flags (five low bits)
   */
  public CoercionCase(ScalarDescriptor kind, Object value, SqlInputReader reader,
      @Nullable Class<?> targetClass, byte prefersJavaTime) {
    this(kind, value, reader, targetClass, prefersJavaTime, -1);
  }

  /**
   * The packed-{@code prefersJavaTime} constructor with an applied field modifier ({@code atttypmod}),
   * so a generated {@code @FuzzTest} can draw the whole config axis as one byte and stamp a modifier
   * on the field. Pass {@code -1} for no modifier.
   *
   * @param kind the field type descriptor
   * @param value a value of the field type on the canonical wire
   * @param reader the SQLInput reader under test
   * @param targetClass the {@code readObject(Class)} target, or {@code null} for any other reader
   * @param prefersJavaTime the packed {@code prefersJavaTime} flags (five low bits)
   * @param appliedTypmod the field's applied modifier, or {@code -1} for none
   */
  public CoercionCase(ScalarDescriptor kind, Object value, SqlInputReader reader,
      @Nullable Class<?> targetClass, byte prefersJavaTime, int appliedTypmod) {
    this(kind, value, reader, targetClass, PrefersJavaTime.builder()
        .date((prefersJavaTime & 0x01) != 0)
        .time((prefersJavaTime & 0x02) != 0)
        .timetz((prefersJavaTime & 0x04) != 0)
        .timestamp((prefersJavaTime & 0x08) != 0)
        .timestamptz((prefersJavaTime & 0x10) != 0)
        .build(), appliedTypmod);
  }

  @Override
  public String toString() {
    return "CoercionCase{oid=" + kind.oid() + ", value=" + value + ", reader="
        + reader + ", targetClass=" + (targetClass == null ? "-" : targetClass.getSimpleName())
        + ", prefersJavaTime=" + prefersJavaTime
        + (appliedTypmod == -1 ? "" : ", typmod=" + appliedTypmod) + '}';
  }
}
