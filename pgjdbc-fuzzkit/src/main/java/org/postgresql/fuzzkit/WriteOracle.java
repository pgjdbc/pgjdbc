/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.fuzzkit.coercion.CoercionOutcome;
import org.postgresql.fuzzkit.coercion.OutcomeContract;
import org.postgresql.fuzzkit.coercion.OutcomeContract.Direction;
import org.postgresql.fuzzkit.coercion.WriteCoercions;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;

/**
 * The shared write-side oracle for the SQLData fuzzers. It owns the {@code SQLOutput}-encode outcome
 * check against {@code WriteCoercions}, so {@link CoercionWriteSupport} and
 * {@link CoercionRoundTripSupport} reach the same verdicts.
 *
 * <p>The outcome is: {@code OK} must encode, {@code OK_OR_COERCE} must encode or refuse with a
 * value-level {@code SQLState}, {@code INVALID_PARAMETER_TYPE} / {@code CANNOT_COERCE} must refuse with
 * that state, a {@code NOT_IMPLEMENTED} writer must throw {@code NOT_IMPLEMENTED}, and an unspecified
 * cell ({@code null}) keeps the weak invariant -- encode or a clean {@link SQLException}, never an
 * unchecked leak. {@link #verify} returns the encoded wire, or {@code null} when the writer refused
 * (so there is nothing to read back).
 */
public final class WriteOracle {

  private WriteOracle() {
  }

  /** The outcome the registry predicts for a writer encoding its value into a type. */
  static @Nullable CoercionOutcome expected(int oid, SqlOutputWriterBinding writer, Object value) {
    WriteCoercions.Method method = writer.method();
    return method.notImplemented()
        ? CoercionOutcome.NOT_IMPLEMENTED
        : WriteCoercions.encode(WriteCoercions.Surface.SQL_OUTPUT, oid, method.presentedClass(value));
  }

  /**
   * Encodes the value through the writer, asserts the outcome against {@code expected}, and returns
   * the wire -- or {@code null} when the writer refused (the outcome is checked either way). Throws an
   * {@link AssertionError} on any unchecked leak the registry does not model.
   */
  static @Nullable RawValue verify(PgType comp, PgCodecContext ctx, Format format,
      SqlOutputWriterBinding writer, Object value, @Nullable CoercionOutcome expected,
      Object caseLabel) {
    try {
      RawValue wire = Codecs.encode(new WriteProbe(writer, value), comp, ctx, format);
      requireEncodeAllowed(writer, expected, format, caseLabel);
      return wire;
    } catch (SQLException refused) {
      requireRefusalMatches(writer, expected, refused, format, caseLabel);
      return null;
    } catch (RuntimeException leak) {
      throw new AssertionError("writer " + writer.label() + " leaked " + leak.getClass().getName()
          + " (expected only SQLException) on " + format + " " + caseLabel, leak);
    }
  }

  private static void requireEncodeAllowed(SqlOutputWriterBinding writer,
      @Nullable CoercionOutcome expected, Format format, Object caseLabel) {
    if (OutcomeContract.allowsReturn(expected)) {
      return;
    }
    throw new AssertionError("writer " + writer.label() + " encoded a value but the registry expects "
        + expected + " on " + format + " " + caseLabel);
  }

  private static void requireRefusalMatches(SqlOutputWriterBinding writer,
      @Nullable CoercionOutcome expected, SQLException refused, Format format, Object caseLabel) {
    String state = refused.getSQLState();
    if (!OutcomeContract.matchesRefusal(expected, state, Direction.WRITE)) {
      throw new AssertionError("writer " + writer.label() + " refused with SQLState " + state
          + " but the registry expects " + expected + " on " + format + " " + caseLabel, refused);
    }
  }

  /** A single-field {@link SQLData} that writes its one value through the chosen writer. */
  static final class WriteProbe implements SQLData {
    private final SqlOutputWriterBinding writer;
    private final Object value;

    WriteProbe(SqlOutputWriterBinding writer, Object value) {
      this.writer = writer;
      this.value = value;
    }

    @Override
    public String getSQLTypeName() {
      return "public.ct";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) {
      // Write-only probe.
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      writer.write(stream, value);
    }
  }
}
