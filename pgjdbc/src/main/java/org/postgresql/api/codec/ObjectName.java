/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A PostgreSQL object name: an optional namespace (schema) plus a local name.
 *
 * <p>This is the read-only view a {@link TypeDescriptor} exposes to codecs, so the
 * codec layer does not depend on the driver's internal name class. The driver's own
 * name type implements this interface.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface ObjectName {

  /**
   * Returns the namespace (schema) part, or {@code null} for an unqualified name.
   *
   * @return the namespace, or {@code null}
   */
  @Nullable String getNamespace();

  /**
   * Returns the local (unqualified) name.
   *
   * @return the name
   */
  String getName();
}
