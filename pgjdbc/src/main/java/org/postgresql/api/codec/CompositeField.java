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
public interface CompositeField {

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

  /**
   * Returns the attribute's type modifier ({@code pg_attribute.atttypmod}), for example the precision
   * and scale of a {@code numeric(10,2)} field. A codec stamps this onto the field's descriptor
   * ({@code ctx.resolveType(getTypeOid(), getTypmod())}) so a modifier-sensitive field decodes
   * correctly. The default is {@code -1}, meaning no modifier applies.
   *
   * @return the attribute type modifier, or {@code -1} when none applies
   */
  default int getTypmod() {
    return -1;
  }
}
