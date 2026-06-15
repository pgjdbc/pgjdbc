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
   * Creates a standard error for a value read from the database that cannot be
   * represented as {@code targetType}.
   *
   * <p>This is the read/decode direction (for example {@code getDate} on an int4
   * column), so the error carries {@link PSQLState#DATA_TYPE_MISMATCH}.</p>
   *
   * @param value decoded value, or null when the decoded value is SQL NULL
   * @param targetType target Java type name
   * @return conversion error
   */
  static SQLException cannotDecode(@Nullable Object value, String targetType) {
    return cannotDecode(value == null ? "null" : value.getClass().getName(), targetType);
  }

  /**
   * Creates a standard decode error from a source type name to a target Java
   * type name.
   *
   * <p>This is the read/decode direction, so the error carries
   * {@link PSQLState#DATA_TYPE_MISMATCH}.</p>
   *
   * @param sourceType source type name
   * @param targetType target Java type name
   * @return conversion error
   */
  static SQLException cannotDecode(String sourceType, String targetType) {
    return new PSQLException(
        GT.tr("Cannot convert {0} to {1}", sourceType, targetType),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  /**
   * Creates a standard error for a Java value that cannot be encoded as the
   * codec's PostgreSQL type.
   *
   * <p>This is the write/bind direction (for example binding a {@code byte[]} to
   * an int4 parameter), so the error carries
   * {@link PSQLState#INVALID_PARAMETER_TYPE}.</p>
   *
   * @param value the value being encoded, or null
   * @param targetType target PostgreSQL type name
   * @return conversion error
   */
  static SQLException cannotEncode(@Nullable Object value, String targetType) {
    return cannotEncode(value == null ? "null" : value.getClass().getName(), targetType);
  }

  /**
   * Creates a standard encode error from a source type name to a target
   * PostgreSQL type name.
   *
   * <p>This is the write/bind direction, so the error carries
   * {@link PSQLState#INVALID_PARAMETER_TYPE}.</p>
   *
   * @param sourceType source type name
   * @param targetType target PostgreSQL type name
   * @return conversion error
   */
  static SQLException cannotEncode(String sourceType, String targetType) {
    return new PSQLException(
        GT.tr("Cannot convert {0} to {1}", sourceType, targetType),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
