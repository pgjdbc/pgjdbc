/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The model that turns a {@link CoercionOutcome} into an assertion: whether a coercion may return
 * (or encode) a value, and which {@code SQLState} a refusal must carry. It is the single source of
 * truth an oracle consumes; the read and write oracles differ only in the value-level states an
 * {@link CoercionOutcome#OK_OR_COERCE} cell may raise, so this contract is parameterised by
 * {@link Direction}.
 *
 * <p>The rules are the same on both directions: {@link CoercionOutcome#OK} and
 * {@link CoercionOutcome#OK_OR_COERCE} may return; each exact refusal outcome maps to its own
 * {@code SQLState}; a {@code null} cell keeps the weak invariant, so any clean refusal is accepted.
 * Only the {@code OK_OR_COERCE} value-level set differs -- the write direction adds
 * {@link PSQLState#DATETIME_OVERFLOW} and {@link PSQLState#INVALID_PARAMETER_TYPE}, because a
 * write-time overflow and an accepted class that fails to convert both surface there.
 */
public final class OutcomeContract {

  /** The coercion direction, which selects the {@link CoercionOutcome#OK_OR_COERCE} value-level set. */
  public enum Direction {
    /** Reading a value out of {@code SQLInput} / {@code ResultSet} / {@code CallableStatement}. */
    READ,
    /** Encoding a value into {@code SQLOutput} / {@code PreparedStatement} / an updatable row. */
    WRITE
  }

  /**
   * Clean value-level refusal states a read {@link CoercionOutcome#OK_OR_COERCE} cell may raise:
   * an out-of-range or unparsable value refuses per value rather than for the whole type.
   */
  private static final Set<String> READ_VALUE_LEVEL_STATES = states(
      PSQLState.CANNOT_COERCE,
      PSQLState.NUMERIC_VALUE_OUT_OF_RANGE,
      PSQLState.BAD_DATETIME_FORMAT);

  /**
   * Clean value-level refusal states a write {@link CoercionOutcome#OK_OR_COERCE} cell may raise.
   * It extends the read set with {@link PSQLState#DATETIME_OVERFLOW} (a write-time overflow),
   * {@link PSQLState#INVALID_PARAMETER_TYPE} (a value of an accepted class that fails to convert,
   * such as {@code decodeDateText} on an unparsable {@code date} string), and the two states a
   * malformed {@code bytea} literal raises when a {@code String} is encoded as {@code bytea}:
   * {@link PSQLState#INVALID_TEXT_REPRESENTATION} for a bad escape/octal literal and
   * {@link PSQLState#INVALID_PARAMETER_VALUE} for a bad hex literal.
   */
  private static final Set<String> WRITE_VALUE_LEVEL_STATES = states(
      PSQLState.NUMERIC_VALUE_OUT_OF_RANGE,
      PSQLState.BAD_DATETIME_FORMAT,
      PSQLState.DATETIME_OVERFLOW,
      PSQLState.CANNOT_COERCE,
      PSQLState.INVALID_PARAMETER_TYPE,
      PSQLState.INVALID_TEXT_REPRESENTATION,
      PSQLState.INVALID_PARAMETER_VALUE);

  private OutcomeContract() {
  }

  /**
   * Whether the coercion is allowed to return (read) or encode (write) a value rather than refuse.
   * True for an unspecified cell ({@code null}), {@link CoercionOutcome#OK}, and
   * {@link CoercionOutcome#OK_OR_COERCE}; false for every exact refusal outcome.
   */
  public static boolean allowsReturn(@Nullable CoercionOutcome expected) {
    return expected == null
        || expected == CoercionOutcome.OK
        || expected == CoercionOutcome.OK_OR_COERCE;
  }

  /**
   * Whether a refusal's {@code SQLState} matches the expected outcome in the given direction. A
   * {@code null} cell accepts any clean refusal (the weak invariant); {@link CoercionOutcome#OK}
   * never matches a refusal; {@link CoercionOutcome#OK_OR_COERCE} accepts the direction's value-level
   * set; every exact refusal outcome matches its own {@code SQLState}.
   *
   * @param expected the outcome the registry predicts, or {@code null} for an unspecified cell
   * @param sqlState the {@code SQLState} the refusal carried, or {@code null} if it had none
   * @param dir the coercion direction, selecting the {@code OK_OR_COERCE} value-level set
   */
  public static boolean matchesRefusal(@Nullable CoercionOutcome expected,
      @Nullable String sqlState, Direction dir) {
    if (expected == null) {
      // An unspecified cell keeps the weak invariant: any clean SQLException is acceptable.
      return true;
    }
    switch (expected) {
      case OK:
        return false;
      case OK_OR_COERCE:
        return sqlState != null && valueLevelStates(dir).contains(sqlState);
      case CANNOT_COERCE:
        return PSQLState.CANNOT_COERCE.getState().equals(sqlState);
      case DATA_TYPE_MISMATCH:
        return PSQLState.DATA_TYPE_MISMATCH.getState().equals(sqlState);
      case INVALID_PARAMETER_VALUE:
        return PSQLState.INVALID_PARAMETER_VALUE.getState().equals(sqlState);
      case INVALID_PARAMETER_TYPE:
        return PSQLState.INVALID_PARAMETER_TYPE.getState().equals(sqlState);
      case NOT_IMPLEMENTED:
        return PSQLState.NOT_IMPLEMENTED.getState().equals(sqlState);
      default:
        return false;
    }
  }

  private static Set<String> valueLevelStates(Direction dir) {
    return dir == Direction.WRITE ? WRITE_VALUE_LEVEL_STATES : READ_VALUE_LEVEL_STATES;
  }

  private static Set<String> states(PSQLState... states) {
    Set<String> set = new HashSet<>();
    for (PSQLState state : states) {
      set.add(state.getState());
    }
    return Collections.unmodifiableSet(set);
  }
}
