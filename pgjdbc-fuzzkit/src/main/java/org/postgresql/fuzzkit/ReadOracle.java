/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.Format;
import org.postgresql.fuzzkit.coercion.CoercionOutcome;
import org.postgresql.fuzzkit.coercion.OutcomeContract;
import org.postgresql.fuzzkit.coercion.OutcomeContract.Direction;
import org.postgresql.fuzzkit.coercion.ReadCoercions;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.sql.SQLInput;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The shared read-side oracle for the SQLData fuzzers. It owns the read axes -- the
 * {@code readObject(Class)} target classes and the {@code prefersJavaTime} connection parameters -- and
 * the {@code SQLInput}-read outcome check against {@code ReadCoercions}, so {@link CoercionFuzzSupport}
 * and {@link CoercionRoundTripSupport} declare them once and reach the same verdicts.
 *
 * <p>The outcome is: {@code OK} must return, {@code OK_OR_COERCE} must return or refuse with a
 * value-level {@code SQLState}, each refusal outcome must throw its exact {@code SQLState}, and an
 * unspecified cell ({@code null}) keeps the weak invariant -- return or a clean {@link SQLException},
 * never an unchecked leak.
 */
public final class ReadOracle {

  /**
   * The classes no read-populated type lists as a {@code readObject(Class)} target -- negative controls
   * that exercise the registry's default-deny. Every populated type must refuse them with a clean
   * {@code DATA_TYPE_MISMATCH}, so they belong on the axis even though no type produces them.
   */
  private static final Class<?>[] OUT_OF_DICTIONARY_CONTROLS = {Pattern.class, Thread.class};

  /**
   * Candidate target classes for {@code readObject(Class)} -- the target-class axis. Derived: the union
   * of every read-populated type's {@code readObject(Class)} targets
   * ({@link ReadCoercions#allObjectTargets}) plus the {@link #OUT_OF_DICTIONARY_CONTROLS}. The union is
   * ordered by class name so the axis index is
   * stable across runs. This is wider than the hand-written list it replaced -- it picks up every class a
   * type produces, including {@code Byte}, {@code BigInteger}, {@code java.util.Date}, {@code InputStream},
   * {@code PGobject}, {@code ZonedDateTime}, {@code Instant}, {@code PGInterval} and the geometric types.
   */
  public static final Class<?>[] TARGET_CLASSES = deriveTargetClasses();

  private static Class<?>[] deriveTargetClasses() {
    // Sort the dictionary union by class name so the axis index is stable across runs, then append the
    // controls in order. A LinkedHashSet keeps that order and drops any control a type happens to
    // produce (none today), so the axis has no duplicates.
    Set<Class<?>> ordered = new TreeSet<>(Comparator.comparing(Class::getName));
    ordered.addAll(ReadCoercions.allObjectTargets());
    Set<Class<?>> withControls = new LinkedHashSet<>(ordered);
    Collections.addAll(withControls, OUT_OF_DICTIONARY_CONTROLS);
    return withControls.toArray(new Class<?>[0]);
  }

  /** The {@code prefersJavaTime} connection parameters, in {@code CoercionCase} flag order. */
  private static final String[] PREFERS_JAVA_TIME = {
      "prefersJavaTimeForDate", "prefersJavaTimeForTime", "prefersJavaTimeForTimetz",
      "prefersJavaTimeForTimestamp", "prefersJavaTimeForTimestamptz"};

  private ReadOracle() {
  }

  /** The connection config for a set of {@code prefersJavaTime} flags (all five, in flag order). */
  static Map<String, String> configFor(boolean[] prefersJavaTime) {
    Map<String, String> config = new HashMap<>();
    for (int i = 0; i < PREFERS_JAVA_TIME.length; i++) {
      if (prefersJavaTime[i]) {
        config.put(PREFERS_JAVA_TIME[i], "true");
      }
    }
    return config;
  }

  /**
   * The outcome the registry predicts for a reader over a type: {@code readObject(Class)} uses the
   * class axis ({@code readObjectAs}), every other reader its {@code Accessor}.
   */
  static @Nullable CoercionOutcome expected(int oid, SqlInputReader reader, Class<?> target,
      Map<String, String> config) {
    ReadCoercions.Accessor accessor = reader.accessor();
    return accessor == null
        ? ReadCoercions.readObjectAs(ReadCoercions.Surface.SQL_INPUT, oid, target, config)
        : ReadCoercions.read(ReadCoercions.Surface.SQL_INPUT, oid, accessor, config);
  }

  /**
   * The Java class {@code readObject(Object.class)} returns for a type under the default config -- the
   * type's own natural class. Used by the round-trip to tell whether {@code readObject(Object)} round-
   * trips (it does when the default matches the written value's class, but not for {@code timetz}/
   * {@code timestamptz}, whose default is {@code java.sql.Time}/{@code Timestamp} rather than an
   * {@code Offset} type).
   */
  static @Nullable Class<?> defaultObjectClass(int oid) {
    return ReadCoercions.defaultObjectClass(oid);
  }

  /**
   * Invokes the reader over the input, asserts the outcome against {@code expected}, and returns the
   * value read -- or a non-returned result for a refusal or a tolerated {@code valueOf} deviation.
   * Throws an {@link AssertionError} on any unchecked leak the registry does not model.
   */
  static ReadResult verify(SQLInput in, SqlInputReader reader, Class<?> target,
      @Nullable CoercionOutcome expected, Format format, Object caseLabel) {
    try {
      @Nullable Object value = reader.read(in, target);
      requireReturnAllowed(reader, target, expected, format, caseLabel);
      return ReadResult.returned(value);
    } catch (SQLException refused) {
      requireRefusalMatches(reader, target, expected, refused, format, caseLabel);
      return ReadResult.NOT_RETURNED;
    } catch (RuntimeException leak) {
      throw new AssertionError(describe(reader, target) + " leaked " + leak.getClass().getName()
          + " (expected only SQLException) on " + format + " " + caseLabel, leak);
    }
  }

  /** A human-readable name for the reader, spelling out the target class of {@code readObject(Class)}. */
  static String describe(SqlInputReader reader, Class<?> target) {
    return reader.accessor() == null ? "readObject(" + target.getSimpleName() + ")" : reader.label();
  }

  private static void requireReturnAllowed(SqlInputReader reader, Class<?> target,
      @Nullable CoercionOutcome expected, Format format, Object caseLabel) {
    if (OutcomeContract.allowsReturn(expected)) {
      return;
    }
    throw new AssertionError(describe(reader, target) + " returned a value but the registry expects "
        + expected + " on " + format + " " + caseLabel);
  }

  private static void requireRefusalMatches(SqlInputReader reader, Class<?> target,
      @Nullable CoercionOutcome expected, SQLException refused, Format format, Object caseLabel) {
    String state = refused.getSQLState();
    if (!OutcomeContract.matchesRefusal(expected, state, Direction.READ)) {
      throw new AssertionError(describe(reader, target) + " refused with SQLState " + state
          + " but the registry expects " + expected + " on " + format + " " + caseLabel, refused);
    }
  }

  /** The result of a read: the value returned, or a marker that the read refused or deviated. */
  static final class ReadResult {
    static final ReadResult NOT_RETURNED = new ReadResult(false, null);

    private final boolean returned;
    private final @Nullable Object value;

    private ReadResult(boolean returned, @Nullable Object value) {
      this.returned = returned;
      this.value = value;
    }

    static ReadResult returned(@Nullable Object value) {
      return new ReadResult(true, value);
    }

    boolean returned() {
      return returned;
    }

    @Nullable Object value() {
      return value;
    }
  }
}
