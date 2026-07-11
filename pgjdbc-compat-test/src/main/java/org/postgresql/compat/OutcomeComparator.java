/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Turns a returned JDBC value into a class-loader-agnostic string and compares two {@link
 * ObservableOutcome}s for backward-compatibility.
 *
 * <p>Normalization must not rely on {@code equals} between the two drivers: their {@code
 * org.postgresql.*} classes come from different class loaders, so equal values are not {@code equals}. A
 * string form sidesteps that and, by keeping the runtime class name, also catches a changed return type
 * (for example {@code getObject} returning {@code Long} where the baseline returned {@code Integer}).
 */
public final class OutcomeComparator {
  private OutcomeComparator() {
  }

  /**
   * Renders a value returned by a getter into a stable, comparable string. {@code null} maps to {@code
   * null} (the caller tracks SQL NULL through {@code wasNull} separately).
   */
  public static @Nullable String normalize(@Nullable Object v) throws SQLException {
    if (v == null) {
      return null;
    }
    if (v instanceof byte[]) {
      return "bytes:0x" + hex((byte[]) v);
    }
    if (v instanceof Array) {
      Array a = (Array) v;
      Object elements = a.getArray();
      String rendered = elements instanceof Object[]
          ? Arrays.deepToString((Object[]) elements)
          : String.valueOf(elements);
      return "array(" + a.getBaseTypeName() + "):" + rendered;
    }
    if (v instanceof BigDecimal) {
      // Keep the exact scale: a changed getBigDecimal scale is itself an observable difference.
      return "BigDecimal:" + ((BigDecimal) v).toPlainString();
    }
    // Class name plus value: catches both a changed value and a changed returned type.
    return v.getClass().getName() + ":" + v;
  }

  /**
   * Compares the current driver's outcome against the baseline's.
   *
   * @return a human-readable description of the first mismatch, or {@code null} when the two are
   *     backward-compatible. Two throws are compatible when they land in the same failure group (see
   *     {@link #failureGroup}); the exact SQLState and message are deliberately ignored.
   */
  public static @Nullable String compare(ObservableOutcome current, ObservableOutcome baseline) {
    if (current.threw() != baseline.threw()) {
      return "current " + current + " vs baseline " + baseline;
    }
    if (current.threw()) {
      // Both refused. Compare the failure group, not the exact SQLState: the codec path reports
      // coercion refusals under a different state than the baseline, but the same group, so that churn
      // is compatible; a shift that crosses groups (a coercion refusal becoming a connection error, or
      // degrading into an unchecked RuntimeException) stays a real regression.
      if (!failureGroup(current).equals(failureGroup(baseline))) {
        return "current " + current + " vs baseline " + baseline;
      }
      return null;
    }
    if (current.wasNull() != baseline.wasNull()) {
      return "current wasNull=" + current.wasNull() + " vs baseline wasNull=" + baseline.wasNull();
    }
    if (!Objects.equals(current.value(), baseline.value())) {
      return "current [" + current.value() + "] vs baseline [" + baseline.value() + "]";
    }
    return null;
  }

  /**
   * The comparison bucket for a thrown outcome: for a {@link java.sql.SQLException}, its {@link
   * SqlStateGroup}; otherwise the exception family, so an unchecked failure never merges with a checked
   * one.
   */
  private static String failureGroup(ObservableOutcome outcome) {
    if (ObservableOutcome.FAMILY_SQL.equals(outcome.exceptionFamily())) {
      return SqlStateGroup.of(outcome.sqlState());
    }
    String family = outcome.exceptionFamily();
    return family == null ? "unknown" : family;
  }

  private static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xf, 16));
      sb.append(Character.forDigit(b & 0xf, 16));
    }
    return sb.toString();
  }
}
