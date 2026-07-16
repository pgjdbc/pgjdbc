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
   * Returns the primary PostgreSQL type name this codec registers under.
   *
   * <p>This is an unqualified name (for example {@code "int4"}, {@code "text"}, {@code "geometry"}),
   * not the schema-qualified {@link TypeDescriptor#getTypeName() ObjectName} a descriptor reports. A
   * codec may register under further names as aliases; this method returns the primary one.</p>
   *
   * @return the primary, unqualified PostgreSQL type name
   */
  String getPrimaryTypeName();

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
