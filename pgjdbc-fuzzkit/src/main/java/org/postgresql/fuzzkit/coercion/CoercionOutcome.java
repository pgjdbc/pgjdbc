/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

/**
 * The contract-level result a JDBC coercion is expected to produce, independent of the value.
 *
 * <p>The registry states the <em>intended</em> contract (JDBC spec plus PostgreSQL semantics), not
 * whatever a given codec happens to do today. An oracle turns each outcome into an assertion:
 * {@link #OK} must return, a refusal must throw with the matching {@code SQLState}, and
 * {@link #OK_OR_COERCE} accepts either because the split depends on the concrete value.
 *
 * <p>Refusals are not one {@code SQLState}. The three refusal outcomes below map to the three states
 * the driver actually raises, so an oracle can assert the exact state rather than "some
 * {@code SQLException}".
 */
public enum CoercionOutcome {

  /** The coercion is legal for every value of the type and must return a (possibly null) value. */
  OK,

  /**
   * The coercion is legal to attempt, but succeeds or fails per value: a well-formed value returns,
   * an out-of-range or unparsable one refuses with {@code CANNOT_COERCE}. Reading {@code int} text as
   * {@code readInt} is the canonical case. This is genuine value dependence, not a known bug; known
   * bugs stay in a separate deviation list.
   */
  OK_OR_COERCE,

  /**
   * A boolean coercion failed. In the codec path only {@code readBoolean} produces this: it runs
   * through {@code BooleanCoercion}/{@code BooleanTypeUtil}, which throw {@code SQLState} {@code 42846}
   * ({@code PSQLState.CANNOT_COERCE}) when the value is not a recognised boolean. Every other decode
   * failure is {@link #DATA_TYPE_MISMATCH}.
   */
  CANNOT_COERCE,

  /**
   * The general decode-failure state: the reader cannot convert this type. {@code Codec.cannotDecode}
   * -- the shared path behind every unsupported numeric reader, {@code readObject(Class)} target and
   * {@code byte[]} read -- throws {@code SQLState} {@code 42821} ({@code PSQLState.DATA_TYPE_MISMATCH}).
   * It is the default refusal for a populated type; only {@code readBoolean} deviates to
   * {@link #CANNOT_COERCE}.
   */
  DATA_TYPE_MISMATCH,

  /**
   * A {@code getObject(Class)} request for a JDBC-managed target ({@code Blob}, {@code Clob},
   * {@code SQLXML}, {@code Calendar}, {@code InetAddress}) whose column type does not match. The
   * driver must throw with {@code SQLState} {@code PSQLState.INVALID_PARAMETER_VALUE}.
   */
  INVALID_PARAMETER_VALUE,

  /**
   * The general encode-failure state (write direction): the codec cannot turn this Java class into
   * the target PostgreSQL type. {@code Codec.cannotEncode} -- the shared path behind every rejected
   * write source class -- throws {@code SQLState} {@code 07006} ({@code PSQLState.INVALID_PARAMETER_TYPE}).
   * It is the write-side default refusal, the mirror of {@link #DATA_TYPE_MISMATCH} on the read side;
   * {@code bool} deviates to {@link #CANNOT_COERCE} (it runs through {@code BooleanTypeUtil}).
   */
  INVALID_PARAMETER_TYPE,

  /**
   * The accessor is not implemented for any type ({@code readRowId}, {@code readNClob}). The driver
   * must throw with {@code SQLState} {@code PSQLState.NOT_IMPLEMENTED}.
   */
  NOT_IMPLEMENTED
}
