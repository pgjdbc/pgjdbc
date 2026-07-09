/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

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
}
