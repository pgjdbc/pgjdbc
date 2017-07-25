/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql;

import java.sql.Array;
import java.sql.SQLException;

/**
 * Defines public PostgreSQL extensions to create {@link Array} objects.
 */
public interface PGArraySupport {

  /**
   * Creates an {@link Array} wrapping <i>elements</i>. This is similar to
   * java.sql.Connection#createArrayOf(String, Object[]), but also provides
   * support for primitive arrays.
   *
   * @param typeName
   *          The SQL name of the type to map the <i>elements</i> to.
   * @param elements
   *          The array of objects to map.
   * @return An {@link Array} wrapping <i>elements</i>.
   * @throws SQLException If for some reason the array cannot be created.
   * @see java.sql.Connection#createArrayOf(String, Object[])
   */
  Array createArrayOf(String typeName, Object elements) throws SQLException;
}
