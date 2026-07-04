/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.JDBCType;
import java.util.function.Predicate;

/**
 * A scalar {@link PgTypeDescriptor}: a leaf backend type with no element or field structure. It serves
 * both the coercion scalars ({@code int4}, {@code numeric}, {@code timetz}, ...) and the codec-only
 * scalars ({@code int2}, {@code float4}, {@code float8}, {@code bytea}) that the codec round-trip
 * fuzzers exercise but the coercion dictionaries do not write-populate.
 *
 * <p>The scalar <b>stores</b>, beyond the {@code oid} on the base:
 *
 * <ul>
 *   <li>{@code pgTypeName} / {@code typcategory} -- the offline {@link PgType} identity ({@link #pgType()}
 *       builds it), previously the hand-written {@code scalar(...)} constants in the codec fuzz support.</li>
 *   <li>{@code jdbcType} -- the {@link JDBCType} the generic {@code writeObject(Object, SQLType)} /
 *       {@code setObject} paths use.</li>
 *   <li>{@code naturalClass} -- the Java class of an identity round-trip. It is not derivable: for
 *       {@code timetz}/{@code timestamptz} it is {@code OffsetTime}/{@code OffsetDateTime}, which differ
 *       from {@link #defaultObjectClass()} (java.sql.Time / java.sql.Timestamp); for {@code int2} it is
 *       {@code Short} (its typed {@code WRITE_SHORT}/{@code READ_SHORT} identity), while its default
 *       {@code getObject} class stays {@code Integer} (pgjdbc's documented smallint backward-compat),
 *       delegated to the dictionary.</li>
 *   <li>{@code typedWriter} / {@code typedReader} -- the diagonal write method and read accessor, or
 *       {@code null} for a type reached only through the object axis ({@code timetz}/{@code timestamptz}
 *       have no typed {@code Offset} writer or reader).</li>
 *   <li>{@code fidelity} -- how a written value is compared with the value read back.</li>
 *   <li>{@code poison} -- a predicate marking values that encode legally but need not read back (a
 *       non-finite {@code Float}/{@code Double} into {@code numeric}); every other type poisons
 *       nothing. This generalises the {@code nonFiniteNumeric} special case the round-trip support
 *       hard-codes.</li>
 * </ul>
 */
public final class ScalarDescriptor extends PgTypeDescriptor {

  /** A type that poisons nothing: every value that encodes may be read back. */
  static final Predicate<@Nullable Object> NO_POISON = value -> false;

  /** {@code numeric}'s poison: a non-finite {@code Float}/{@code Double} has no {@code BigDecimal} form. */
  static final Predicate<@Nullable Object> NON_FINITE_NUMERIC = ScalarDescriptor::isNonFiniteNumeric;

  private final String pgTypeName;
  private final char typcategory;
  private final JDBCType jdbcType;
  private final Class<?> naturalClass;
  private final WriteCoercions.@Nullable Method typedWriter;
  private final ReadCoercions.@Nullable Accessor typedReader;
  private final Fidelity fidelity;
  private final Predicate<@Nullable Object> poison;

  ScalarDescriptor(int oid, String pgTypeName, char typcategory, JDBCType jdbcType,
      Class<?> naturalClass, WriteCoercions.@Nullable Method typedWriter,
      ReadCoercions.@Nullable Accessor typedReader, Fidelity fidelity,
      Predicate<@Nullable Object> poison) {
    super(oid);
    this.pgTypeName = pgTypeName;
    this.typcategory = typcategory;
    this.jdbcType = jdbcType;
    this.naturalClass = naturalClass;
    this.typedWriter = typedWriter;
    this.typedReader = typedReader;
    this.fidelity = fidelity;
    this.poison = poison;
  }

  /**
   * The offline scalar {@link PgType} the codec context resolves, built the way the codec unit tests
   * build theirs: a base type ({@code typtype='b'}) in {@code pg_catalog} with this scalar's name and
   * {@code typcategory} and no element, array, or base type.
   */
  @Override
  public PgType pgType() {
    return new PgType(new ObjectName("pg_catalog", pgTypeName), pgTypeName, oid(), 'b', typcategory,
        -1, 0, 0, 0);
  }

  /** The {@link JDBCType} the generic {@code writeObject(Object, SQLType)} / {@code setObject} paths use. */
  public JDBCType jdbcType() {
    return jdbcType;
  }

  /** The Java class an identity round-trip reads back; not always the codec's default (see class doc). */
  public Class<?> naturalClass() {
    return naturalClass;
  }

  /** The diagonal write method, or {@code null} for a type reached only through the object axis. */
  public WriteCoercions.@Nullable Method typedWriter() {
    return typedWriter;
  }

  /** The diagonal read accessor, or {@code null} for a type reached only through the object axis. */
  public ReadCoercions.@Nullable Accessor typedReader() {
    return typedReader;
  }

  /** How a written value is compared with the value read back on this type's identity round-trip. */
  public Fidelity fidelity() {
    return fidelity;
  }

  /**
   * Whether a value encodes legally but need not read back -- for example a non-finite
   * {@code Float}/{@code Double} into {@code numeric}, which has no {@code BigDecimal} form. Such a
   * value drops the read leg of a round-trip to the weak "no unchecked leak" invariant.
   *
   * @param value the value about to be written (may be {@code null})
   * @return whether the value is poison for this type
   */
  public boolean poison(@Nullable Object value) {
    return poison.test(value);
  }

  private static boolean isNonFiniteNumeric(@Nullable Object value) {
    if (value instanceof Float) {
      return !Float.isFinite((Float) value);
    }
    if (value instanceof Double) {
      return !Double.isFinite((Double) value);
    }
    return false;
  }
}
