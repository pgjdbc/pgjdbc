/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

/**
 * One attribute of a composite {@link TypeDescriptor}: its name and the OID of its type.
 *
 * <p>This is the read-only view a {@link TypeDescriptor} exposes to codecs. A codec
 * resolves the field's own {@link TypeDescriptor} from {@link #getTypeOid()} through the
 * codec context. The driver's internal field type implements this interface and may carry
 * further detail (position, type modifier) that the codec layer does not need.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface PgField {

  /**
   * Returns the field name.
   *
   * @return the field name
   */
  String getName();

  /**
   * Returns the OID of the field's type.
   *
   * @return the type OID
   */
  int getTypeOid();
}
