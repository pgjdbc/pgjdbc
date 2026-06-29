/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Read-only catalog metadata a {@link Codec} reads about the type it handles.
 *
 * <p>This is the type half of the codec contract: every {@code decode}/{@code encode}
 * method receives a {@code TypeDescriptor} describing the PostgreSQL type of the value.
 * It exposes the {@code pg_type} (and, for ranges, {@code pg_range}) columns the codec
 * layer needs — OID, name, modifiers, element/array/base/subtype OIDs, composite fields,
 * and the {@code typtype}/{@code typcategory} discriminators — without tying a codec to
 * the driver's internal type class. The driver's own type implements this interface.</p>
 *
 * <p>The values describe the catalog as it was loaded. Container OIDs ({@link #getTypelem()},
 * {@link #getTypbasetype()}, {@link #getRangeSubtype()}) and {@link #getFields()} are filled
 * lazily, so a descriptor may report {@code 0} / {@code null} for a type whose detail has not
 * been loaded yet.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface TypeDescriptor {

  /**
   * Returns the type OID.
   *
   * @return the type OID
   */
  int getOid();

  /**
   * Returns the type name as a namespace-qualified {@link ObjectName}.
   *
   * @return the type name
   */
  ObjectName getTypeName();

  /**
   * Returns the fully qualified, display-ready type name.
   *
   * @return the full type name
   */
  String getFullName();

  /**
   * Returns the type modifier from {@code pg_type.typtypmod}.
   *
   * @return the type modifier, or {@code -1} when none applies
   */
  int getTyptypmod();

  /**
   * Returns the element type OID for an array type ({@code pg_type.typelem}).
   *
   * @return the element type OID, or {@code 0} if this is not an array type
   */
  int getTypelem();

  /**
   * Returns the OID of the array type whose element is this type.
   *
   * @return the array type OID, or {@code 0} if there is no corresponding array type
   */
  int getArrayOid();

  /**
   * Returns the base type OID for a domain ({@code pg_type.typbasetype}).
   *
   * @return the base type OID, or {@code 0} if this is not a domain type
   */
  int getTypbasetype();

  /**
   * Returns the range subtype OID for a range type ({@code pg_range.rngsubtype}).
   *
   * <p>{@link #getTypelem()} is {@code 0} for ranges, so the element the range is over
   * (for example {@code int4} for {@code int4range}) is carried here instead.</p>
   *
   * @return the range subtype OID, or {@code 0} if not a range or not yet loaded
   */
  int getRangeSubtype();

  /**
   * Returns the range type OID for a multirange type ({@code pg_range.rngtypid}, joined on
   * {@code rngmultitypid}).
   *
   * <p>A multirange ({@code typtype='m'}) carries its elements as ranges rather than scalars, so the
   * companion range type (for example {@code int4range} for {@code int4multirange}) is carried here.
   * Resolve that range with {@link CodecContext#resolveType(int)} to reach its subtype in turn.</p>
   *
   * @return the range type OID, or {@code 0} if not a multirange or not yet loaded
   */
  int getMultirangeRange();

  /**
   * Returns the {@code pg_type.typtype} discriminator
   * ({@code 'b'}=base, {@code 'c'}=composite, {@code 'e'}=enum, {@code 'd'}=domain,
   * {@code 'p'}=pseudo, {@code 'r'}=range, {@code 'm'}=multirange).
   *
   * @return the {@code typtype} character
   */
  char getTyptype();

  /**
   * Returns the {@code pg_type.typcategory} discriminator
   * ({@code 'A'}=array, {@code 'B'}=boolean, {@code 'N'}=numeric, {@code 'S'}=string, ...).
   *
   * @return the {@code typcategory} character
   */
  char getTypcategory();

  /**
   * Returns the array element delimiter from {@code pg_type.typdelim}.
   *
   * @return the delimiter character
   */
  char getDelimiter();

  /**
   * Returns the attributes of a composite type.
   *
   * <p>Returns {@code null} for a non-composite type, and for a composite type whose
   * attributes have not been loaded yet.</p>
   *
   * @return the composite fields, or {@code null}
   */
  @Nullable List<? extends PgField> getFields();

  /**
   * Reports whether this is an array type ({@code typcategory='A'}).
   *
   * @return true for an array type
   */
  default boolean isArray() {
    return getTypcategory() == 'A';
  }

  /**
   * Reports whether this is a domain type ({@code typtype='d'}).
   *
   * @return true for a domain type
   */
  default boolean isDomain() {
    return getTyptype() == 'd';
  }

  /**
   * Reports whether this is an enum type ({@code typtype='e'}).
   *
   * @return true for an enum type
   */
  default boolean isEnum() {
    return getTyptype() == 'e';
  }

  /**
   * Reports whether this is a composite type ({@code typtype='c'}).
   *
   * @return true for a composite type
   */
  default boolean isComposite() {
    return getTyptype() == 'c';
  }

  /**
   * Reports whether this is a multirange type ({@code typtype='m'}).
   *
   * @return true for a multirange type
   */
  default boolean isMultirange() {
    return getTyptype() == 'm';
  }
}
