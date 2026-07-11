/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * The observable result of driving one JDBC accessor or binder on one driver: either a value (with the
 * {@code wasNull} flag and a class-loader-agnostic string form) or a thrown exception (with its family and
 * SQLState). Two outcomes are the unit the differential oracle compares; see {@link OutcomeComparator}.
 *
 * <p>The value is kept as a normalized string rather than the original object because the current and the
 * baseline driver return their own {@code org.postgresql.*} classes from distinct class loaders, which
 * cannot be compared with {@code equals}. {@link OutcomeComparator#normalize} produces that string.
 */
public final class ObservableOutcome {
  /** Exception family for a thrown {@link SQLException}. */
  public static final String FAMILY_SQL = "SQLException";
  /** Exception family for a thrown unchecked exception. */
  public static final String FAMILY_RUNTIME = "RuntimeException";
  /** Exception family for anything else thrown (should not normally happen on the JDBC surface). */
  public static final String FAMILY_OTHER = "Throwable";

  private final boolean threw;
  private final @Nullable String exceptionFamily;
  private final @Nullable String sqlState;
  private final boolean wasNull;
  private final @Nullable String value;

  private ObservableOutcome(boolean threw, @Nullable String exceptionFamily,
      @Nullable String sqlState, boolean wasNull, @Nullable String value) {
    this.threw = threw;
    this.exceptionFamily = exceptionFamily;
    this.sqlState = sqlState;
    this.wasNull = wasNull;
    this.value = value;
  }

  /** A successful read: the {@code wasNull} flag and the normalized value ({@code null} when SQL NULL). */
  public static ObservableOutcome value(boolean wasNull, @Nullable String value) {
    return new ObservableOutcome(false, null, null, wasNull, value);
  }

  /** A thrown exception, classified into a family plus its SQLState when it is a {@link SQLException}. */
  public static ObservableOutcome thrown(Throwable t) {
    if (t instanceof SQLException) {
      return new ObservableOutcome(true, FAMILY_SQL, ((SQLException) t).getSQLState(), false, null);
    }
    String family = t instanceof RuntimeException ? FAMILY_RUNTIME : FAMILY_OTHER;
    return new ObservableOutcome(true, family, null, false, null);
  }

  public boolean threw() {
    return threw;
  }

  public @Nullable String exceptionFamily() {
    return exceptionFamily;
  }

  public @Nullable String sqlState() {
    return sqlState;
  }

  public boolean wasNull() {
    return wasNull;
  }

  public @Nullable String value() {
    return value;
  }

  @Override
  public String toString() {
    if (threw) {
      return "threw " + exceptionFamily + (sqlState == null ? "" : "(" + sqlState + ")");
    }
    return wasNull ? "NULL" : String.valueOf(value);
  }
}
