/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Base marker interface for PostgreSQL type codecs.
 *
 * <p>Codecs handle conversion between Java objects and PostgreSQL wire format
 * (text or binary). Implementations should be stateless and thread-safe.</p>
 *
 * <p>Implementations typically implement both {@link BinaryCodec} and {@link TextCodec}
 * interfaces, though some may only support one format.</p>
 *
 * @see BinaryCodec
 * @see TextCodec
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface Codec {

  /**
   * Returns the PostgreSQL type name this codec handles.
   *
   * <p>This is the type name without schema qualification (e.g., "int4", "text", "geometry").
   * The same codec may handle multiple type names via aliases.</p>
   *
   * @return the PostgreSQL type name
   */
  String getTypeName();

  /**
   * Returns the default Java class this codec produces when decoding.
   *
   * <p>This is used for {@code getObject()} without a target class parameter
   * and for metadata operations.</p>
   *
   * @return the default Java class for decoded values
   */
  Class<?> getDefaultJavaType();

  /**
   * Creates a standard conversion error for a decoded value that cannot be
   * represented as {@code targetType}.
   *
   * @param value decoded value, or null when the decoded value is SQL NULL
   * @param targetType target Java type name
   * @return conversion error
   */
  static SQLException cannotConvert(@Nullable Object value, String targetType) {
    return cannotConvert(value == null ? "null" : value.getClass().getName(), targetType);
  }

  /**
   * Creates a standard conversion error from a source type name to a target
   * Java type name.
   *
   * @param sourceType source type name
   * @param targetType target Java type name
   * @return conversion error
   */
  static SQLException cannotConvert(String sourceType, String targetType) {
    return new PSQLException(
        GT.tr("Cannot convert {0} to {1}", sourceType, targetType),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
